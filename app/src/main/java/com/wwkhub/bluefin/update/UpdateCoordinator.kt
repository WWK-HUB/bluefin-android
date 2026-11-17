package com.wwkhub.bluefin.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wwkhub.bluefin.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import android.os.SystemClock

/**
 * Orchestrates check → download → verify → hand-off to the system installer.
 * Sources are tried in order (domestic primary first, GitHub fallback second);
 * the domestic path never needs GitHub to be reachable. Every user-visible
 * step is posted to the main thread; the work runs on one plain Thread, the
 * same idiom as the rest of the app.
 */
internal class UpdateCoordinator(context: Context, private val listener: Listener) {
    interface Listener {
        fun onChecking(sourceLabel: String)
        fun onAvailable(manifest: UpdateManifest, sourceLabel: String)
        fun onUpToDate()
        fun onProgress(read: Long, total: Long?)
        fun onVerifying()
        fun onReadyToInstall(file: File, manifest: UpdateManifest)
        fun onFailure(message: String)
        fun onCancelled()
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var downloader: UpdateDownloader? = null
    @Volatile
    private var cancelled = false
    @Volatile
    private var busy = false

    private fun allowInsecureLan(): Boolean =
        BuildConfig.DEBUG && BuildConfig.UPDATE_ALLOW_INSECURE_LAN

    private fun sources(): List<Pair<String, String>> = listOf(
        BuildConfig.UPDATE_PRIMARY_MANIFEST_URL,
        BuildConfig.UPDATE_FALLBACK_MANIFEST_URL,
    ).filter { it.isNotBlank() }.map { UpdatePolicy.sourceLabel(it) to it }

    fun check(): Boolean {
        if (busy) return false
        busy = true
        cancelled = false
        Thread {
            try {
                var lastError = "检查更新失败，请检查网络后重试。"
                for ((label, url) in sources()) {
                    if (cancelled) return@Thread finish {}
                    post { listener.onChecking(label) }
                    val valid = UpdateUrlPolicy.check(url, allowInsecureLan())
                    if (valid is UpdateUrlPolicy.Check.Invalid) {
                        lastError = valid.reason
                        continue
                    }
                    val raw = fetchText((valid as UpdateUrlPolicy.Check.Valid).url)
                    if (raw == null) {
                        lastError = "无法连接更新源（$label）。"
                        continue
                    }
                    val manifest = UpdateManifest.parse(raw)
                    if (manifest == null) {
                        lastError = "更新清单无法读取（$label）。"
                        continue
                    }
                    if (UpdateUrlPolicy.check(manifest.apkUrl, allowInsecureLan()) !is UpdateUrlPolicy.Check.Valid) {
                        lastError = "更新清单的下载地址不受信任（$label）。"
                        continue
                    }
                    val installed = UpdateInstaller.installedVersionCode(appContext)
                    if (UpdatePolicy.isNewer(manifest.versionCode, installed)) {
                        finish { listener.onAvailable(manifest, label) }
                    } else {
                        finish { listener.onUpToDate() }
                    }
                    return@Thread
                }
                finish { listener.onFailure(lastError) }
            } catch (error: Exception) {
                Log.w(TAG, "update check failed", error)
                finish { listener.onFailure("检查更新失败，请重试。") }
            }
        }.start()
        return true
    }

    fun download(manifest: UpdateManifest): Boolean {
        if (busy) return false
        busy = true
        cancelled = false
        Thread {
            var temporary: File? = null
            try {
                val valid = UpdateUrlPolicy.check(manifest.apkUrl, allowInsecureLan())
                if (valid is UpdateUrlPolicy.Check.Invalid) {
                    finish { listener.onFailure(valid.reason) }
                    return@Thread
                }
                val directory = File(appContext.cacheDir, "updates")
                directory.mkdirs()
                val target = File.createTempFile("bluefin-${manifest.versionCode}-", ".apk", directory)
                temporary = target
                val worker = UpdateDownloader(allowInsecureLan()).also { downloader = it }
                if (cancelled) {
                    finish {}
                    return@Thread
                }
                post { listener.onProgress(0, manifest.sizeBytes) }
                var lastProgress = SystemClock.elapsedRealtime()
                val result = worker.download((valid as UpdateUrlPolicy.Check.Valid).url, target) { read, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 100 || read == total) {
                        lastProgress = now
                        post { listener.onProgress(read, total ?: manifest.sizeBytes) }
                    }
                }
                when (result) {
                    is UpdateDownloader.Result.Cancelled -> {
                        finish {}
                        return@Thread
                    }
                    is UpdateDownloader.Result.Failed -> {
                        finish { listener.onFailure("下载失败：${result.reason}。可重试。") }
                        return@Thread
                    }
                    is UpdateDownloader.Result.Done -> Unit
                }
                if (cancelled) {
                    target.delete()
                    finish {}
                    return@Thread
                }
                post { listener.onVerifying() }
                if (manifest.sizeBytes != null && manifest.sizeBytes != result.bytes) {
                    finish { listener.onFailure("安装包大小与更新清单不一致，请重试。") }
                    return@Thread
                }
                if (!UpdatePolicy.shaMatches(manifest.sha256, result.sha256)) {
                    target.delete()
                    finish { listener.onFailure("SHA-256 校验失败，安装包可能损坏或被替换。") }
                    return@Thread
                }
                val identity = UpdateInstaller.archiveIdentity(appContext, target)
                val installed = UpdateInstaller.installedVersionCode(appContext)
                when (val check = UpdatePolicy.checkArchive(
                    appContext.packageName, manifest, identity?.first, identity?.second, installed,
                )) {
                    is UpdatePolicy.ArchiveCheck.Rejected -> {
                        target.delete()
                        val reason = when (check.reason) {
                            "PACKAGE" -> "安装包名与当前应用不一致。"
                            "VERSION" -> "安装包版本与更新清单不一致。"
                            "NOT_NEWER" -> "更新包版本不高于当前版本。"
                            else -> check.reason
                        }
                        finish { listener.onFailure(reason) }
                        return@Thread
                    }
                    UpdatePolicy.ArchiveCheck.Ok -> Unit
                }
                val installedSigners = UpdateInstaller.installedSigners(appContext)
                val archiveSigners = UpdateInstaller.archiveSigners(appContext, target)
                if (!UpdatePolicy.signaturesMatch(installedSigners, archiveSigners)) {
                    target.delete()
                    finish { listener.onFailure("更新包签名与已安装应用不一致，无法覆盖升级。请先确认签名迁移方案。") }
                    return@Thread
                }
                temporary = null
                finish(cancelledFile = target) { listener.onReadyToInstall(target, manifest) }
            } catch (error: Exception) {
                Log.w(TAG, "update download or verification failed", error)
                finish { listener.onFailure("无法保存或验证安装包，请重试。") }
            } finally {
                temporary?.delete()
            }
        }.start()
        return true
    }

    fun cancel() {
        cancelled = true
        downloader?.cancelled = true
    }

    private fun fetchText(url: String): String? {
        val connection = try {
            UpdateHttp.open(url, allowInsecureLan())
        } catch (error: IOException) {
            Log.w(TAG, "update source connect failed", error)
            return null
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    if (cancelled) return null
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > UpdatePolicy.MAX_MANIFEST_BYTES) return null
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (error: IOException) {
            Log.w(TAG, "update source read failed", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun post(block: () -> Unit) {
        handler.post { if (!cancelled) block() }
    }

    private fun finish(cancelledFile: File? = null, block: () -> Unit) {
        // Release the busy state before invoking UI callbacks, which may retry.
        handler.post {
            val wasCancelled = cancelled
            downloader = null
            busy = false
            if (wasCancelled) {
                cancelledFile?.delete()
                listener.onCancelled()
            } else {
                block()
            }
        }
    }

    companion object {
        private const val TAG = "BluefinUpdate"
    }
}