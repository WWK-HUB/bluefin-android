package com.wwkhub.bluefin

import java.net.URI

internal class ChatAddressException(val hint: String) : Exception(hint)

/**
 * Pure address rules for the single user-configured endpoint.
 *
 * HTTPS is accepted for any host. Plain HTTP is accepted only for localhost and
 * private LAN addresses; remote HTTP is rejected before any request is made.
 */
internal object EndpointAddressPolicy {
    sealed class Check {
        data class Valid(val normalized: String) : Check()
        data class Invalid(val reason: String) : Check()
    }

    private const val COMPLETIONS_SUFFIX = "/chat/completions"
    const val CHAT_PROTOCOL_HINT = "请填写 OpenAI 兼容的 Chat Completions API 地址，或切换接口格式。"
    const val MESSAGES_PROTOCOL_HINT = "请填写 Anthropic 兼容的 Messages API 地址，或切换接口格式。"
    const val CHAT_FRAGMENT_HINT = "模型 API 地址不能包含网页锚点（#）。"
    const val URL_CREDENTIALS_HINT = "请把密钥填在 API Key 栏。API 地址不能包含用户名或密码。"
    const val LEGACY_CHAT_PROTOCOL_HINT = "请填写 OpenAI 兼容的 Chat Completions API 地址。"

    fun check(raw: String): Check {
        val text = raw.trim()
        if (text.isEmpty()) return Check.Invalid("请填写 API 地址。")
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return Check.Invalid("API 地址格式不正确。")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Check.Invalid("API 地址必须以 http 或 https 开头。")
        }
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return Check.Invalid("API 地址缺少主机名。")
        if (scheme == "http" && !isPrivateHost(host)) {
            return Check.Invalid("远程地址只允许 https；http 仅用于本机或局域网。")
        }
        return Check.Valid(text.trimEnd('/'))
    }

    fun isPrivateHost(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]").lowercase()
        if (normalized == "localhost" || normalized == "::1") return true
        val parts = normalized.split('.')
        if (parts.size != 4) return false
        val numbers = parts.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        if (numbers[0] == 127) return true
        if (numbers[0] == 10) return true
        if (numbers[0] == 172 && numbers[1] in 16..31) return true
        if (numbers[0] == 192 && numbers[1] == 168) return true
        return false
    }

    /**
     * Resolves the request URL for the selected protocol while retaining the raw query.
     */
    fun checkChatAddress(raw: String, format: ModelApiFormat = ModelApiFormat.OPENAI): Check {
        val checked = check(raw)
        if (checked is Check.Invalid) return checked
        val uri = URI(raw.trim())
        if (uri.rawFragment != null) return Check.Invalid(CHAT_FRAGMENT_HINT)
        if (uri.rawUserInfo != null) return Check.Invalid(URL_CREDENTIALS_HINT)
        val basePath = uri.rawPath.trimEnd('/')
        val lowerPath = basePath.lowercase()
        val path = when (format) {
            ModelApiFormat.OPENAI -> {
                if (lowerPath.endsWith("/messages") || lowerPath.endsWith("/messages/count_tokens") ||
                    lowerPath.endsWith("/responses") || isDeepSeekAnthropicAddress(uri, lowerPath) ||
                    uri.host.equals("api.anthropic.com", ignoreCase = true)
                ) return Check.Invalid(CHAT_PROTOCOL_HINT)
                when {
                    lowerPath.endsWith(COMPLETIONS_SUFFIX) -> basePath
                    basePath.isEmpty() && uri.host.equals("api.openai.com", ignoreCase = true) -> "/v1$COMPLETIONS_SUFFIX"
                    else -> "$basePath$COMPLETIONS_SUFFIX"
                }
            }
            ModelApiFormat.ANTHROPIC -> {
                if (lowerPath.endsWith(COMPLETIONS_SUFFIX) || lowerPath.endsWith("/responses") ||
                    lowerPath.endsWith("/messages/count_tokens") || uri.host.equals("api.openai.com", ignoreCase = true)
                ) return Check.Invalid(MESSAGES_PROTOCOL_HINT)
                when {
                    lowerPath.endsWith("/messages") -> basePath
                    lowerPath.endsWith("/v1") -> "$basePath/messages"
                    else -> "$basePath/v1/messages"
                }
            }
        }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return Check.Valid("${uri.scheme}://${uri.rawAuthority}$path$query")
    }

    fun completionsUrl(raw: String, format: ModelApiFormat = ModelApiFormat.OPENAI): String =
        when (val checked = checkChatAddress(raw, format)) {
        is Check.Valid -> checked.normalized
        is Check.Invalid -> throw ChatAddressException(checked.reason)
    }

    private fun isDeepSeekAnthropicAddress(uri: URI, lowerPath: String): Boolean =
        uri.host.equals("api.deepseek.com", ignoreCase = true) && lowerPath in setOf("/anthropic", "/anthropic/v1")

    fun sameOrigin(first: String, second: String): Boolean {
        val original = origin(first) ?: return false
        return original == origin(second)
    }

    fun origin(raw: String): String? {
        if (check(raw) !is Check.Valid) return null
        val uri = URI(raw.trim())
        val scheme = uri.scheme.lowercase()
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        return "$scheme://${uri.host.lowercase()}:$port"
    }

    fun isConfigured(
        url: String,
        model: String,
        hasKey: Boolean,
        format: ModelApiFormat = ModelApiFormat.OPENAI,
    ): Boolean = model.isNotBlank() && hasKey && checkChatAddress(url, format) is Check.Valid
}
