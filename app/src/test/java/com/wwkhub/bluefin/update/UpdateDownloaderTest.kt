package com.wwkhub.bluefin.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The downloader is plain java.net, so its failure matrix runs against a tiny
 * socket-level HTTP server instead of any Android or third-party fixture.
 */
class UpdateDownloaderTest {
    private class TinyServer(private val respond: (OutputStream) -> Unit) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                Thread {
                    client.use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        reader.readLine()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        try {
                            respond(c.getOutputStream())
                        } catch (_: Exception) {
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }

        init {
            thread.isDaemon = true
            thread.start()
        }

        override fun close() {
            socket.close()
        }
    }

    private fun headers(out: OutputStream, status: String, length: Long) {
        out.write("HTTP/1.1 $status\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun tempTarget(): File =
        File(Files.createTempDirectory("bluefin-update-test").toFile(), "pkg.apk")

    @Test
    fun downloadsAndHashesTheWholeBody() {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        TinyServer { out ->
            headers(out, "200 OK", body.size.toLong())
            out.write(body)
            out.flush()
        }.use { server ->
            val target = tempTarget()
            var last = 0L
            val result = UpdateDownloader(allowInsecureLan = true).download(
                "http://127.0.0.1:${server.port}/pkg.apk", target,
            ) { read, _ -> last = read }
            assertTrue(result is UpdateDownloader.Result.Done)
            result as UpdateDownloader.Result.Done
            assertEquals(UpdatePolicy.sha256Hex(body), result.sha256)
            assertEquals(body.size.toLong(), result.bytes)
            assertEquals(body.size.toLong(), last)
            assertTrue(target.readBytes().contentEquals(body))
        }
    }

    @Test
    fun followsSameOriginRedirectButRejectsDifferentOrigin() {
        TinyServer { out ->
            headers(out, "200 OK", 3)
            out.write(byteArrayOf(1, 2, 3))
        }.use { destination ->
            TinyServer { out ->
                out.write(
                    "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${destination.port}/pkg.apk\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(),
                )
            }.use { source ->
                val target = tempTarget()
                val result = UpdateDownloader(allowInsecureLan = true).download(
                    "http://127.0.0.1:${source.port}/pkg.apk", target,
                ) { _, _ -> }
                assertTrue(result is UpdateDownloader.Result.Failed)
                assertFalse(target.exists())
            }
        }

        val redirected = java.util.concurrent.atomic.AtomicBoolean()
        TinyServer { out ->
            if (!redirected.getAndSet(true)) {
                out.write("HTTP/1.1 302 Found\r\nLocation: /file.apk\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                headers(out, "200 OK", 3)
                out.write(byteArrayOf(1, 2, 3))
            }
        }.use { server ->
            val result = UpdateDownloader(allowInsecureLan = true).download(
                "http://127.0.0.1:${server.port}/pkg.apk", tempTarget(),
            ) { _, _ -> }
            assertTrue(result is UpdateDownloader.Result.Done)
        }
    }

    @Test
    fun rejectsTruncatedBody() {
        TinyServer { out ->
            headers(out, "200 OK", 10)
            out.write(byteArrayOf(1, 2, 3))
        }.use { server ->
            val target = tempTarget()
            val result = UpdateDownloader(allowInsecureLan = true).download(
                "http://127.0.0.1:${server.port}/pkg.apk", target,
            ) { _, _ -> }
            assertTrue(result is UpdateDownloader.Result.Failed)
            assertFalse(target.exists())
        }
    }

    @Test
    fun httpErrorsFailWithoutLeavingAFile() {
        TinyServer { out ->
            headers(out, "404 Not Found", 0)
            out.flush()
        }.use { server ->
            val target = tempTarget()
            val result = UpdateDownloader(allowInsecureLan = true).download(
                "http://127.0.0.1:${server.port}/pkg.apk", target,
            ) { _, _ -> }
            assertTrue(result is UpdateDownloader.Result.Failed)
            assertEquals("HTTP 404", (result as UpdateDownloader.Result.Failed).reason)
            assertFalse(target.exists())
        }
    }

    @Test
    fun declaredOversizeIsRejectedBeforeReading() {
        TinyServer { out ->
            headers(out, "200 OK", UpdatePolicy.MAX_APK_BYTES + 1)
            out.write(ByteArray(16))
            out.flush()
        }.use { server ->
            val result = UpdateDownloader(allowInsecureLan = true).download(
                "http://127.0.0.1:${server.port}/pkg.apk", tempTarget(),
            ) { _, _ -> }
            assertTrue(result is UpdateDownloader.Result.Failed)
            assertEquals("TOO_LARGE", (result as UpdateDownloader.Result.Failed).reason)
        }
    }

    @Test
    fun cancellationStopsAndRemovesThePartialFile() {
        val started = CountDownLatch(1)
        TinyServer { out ->
            headers(out, "200 OK", 8192L * 200)
            repeat(200) {
                out.write(ByteArray(8192))
                out.flush()
                started.countDown()
                Thread.sleep(20)
            }
        }.use { server ->
            val target = tempTarget()
            val downloader = UpdateDownloader(allowInsecureLan = true)
            val outcome = java.util.concurrent.atomic.AtomicReference<UpdateDownloader.Result>()
            val worker = Thread {
                outcome.set(
                    downloader.download("http://127.0.0.1:${server.port}/pkg.apk", target) { _, _ -> },
                )
            }
            worker.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            downloader.cancelled = true
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertTrue(outcome.get() is UpdateDownloader.Result.Cancelled)
            assertFalse(target.exists())
        }
    }
}