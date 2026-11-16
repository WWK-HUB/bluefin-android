package com.wwkhub.bluefin

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask

class ModelConnectionTest {
    @Test
    fun acceptsAnActualCompletion() {
        withResponse("""{"choices":[{"message":{"content":"你好"}}]}""") { endpoint ->
            assertTrue(ChatCompletionsClient().test(endpoint, "local-test") >= 0)
        }
    }

    @Test
    fun rejectsEmptyHtmlAndIncompleteSuccessResponses() {
        for (body in listOf("", "<html>API portal</html>", """{"choices":[]}""", """{"choices":[{"message":{"content":" "}}]}""")) {
            withResponse(body) { endpoint ->
                assertThrows(InvalidChatTestResponse::class.java) {
                    ChatCompletionsClient().test(endpoint, "local-test")
                }
            }
        }
    }

    @Test
    fun expiredCaptureIsRejectedBeforeConnecting() {
        val scope = ScreenCaptureScope()
        val permit = checkNotNull(scope.permit())
        val app = ModelEndpoint("custom", ModelCatalog.Region.MAINLAND, "http://127.0.0.1:1",
            "mock", null, 64, "")
        scope.stop()
        assertThrows(ScreenSendExpired::class.java) {
            ChatCompletionsClient().stream(
                app, "local-test", "", listOf(ChatLine(ChatRole.USER, "look")), {}, { false },
                canSendScreen = { scope.allows(permit) },
            )
        }
    }

    private fun withResponse(body: String, check: (ModelEndpoint) -> Unit) {
        ServerSocket().use { server ->
            server.soTimeout = 5_000
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            val response = FutureTask {
                server.accept().use { client ->
                    client.soTimeout = 5_000
                    val request = client.getInputStream().bufferedReader()
                    val headers = generateSequence(request::readLine).takeWhile { it.isNotEmpty() }.toList()
                    val length = headers.first { it.startsWith("Content-Length:", ignoreCase = true) }
                        .substringAfter(':').trim().toInt()
                    repeat(length) { check(request.read() != -1) }
                    val output = client.getOutputStream()
                    val bytes = body.toByteArray()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(bytes)
                }
            }
            Thread(response, "mock-chat-completions").apply { isDaemon = true; start() }
            check(ModelEndpoint("custom", ModelCatalog.Region.MAINLAND, "http://127.0.0.1:${server.localPort}",
                "mock", null, 64, ""))
            response.get()
        }
    }
}
