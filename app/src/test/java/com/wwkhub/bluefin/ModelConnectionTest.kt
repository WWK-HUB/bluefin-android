package com.wwkhub.bluefin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask

class ModelConnectionTest {
    @Test
    fun acceptsAnActualCompletion() {
        withResponse("""{"choices":[{"message":{"content":"你好"}}]}""") { endpoint ->
            val result = ChatCompletionsClient().test(endpoint, "local-test")
            assertTrue(result.elapsedMillis >= 0)
            assertFalse(result.outputLimitReached)
        }
    }

    @Test
    fun testConnectionUsesTheSameStreamingModeAsChatAndAcceptsItsReply() {
        val response = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n"
        withResponse(response, onRequest = { headers, body ->
            assertTrue(body.getBoolean("stream"))
            assertEquals(64, body.getInt("max_tokens"))
            assertTrue(headers.any { it.equals("Accept: text/event-stream", ignoreCase = true) })
        }) { endpoint ->
            assertFalse(ChatCompletionsClient().test(endpoint, "local-test").outputLimitReached)
        }
    }

    @Test
    fun officialOpenAiUsesTheCurrentTokenParameterWithoutChangingGateways() {
        val client = ChatCompletionsClient()
        val endpoint = ModelEndpoint(ModelApiFormat.OPENAI, "https://api.openai.com", "o3", null, 8192, "")
        assertEquals(8192, client.connectionTestBody(endpoint).getInt("max_completion_tokens"))
        assertFalse(client.connectionTestBody(endpoint).has("max_tokens"))
        assertEquals(8192, client.connectionTestBody(endpoint.copy(url = "https://gateway.example/v1")).getInt("max_tokens"))
    }

    @Test
    fun anthropicUsesMessagesAuthenticationSystemBlocksAndStreamsVisibleText() {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"content":[]}}

            data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"private"}}
            data: {"type":"content_block_start","content_block":{"type":"text","text":"你"}}
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"好"}}
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
            data: {"type":"message_stop"}
        """.trimIndent()
        withResponse(sse, onRequest = { headers, body ->
            assertEquals("POST /anthropic/v1/messages?tenant=a%2Fb HTTP/1.1", headers.first())
            assertTrue(headers.any { it.equals("x-api-key: local-test", true) })
            assertTrue(headers.any { it.equals("anthropic-version: 2023-06-01", true) })
            assertTrue(headers.none { it.startsWith("Authorization:", true) })
            assertTrue(body.getString("system").startsWith("测试人设"))
            val messages = body.getJSONArray("messages")
            assertEquals(1, messages.length())
            val blocks = messages.getJSONObject(0).getJSONArray("content")
            assertEquals("earlier", blocks.getJSONObject(0).getString("text"))
            assertEquals("look", blocks.getJSONObject(1).getString("text"))
            val image = blocks.getJSONObject(2)
            assertEquals("image", image.getString("type"))
            assertEquals("image/jpeg", image.getJSONObject("source").getString("media_type"))
            assertEquals("AQID", image.getJSONObject("source").getString("data"))
        }) { endpoint ->
            val chunks = mutableListOf<String>()
            val reply = ChatCompletionsClient().stream(
                endpoint.copy(format = ModelApiFormat.ANTHROPIC, url = "${endpoint.url}/anthropic?tenant=a%2Fb"),
                "local-test", "测试人设", listOf(
                    ChatLine(ChatRole.ASSISTANT, "proactive"),
                    ChatLine(ChatRole.USER, "earlier"),
                    ChatLine(ChatRole.USER, "look"),
                ), chunks::add, { false }, byteArrayOf(1, 2, 3),
            )
            assertEquals("你好", reply)
            assertEquals(listOf("你", "好"), chunks)
        }
    }

    @Test
    fun anthropicAcceptsJsonFallbackAndCanUseExplicitAuthorization() {
        val json = """{"type":"message","content":[{"type":"thinking","thinking":"private"},{"type":"text","text":"OK"}],"stop_reason":"end_turn"}"""
        withResponse(json, onRequest = { headers, body ->
            assertEquals("POST /v1/messages HTTP/1.1", headers.first())
            assertTrue(headers.any { it.equals("Authorization: Bearer explicit", true) })
            assertTrue(headers.none { it.startsWith("x-api-key:", true) })
            assertTrue(body.getBoolean("stream"))
        }) { endpoint ->
            val draft = endpoint.copy(format = ModelApiFormat.ANTHROPIC, extraHeaders = "Authorization: Bearer explicit")
            assertFalse(ChatCompletionsClient().test(draft, "local-test").outputLimitReached)
        }
    }

    @Test
    fun periodicObservationUsesTheSelectedFormatAndParsesStreamedScreenResults() {
        val text = """{"summary":"有一扇窗","remark":"窗外真亮。","emotion":"neutral"}"""
        val delta = JSONObject().put("type", "text_delta").put("text", text)
        val event = JSONObject().put("type", "content_block_delta").put("delta", delta)
        withResponse("data: $event\n\ndata: {\"type\":\"message_stop\"}\n", onRequest = { headers, body ->
            assertEquals("POST /v1/messages HTTP/1.1", headers.first())
            assertTrue(body.getBoolean("stream"))
            assertEquals(256, body.getInt("max_tokens"))
            assertTrue(body.getString("system").contains("定时看屏"))
            assertEquals("image", body.getJSONArray("messages").getJSONObject(0)
                .getJSONArray("content").getJSONObject(1).getString("type"))
        }) { endpoint ->
            val screen = ChatCompletionsClient().observeScreen(
                endpoint.copy(format = ModelApiFormat.ANTHROPIC, maxOutputTokens = 4096),
                "local-test", byteArrayOf(1, 2, 3), "", null, { true },
            )
            assertEquals("有一扇窗", screen?.summary)
            assertEquals("窗外真亮。", screen?.remark)
        }
    }

    @Test
    fun distinguishesWrongProtocolBillingAndRedirectsFromMissingText() {
        withResponse("""{"type":"message","content":[{"type":"text","text":"OK"}]}""") { endpoint ->
            val failure = assertThrows(ModelResponseException::class.java) {
                ChatCompletionsClient().test(endpoint, "local-test")
            }
            assertEquals(ModelResponseIssue.FORMAT_MISMATCH, failure.issue)
            val hint = ChatFailureText.forThrowable(failure)
            assertTrue(hint.contains("接口格式"))
            assertEquals(hint, ChatFailureText.forBubble(hint))
        }
        withResponse("""{"error":{"code":"insufficient_quota","message":"private"}}""", status = 429) { endpoint ->
            val failure = assertThrows(ChatHttpStatusException::class.java) {
                ChatCompletionsClient().test(endpoint, "local-test")
            }
            assertEquals(402, failure.status)
        }
        withResponse("", status = 307, extraResponseHeaders = "Location: /wrong-host\r\n") { endpoint ->
            val failure = assertThrows(ChatHttpStatusException::class.java) {
                ChatCompletionsClient().test(endpoint, "local-test")
            }
            assertEquals(307, failure.status)
            assertTrue(ChatFailureText.forStatus(failure.status).contains("跳转"))
        }
    }

    @Test
    fun detectsSuccessfulReasoningThatUsedTheProbeTokenBudget() {
        val json = """{"choices":[{"finish_reason":"length","message":{"content":null,"reasoning_content":"test reasoning"}}]}"""
        withResponse(json) { endpoint ->
            assertTrue(ChatCompletionsClient().test(endpoint, "local-test").outputLimitReached)
        }
    }

    @Test
    fun probeDisablesThinkingOnlyAtTheOfficialDeepSeekHost() {
        val client = ChatCompletionsClient()
        val endpoint = ModelEndpoint(
            ModelApiFormat.OPENAI, "https://api.deepseek.com",
            "deepseek-flash", null, 8192, "",
        )
        val official = client.connectionTestBody(endpoint)
        assertEquals("disabled", official.getJSONObject("thinking").getString("type"))
        assertEquals(endpoint.maxOutputTokens, official.getInt("max_tokens"))
        assertTrue(official.getBoolean("stream"))
        val proxy = client.connectionTestBody(endpoint.copy(url = "https://gateway.example.invalid/v1"))
        assertFalse(proxy.has("thinking"))
    }

    @Test
    fun petRequestsGetVisibleRepliesFromOfficialFlashWithoutChangingOtherModels() {
        val client = ChatCompletionsClient()
        val endpoint = ModelEndpoint(
            ModelApiFormat.OPENAI, "https://api.deepseek.com",
            "deepseek-flash", null, 512, "",
        )
        for (stream in listOf(false, true)) {
            val request = client.requestBody(endpoint, JSONArray(), stream)
            assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
            assertEquals(512, request.getInt("max_tokens"))
            assertEquals(stream, request.getBoolean("stream"))
        }
        assertFalse(client.requestBody(endpoint.copy(model = "deepseek-v4-pro"), JSONArray(), true).has("thinking"))
        assertFalse(
            client.requestBody(endpoint.copy(url = "https://gateway.example.invalid/v1"), JSONArray(), true)
                .has("thinking"),
        )
    }

    @Test
    fun streamingReadsMultipartSseEvenWhenTheGatewayLabelsItJson() {
        val response = """
            : connection opened

            data: {"choices":[
            data: {"delta":{"content":"晚安"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"not a reply"}}]}
            data: {"choices":[{"delta":{"content":"，蓝鳍。"}}]}

            data: [DONE]
        """.trimIndent()
        withResponse(response, onRequest = { headers, body ->
            assertEquals("POST /v1/chat/completions?tenant=a%2Fb HTTP/1.1", headers.first())
            assertTrue(headers.any { it.equals("Authorization: Bearer local-test", ignoreCase = true) })
            assertTrue(body.getBoolean("stream"))
            val messages = body.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertEquals("hello", messages.getJSONObject(1).getString("content"))
        }) { endpoint ->
            val chunks = mutableListOf<String>()
            val reply = ChatCompletionsClient().stream(
                endpoint.copy(url = "${endpoint.url}/v1?tenant=a%2Fb"),
                "local-test",
                "",
                listOf(ChatLine(ChatRole.ASSISTANT, "", stopped = true), ChatLine(ChatRole.USER, "hello")),
                chunks::add,
                { false },
            )
            assertEquals("晚安，蓝鳍。", reply)
            assertEquals(listOf("晚安", "，蓝鳍。"), chunks)
        }
    }

    @Test
    fun streamingAcceptsFullJsonFromAnOpenAiCompatibleGateway() {
        withResponse(
            """{"choices":[{"message":{"content":"完整回复。"}}]}""",
            contentType = "text/event-stream",
        ) { endpoint ->
            val chunks = mutableListOf<String>()
            assertEquals(
                "完整回复。",
                ChatCompletionsClient().stream(
                    endpoint, "local-test", "", listOf(ChatLine(ChatRole.USER, "hello")), chunks::add, { false },
                ),
            )
            assertEquals(listOf("完整回复。"), chunks)
        }
    }

    @Test
    fun gatewayBillingErrorInsideASuccessfulStreamKeepsItsCode() {
        withResponse("""data: {"error":{"code":402,"message":"private gateway details"}}""" + "\n\n") { endpoint ->
            val failure = assertThrows(ChatHttpStatusException::class.java) {
                ChatCompletionsClient().stream(
                    endpoint, "local-test", "", listOf(ChatLine(ChatRole.USER, "hello")), {}, { false },
                )
            }
            assertEquals(402, failure.status)
            assertFalse(failure.message.orEmpty().contains("private gateway details"))
        }
    }

    @Test
    fun billingDenialRemainsAnHttpErrorEvenIfItLooksLikeACompletion() {
        withResponse("""{"choices":[{"message":{"content":"OK"}}]}""", status = 402) { endpoint ->
            val failure = assertThrows(ChatHttpStatusException::class.java) {
                ChatCompletionsClient().test(endpoint, "local-test")
            }
            assertEquals(402, failure.status)
            val hint = ChatFailureText.forStatus(failure.status)
            assertTrue(hint.contains("余额"))
            assertEquals(hint, ChatFailureText.forBubble(hint))
        }
    }

    @Test
    fun rejectsEmptyHtmlAndIncompleteSuccessResponses() {
        val invalid = listOf(
            "",
            "<html>API portal</html>",
            """{"choices":[]}""",
            """{"choices":[{"message":{"content":" "}}]}""",
            """{"choices":[{"finish_reason":"length","message":{"content":null,"reasoning_content":null}}]}""",
        )
        for (body in invalid) {
            withResponse(body) { endpoint ->
                assertThrows(ModelResponseException::class.java) {
                    ChatCompletionsClient().test(endpoint, "local-test")
                }
            }
        }
    }

    @Test
    fun expiredCaptureIsRejectedBeforeConnecting() {
        val scope = ScreenCaptureScope()
        val permit = checkNotNull(scope.permit())
        val app = ModelEndpoint(ModelApiFormat.OPENAI, "http://127.0.0.1:1",
            "mock", null, 64, "")
        scope.stop()
        assertThrows(ScreenSendExpired::class.java) {
            ChatCompletionsClient().stream(
                app, "local-test", "", listOf(ChatLine(ChatRole.USER, "look")), {}, { false },
                canSendScreen = { scope.allows(permit) },
            )
        }
    }

    private fun withResponse(
        body: String,
        status: Int = 200,
        contentType: String = "application/json",
        extraResponseHeaders: String = "",
        onRequest: (List<String>, JSONObject) -> Unit = { _, _ -> },
        check: (ModelEndpoint) -> Unit,
    ) {
        ServerSocket().use { server ->
            server.soTimeout = 5_000
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            val response = FutureTask {
                server.accept().use { client ->
                    client.soTimeout = 5_000
                    val request = client.getInputStream().buffered()
                    val headers = generateSequence { request.readHeaderLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val length = headers.first { it.startsWith("Content-Length:", ignoreCase = true) }
                        .substringAfter(':').trim().toInt()
                    val sent = request.readNBytes(length)
                    check(sent.size == length)
                    onRequest(headers, JSONObject(sent.toString(Charsets.UTF_8)))
                    val output = client.getOutputStream()
                    val bytes = body.toByteArray()
                    output.write(
                        ("HTTP/1.1 $status Test\r\nContent-Type: $contentType\r\n" +
                            "Content-Length: ${bytes.size}\r\n${extraResponseHeaders}Connection: close\r\n\r\n").toByteArray(),
                    )
                    output.write(bytes)
                }
            }
            Thread(response, "mock-chat-completions").apply { isDaemon = true; start() }
            check(ModelEndpoint(ModelApiFormat.OPENAI, "http://127.0.0.1:${server.localPort}",
                "mock", null, 64, ""))
            response.get()
        }
    }

    private fun BufferedInputStream.readHeaderLine(): String = buildString {
        while (true) {
            val byte = read()
            check(byte >= 0)
            if (byte == '\n'.code) break
            if (byte != '\r'.code) append(byte.toChar())
        }
    }
}
