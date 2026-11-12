package com.wwkhub.bluefin

import java.net.URI

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
     * Resolves the Chat Completions URL, appending the path only when the base
     * address does not already end with it.
     */
    fun completionsUrl(raw: String): String {
        val base = raw.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.lowercase().endsWith(COMPLETIONS_SUFFIX)) base else "$base$COMPLETIONS_SUFFIX"
    }

    fun isConfigured(url: String, model: String, hasKey: Boolean): Boolean =
        url.isNotBlank() && model.isNotBlank() && hasKey && check(url) is Check.Valid
}
