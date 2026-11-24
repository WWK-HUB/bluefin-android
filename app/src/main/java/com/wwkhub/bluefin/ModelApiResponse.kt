package com.wwkhub.bluefin

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.CancellationException

internal enum class ModelResponseIssue {
    INVALID, FORMAT_MISMATCH, EMPTY, OUTPUT_LIMIT, FILTERED,
}

internal class ModelResponseException(val issue: ModelResponseIssue) : Exception()

internal data class ModelReply(val text: String, val outputLimitReached: Boolean) {
    fun requireText(): String {
        if (text.isBlank()) throw ModelResponseException(ModelResponseIssue.OUTPUT_LIMIT)
        return text
    }
}

/** Both adapters accept a full JSON reply and SSE, regardless of the gateway's Content-Type. */
internal object ModelApiResponse {
    fun read(
        input: InputStream,
        format: ModelApiFormat,
        onDelta: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): ModelReply {
        val reply = ReplyReader(format, onDelta)
        val plain = StringBuilder()
        val pending = StringBuilder()
        var sse = false
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            var firstLine = true
            while (true) {
                if (isCancelled()) throw CancellationException("stopped")
                val raw = reader.readLine() ?: break
                val line = if (firstLine) raw.removePrefix("\uFEFF") else raw
                firstLine = false
                when {
                    line.startsWith("data:") || line == "data" -> {
                        sse = true
                        val data = line.substringAfter(':', "").removePrefix(" ")
                        if (data.trim() == "[DONE]") break
                        if (pending.isNotEmpty()) pending.append('\n')
                        pending.append(data)
                        // Gateways sometimes omit blank separators between complete objects.
                        val json = parseModelJson(pending.toString()) ?: continue
                        pending.setLength(0)
                        if (reply.receive(json)) break
                    }
                    line.isEmpty() -> pending.setLength(0)
                    !sse -> plain.appendLine(line)
                }
            }
        }
        if (isCancelled()) throw CancellationException("stopped")
        if (!sse) parseModelJson(plain.toString())?.let(reply::receive)
        return reply.result()
    }

    private class ReplyReader(val format: ModelApiFormat, val onDelta: (String) -> Unit) {
        private val text = StringBuilder()
        private var recognized = false
        private var reasoning = false
        private var stop = ""

        fun receive(json: JSONObject): Boolean {
            modelServiceStatus(json)?.let { throw ChatHttpStatusException(it) }
            val type = json.optString("type")
            if (json.optJSONObject("error") != null || type == "error") throw ChatServiceException()
            val choices = json.optJSONArray("choices")
            val anthropic = type in ANTHROPIC_EVENTS
            if (format == ModelApiFormat.OPENAI) {
                if (choices == null && (anthropic || type.startsWith("response.") || json.optString("object") == "response")) {
                    throw ModelResponseException(ModelResponseIssue.FORMAT_MISMATCH)
                }
                choices?.optJSONObject(0)?.let { readChoice(it, json.optJSONObject("usage")) }
            } else {
                if (!anthropic && (choices != null || type.startsWith("response.") || json.optString("object") == "response")) {
                    throw ModelResponseException(ModelResponseIssue.FORMAT_MISMATCH)
                }
                readMessage(json, type)
            }
            return format == ModelApiFormat.ANTHROPIC && type == "message_stop"
        }

        private fun readChoice(choice: JSONObject, usage: JSONObject?) {
            recognized = true
            val message = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
            val content = message?.opt("content")?.takeUnless { it === JSONObject.NULL } ?: choice.opt("text")
            emit(when (content) {
                is String -> content
                is JSONArray -> content.textBlocks()
                else -> ""
            })
            if ((message?.opt("reasoning_content") as? String)?.isNotBlank() == true ||
                (usage?.optInt("completion_tokens", 0) ?: 0) > 0
            ) reasoning = true
            recordStop(choice, "finish_reason")
            if ((message?.opt("refusal") as? String)?.isNotBlank() == true) stop = "refusal"
        }

        private fun readMessage(json: JSONObject, type: String) {
            if (type in ANTHROPIC_EVENTS) recognized = true
            when (type) {
                "message" -> {
                    json.optJSONArray("content")?.let { emit(it.textBlocks()) }
                    recordStop(json, "stop_reason")
                }
                "content_block_start" -> json.optJSONObject("content_block")?.let {
                    if (it.optString("type") == "text") emit(it.textValue())
                }
                "content_block_delta" -> json.optJSONObject("delta")?.let {
                    if (it.optString("type") == "text_delta") emit(it.textValue())
                }
                "message_delta" -> json.optJSONObject("delta")?.let { recordStop(it, "stop_reason") }
            }
        }

        private fun recordStop(json: JSONObject, name: String) {
            (json.opt(name) as? String)?.takeIf { it.isNotEmpty() }?.let { stop = it }
        }

        private fun emit(delta: String) {
            if (delta.isEmpty()) return
            text.append(delta)
            onDelta(delta)
        }

        fun result(): ModelReply {
            if (text.isNotBlank()) return ModelReply(text.toString(), false)
            if (stop == "max_tokens" || stop == "length" && reasoning) return ModelReply("", true)
            val issue = when {
                stop == "content_filter" || stop == "refusal" -> ModelResponseIssue.FILTERED
                recognized -> ModelResponseIssue.EMPTY
                else -> ModelResponseIssue.INVALID
            }
            throw ModelResponseException(issue)
        }
    }

    private val ANTHROPIC_EVENTS = setOf(
        "message", "message_start", "message_delta", "message_stop",
        "content_block_start", "content_block_delta", "content_block_stop",
    )

    private fun JSONObject.textValue(): String = (opt("text") as? String).orEmpty()

    private fun JSONArray.textBlocks(): String = buildString {
        for (index in 0 until length()) {
            val block = optJSONObject(index) ?: continue
            if (block.optString("type") in setOf("", "text")) append(block.textValue())
        }
    }
}

internal fun parseModelJson(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()

internal fun modelServiceStatus(json: JSONObject?): Int? {
    val error = json?.optJSONObject("error") ?: return null
    error.optString("code").toIntOrNull()?.takeIf { it in 400..599 }?.let { return it }
    for (name in listOf("code", "type")) {
        when (error.optString(name)) {
            "invalid_api_key", "authentication_error" -> return 401
            "billing_error", "billing_hard_limit_reached", "insufficient_quota" -> return 402
            "permission_error" -> return 403
            "model_not_found", "not_found_error" -> return 404
            "request_too_large" -> return 413
            "rate_limit_error", "rate_limit_exceeded" -> return 429
            "overloaded_error" -> return 529
            "api_error", "server_error" -> return 500
            "invalid_request_error" -> return 400
        }
    }
    return null
}
