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
 * Downloads and installs the offline TTS engine into app-private storage.
 *
 * Nothing is bundled in the APK and nothing downloads without an explicit user
 * confirmation. Each file lands as a `.part` in a staging dir, is checked
 * against a minimum size so a truncated body can never be mistaken for a good
 * download, and the staging dir is renamed into place only after all five files
 * pass.
 */
internal object TtsModelManager {
    private const val TAG = "BluefinTts"
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var installing = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var active: HttpURLConnection? = null

    private class EngineCorruptException : IOException()

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
    }

    fun cancel() {
        cancelled = true
        active?.disconnect()
    }

    /**
     * Runs on a worker thread. [onProgress] gets 0..100 across all five files.
     * [onDone] gets a user-facing error string, or null on success. Both on the
     * main thread.
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
                    error = appContext.getString(R.string.speech_engine_cancelled)
                } catch (_: EngineCorruptException) {
                    error = appContext.getString(R.string.speech_engine_corrupt)
                } catch (failure: Exception) {
                    Log.w(TAG, "engine install failed type=${failure.javaClass.simpleName}")
                    error = appContext.getString(R.string.speech_engine_failed)
                } finally {
                    installing = false
                    active = null
                    cleanupPartial(appContext)
                    val result = error
                    handler.post { onDone(result) }
                }
            },
            "bluefin-tts-model",
        ).apply { isDaemon = true }.start()
    }

    private fun downloadAndInstall(context: Context, onProgress: (Int) -> Unit) {
        val dir = modelDir(context)
        val parent = dir.parentFile ?: throw IOException("no files dir")
        parent.mkdirs()
        val staging = File(parent, "${OfflineTtsSpec.ID}.staging")
        staging.deleteRecursively()
        staging.mkdirs()

        var doneBytes = 0L
        for (file in OfflineTtsSpec.FILES) {
            val part = File(staging, "$file.part")
            val expected = OfflineTtsSpec.expectedBytes(file)
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
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    lastError = failure
                    Log.w(TAG, "engine source failed file=$file type=${failure.javaClass.simpleName}")
                    part.delete()
                }
            }
            if (!downloaded) throw lastError ?: IOException("no download source")
            if (part.length() < OfflineTtsSpec.minBytesFor(file)) {
                staging.deleteRecursively()
                throw EngineCorruptException()
            }
            doneBytes += expected
            if (!part.renameTo(File(staging, file))) {
                staging.deleteRecursively()
                throw IOException("staging rename failed")
            }
        }

        val sizes = OfflineTtsSpec.FILES.associateWith { File(staging, it).length() }
        if (!OfflineTtsSpec.isComplete(sizes)) {
            staging.deleteRecursively()
            throw EngineCorruptException()
        }
        postProgress(onProgress, 100)
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) {
            staging.deleteRecursively()
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
