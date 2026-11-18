package com.wwkhub.bluefin.voice.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wwkhub.bluefin.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

/**
 * On-demand download of the ZipVoice fallback engine into app-private storage.
 *
 * Nothing downloads without explicit user consent, nothing ships in the APK,
 * and an already-installed engine is never re-downloaded. Downloads support
 * pause/resume (HTTP Range against the existing `.part`), cancel, and retry
 * after failure; each file is size-checked before the staging dir is renamed
 * into place. After installation the caller runs [LocalEngineVerifier] so the
 * engine only becomes a usable fallback once a real inference succeeds.
 */
internal object TtsModelManager {
    private const val TAG = "BluefinTts"
    private val handler = Handler(Looper.getMainLooper())

    sealed class InstallResult {
        object Success : InstallResult()
        object Paused : InstallResult()
        object Cancelled : InstallResult()
        data class Failed(val message: String) : InstallResult()
    }

    @Volatile
    private var installing = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var paused = false

    @Volatile
    private var active: HttpURLConnection? = null

    private class EngineCorruptException : IOException()
    private class PauseException : IOException()

    fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "tts-models/${OfflineTtsSpec.ID}")

    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        if (!dir.isDirectory) return false
        val sizes = OfflineTtsSpec.FILES.associateWith { File(dir, it).length() }
        return OfflineTtsSpec.isComplete(sizes)
    }

    fun isInstalling(): Boolean = installing

    fun delete(context: Context) {
        modelDir(context).deleteRecursively()
        stagingDir(context).deleteRecursively()
    }

    fun cancel() {
        cancelled = true
        active?.disconnect()
    }

    /** Keeps the partial files so a later install resumes where it stopped. */
    fun pause() {
        paused = true
        active?.disconnect()
    }

    /** True when a paused download left resumable partial files behind. */
    fun hasPartialDownload(context: Context): Boolean {
        val staging = stagingDir(context)
        return staging.isDirectory && staging.listFiles().orEmpty().any { it.name.endsWith(".part") && it.length() > 0 }
    }

    /**
     * Runs on a worker thread. [onProgress] gets 0..100 across all five files.
     * [onDone] reports the terminal state on the main thread.
     */
    fun install(context: Context, onProgress: (Int) -> Unit, onDone: (InstallResult) -> Unit) {
        if (installing) return
        installing = true
        cancelled = false
        paused = false
        val appContext = context.applicationContext
        Thread(
            {
                var result: InstallResult = InstallResult.Success
                try {
                    downloadAndInstall(appContext, onProgress)
                } catch (_: PauseException) {
                    result = InstallResult.Paused
                } catch (_: CancellationException) {
                    result = InstallResult.Cancelled
                } catch (_: EngineCorruptException) {
                    result = InstallResult.Failed(appContext.getString(R.string.speech_engine_corrupt))
                } catch (failure: Exception) {
                    Log.w(TAG, "engine install failed type=${failure.javaClass.simpleName}")
                    result = InstallResult.Failed(appContext.getString(R.string.speech_engine_failed))
                } finally {
                    installing = false
                    active = null
                    if (result is InstallResult.Cancelled) cleanupStaging(appContext)
                    val terminal = result
                    handler.post { onDone(terminal) }
                }
            },
            "bluefin-tts-model",
        ).apply { isDaemon = true }.start()
    }

    private fun stagingDir(context: Context): File =
        File(modelDir(context).parentFile, "${OfflineTtsSpec.ID}.staging")

    private fun downloadAndInstall(context: Context, onProgress: (Int) -> Unit) {
        val dir = modelDir(context)
        val parent = dir.parentFile ?: throw IOException("no files dir")
        parent.mkdirs()
        val staging = stagingDir(context)
        staging.mkdirs()

        var doneBytes = 0L
        for (file in OfflineTtsSpec.FILES) {
            val final = File(staging, file)
            val part = File(staging, "$file.part")
            val expected = OfflineTtsSpec.expectedBytes(file)
            // A previous pause/resume may have completed this file already.
            if (final.isFile && final.length() >= OfflineTtsSpec.minBytesFor(file)) {
                doneBytes += expected
                continue
            }
            var lastError: Exception? = null
            var downloaded = false
            for (url in OfflineTtsSpec.urlsFor(file)) {
                try {
                    download(url, part) { received ->
                        val percent = ((doneBytes + received) * 100 / OfflineTtsSpec.TOTAL_BYTES)
                            .toInt().coerceIn(0, 99)
                        postProgress(onProgress, percent)
                    }
                    downloaded = true
                    break
                } catch (pause: PauseException) {
                    throw pause
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    lastError = failure
                    Log.w(TAG, "engine source failed file=$file type=${failure.javaClass.simpleName}")
                    // Keep the partial bytes: the next source (or a retry) resumes.
                }
            }
            if (!downloaded) throw lastError ?: IOException("no download source")
            if (part.length() < OfflineTtsSpec.minBytesFor(file)) {
                part.delete()
                throw EngineCorruptException()
            }
            doneBytes += expected
            if (!part.renameTo(final)) {
                throw IOException("staging rename failed")
            }
        }

        val sizes = OfflineTtsSpec.FILES.associateWith { File(staging, it).length() }
        if (!OfflineTtsSpec.isComplete(sizes)) {
            throw EngineCorruptException()
        }
        postProgress(onProgress, 100)
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) {
            throw IOException("install rename failed")
        }
        Log.i(TAG, "tts engine installed files=${OfflineTtsSpec.FILES.size}")
    }

    private fun download(url: String, part: File, onBytes: (Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        active = connection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        var resumeFrom = part.takeIf { it.isFile }?.length() ?: 0L
        if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        try {
            connection.connect()
            checkPaused()
            val code = connection.responseCode
            val appending: Boolean
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> appending = true
                HttpURLConnection.HTTP_OK -> {
                    // Server ignored Range: restart this file from zero.
                    appending = false
                    resumeFrom = 0L
                    part.delete()
                }
                else -> throw IOException("HTTP $code")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                // append=true keeps the paused prefix; false restarts the file.
                java.io.FileOutputStream(part, appending).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = resumeFrom
                    var lastPosted = -1L
                    while (true) {
                        checkPaused()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val step = if (total > 0) (total + resumeFrom) / 100 else 64 * 1024
                        if (received - lastPosted >= step) {
                            lastPosted = received
                            onBytes(received)
                        }
                    }
                    output.fd.sync()
                    if (total > 0 && received != resumeFrom + total) throw IOException("truncated body")
                    onBytes(received)
                }
            }
        } finally {
            connection.disconnect()
            active = null
        }
    }

    private fun checkPaused() {
        if (cancelled) throw CancellationException()
        if (paused) throw PauseException()
    }

    private fun cleanupStaging(context: Context) {
        stagingDir(context).deleteRecursively()
    }

    private fun postProgress(onProgress: (Int) -> Unit, percent: Int) {
        handler.post { onProgress(percent) }
    }
}