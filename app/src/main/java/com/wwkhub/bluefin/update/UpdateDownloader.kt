package com.wwkhub.bluefin.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * Streaming APK download with a running SHA-256, a hard size cap and cooperative
 * cancellation. Plain java.net so the failure matrix runs as a JVM unit test.
 */
internal class UpdateDownloader(private val allowInsecureLan: Boolean = false) {
    sealed class Result {
        data class Done(val sha256: String, val bytes: Long) : Result()
        data class Failed(val reason: String) : Result()
        object Cancelled : Result()
    }

    @Volatile
    var cancelled: Boolean = false

    fun download(url: String, target: File, onProgress: (Long, Long?) -> Unit): Result {
        target.parentFile?.mkdirs()
        val connection = try {
            UpdateHttp.open(url, allowInsecureLan)
        } catch (error: IOException) {
            return Result.Failed(error.message ?: "connect")
        }
        var written = 0L
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) return Result.Failed("HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            if (total != null && total > UpdatePolicy.MAX_APK_BYTES) return Result.Failed("TOO_LARGE")
            val digest = MessageDigest.getInstance("SHA-256")
            var stopped: String? = null
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) {
                            stopped = "cancelled"
                            break
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > UpdatePolicy.MAX_APK_BYTES) {
                            stopped = "oversize"
                            break
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(written, total)
                    }
                }
            }
            // Streams are closed now; Windows refuses to delete an open file.
            when (stopped) {
                "cancelled" -> {
                    target.delete()
                    return Result.Cancelled
                }
                "oversize" -> {
                    target.delete()
                    return Result.Failed("TOO_LARGE")
                }
            }
            if (total != null && written != total) {
                target.delete()
                return Result.Failed("下载长度不完整")
            }
            return Result.Done(digest.digest().joinToString("") { "%02x".format(it) }, written)
        } catch (error: IOException) {
            target.delete()
            return Result.Failed(error.message ?: "io")
        } finally {
            connection.disconnect()
        }
    }
}