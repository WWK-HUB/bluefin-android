package com.wwkhub.bluefin.voice

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
 * Downloads and installs the offline ASR model into app-private storage.
 * Nothing is bundled in the APK and nothing downloads without an explicit
 * user confirmation in the setup flow or settings.
 *
 * The two model files land as .part downloads in a staging dir, are checked
 * against minimum sizes, then the staging dir is renamed into place. Each
 * file tries hf-mirror first and huggingface.co second.
 */
internal object VoiceModelManager {
    private const val TAG = "BluefinVoice"
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var installing = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var active: HttpURLConnection? = null

    private class ModelCorruptException : IOException()

    fun modelDir(context: Context): File =
        File(context.filesDir, "voice-models/${VoiceModelSpec.ID}")

    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        return VoiceModelSpec.isComplete(
            File(dir, VoiceModelSpec.MODEL_FILE).length(),
            File(dir, VoiceModelSpec.TOKENS_FILE).length(),
        )
    }

    fun isInstalling(): Boolean = installing

    fun delete(context: Context) {
        modelDir(context).deleteRecursively()
    }

    fun cancel() {
        cancelled = true
        active?.disconnect()
    }

    /**
     * Runs on a worker thread. [onProgress] gets 0..100 across both files.
     * [onDone] gets a user-facing error string, or null on success. Both on
     * the main thread.
     */
    fun install(context: Context, onProgress: (Int) -> Unit, onDone: (String?) -> Unit) {
        if (installing) return
        installing = true
        cancelled = false
        val appContext = context.applicationContext
        Thread(
            {
                var error: String? = null
                try {
                    downloadAndInstall(appContext, onProgress)
                } catch (_: CancellationException) {
                    error = "已取消下载。"
                } catch (_: ModelCorruptException) {
                    error = appContext.getString(R.string.voice_model_corrupt)
                } catch (_: Exception) {
                    error = appContext.getString(R.string.voice_model_failed)
                } finally {
                    installing = false
                    active = null
                    cleanupPartial(appContext)
                    val result = error
                    handler.post { onDone(result) }
                }
            },
            "bluefin-voice-model",
        ).apply { isDaemon = true }.start()
    }

    private fun downloadAndInstall(context: Context, onProgress: (Int) -> Unit) {
        val dir = modelDir(context)
        val parent = dir.parentFile ?: throw IOException("no files dir")
        parent.mkdirs()
        val staging = File(parent, "${VoiceModelSpec.ID}.staging")
        staging.deleteRecursively()
        staging.mkdirs()

        var doneBytes = 0L
        for (file in VoiceModelSpec.FILES) {
            val part = File(staging, "$file.part")
            val expected = VoiceModelSpec.expectedBytes(file)
            var lastError: Exception? = null
            var downloaded = false
            for (url in VoiceModelSpec.urlsFor(file)) {
                try {
                    download(url, part) { received ->
                        val percent = ((doneBytes + received) * 100 / VoiceModelSpec.TOTAL_BYTES)
                            .toInt().coerceIn(0, 99)
                        postProgress(onProgress, percent)
                    }
                    downloaded = true
                    break
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    lastError = error
                    Log.w(TAG, "model source failed file=$file type=${error.javaClass.simpleName}")
                    part.delete()
                }
            }
            if (!downloaded) throw lastError ?: IOException("no download source")
            if (part.length() < minBytesFor(file)) {
                staging.deleteRecursively()
                throw ModelCorruptException()
            }
            doneBytes += expected
            if (!part.renameTo(File(staging, file))) {
                staging.deleteRecursively()
                throw IOException("staging rename failed")
            }
        }

        val modelBytes = File(staging, VoiceModelSpec.MODEL_FILE).length()
        val tokensBytes = File(staging, VoiceModelSpec.TOKENS_FILE).length()
        if (!VoiceModelSpec.isComplete(modelBytes, tokensBytes)) {
            staging.deleteRecursively()
            throw ModelCorruptException()
        }
        postProgress(onProgress, 100)
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) {
            staging.deleteRecursively()
            throw IOException("install rename failed")
        }
        Log.i(TAG, "voice model installed bytes=$modelBytes+$tokensBytes")
    }

    private fun minBytesFor(file: String): Long = when (file) {
        VoiceModelSpec.MODEL_FILE -> VoiceModelSpec.MIN_MODEL_BYTES
        else -> VoiceModelSpec.MIN_TOKENS_BYTES
    }

    private fun download(url: String, part: File, onBytes: (Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        active = connection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            connection.connect()
            if (cancelled) throw CancellationException()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var lastPosted = -1L
                    while (true) {
                        if (cancelled) throw CancellationException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        // Post at most ~100 updates per file.
                        val step = if (total > 0) total / 100 else 64 * 1024
                        if (received - lastPosted >= step) {
                            lastPosted = received
                            onBytes(received)
                        }
                    }
                    output.fd.sync()
                    if (total > 0 && received != total) throw IOException("truncated body")
                    onBytes(received)
                }
            }
        } finally {
            connection.disconnect()
            active = null
        }
    }

    private fun cleanupPartial(context: Context) {
        val parent = modelDir(context).parentFile ?: return
        parent.listFiles()?.forEach { child ->
            if (child.name.endsWith(".part") || child.name.endsWith(".staging")) {
                child.deleteRecursively()
            }
        }
    }

    private fun postProgress(onProgress: (Int) -> Unit, percent: Int) {
        handler.post { onProgress(percent) }
    }
}