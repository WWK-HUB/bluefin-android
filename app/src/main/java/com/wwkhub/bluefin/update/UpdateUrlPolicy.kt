package com.wwkhub.bluefin.update

import com.wwkhub.bluefin.EndpointAddressPolicy
import java.net.URI

/**
 * Address rules for update sources. Distribution is HTTPS-only; plain HTTP is
 * accepted solely for loopback/LAN hosts in debug test builds, mirroring the
 * model-endpoint policy, so emulator verification can run a local test source.
 */
internal object UpdateUrlPolicy {
    sealed class Check {
        data class Valid(val url: String) : Check()
        data class Invalid(val reason: String) : Check()
    }

    fun allowsRedirect(original: URI, target: URI, allowInsecureLan: Boolean): Boolean {
        if (check(target.toString(), allowInsecureLan) !is Check.Valid) return false
        if (original.scheme.equals("https", true) && !target.scheme.equals("https", true)) return false
        if (original.host.equals(target.host, true) && original.toURL().let {
                (if (it.port == -1) it.defaultPort else it.port)
            } == target.toURL().let { if (it.port == -1) it.defaultPort else it.port }
        ) return true
        return original.host.equals("github.com", true) &&
            target.scheme.equals("https", true) && target.port in listOf(-1, 443) &&
            target.host.lowercase() in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }

    fun check(raw: String, allowInsecureLan: Boolean): Check {
        val text = raw.trim()
        if (text.isEmpty()) return Check.Invalid("更新源地址为空。")
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return Check.Invalid("更新源地址格式不正确。")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (uri.rawUserInfo != null || uri.rawFragment != null) return Check.Invalid("更新地址格式不正确。")
        if (host.isNullOrBlank()) return Check.Invalid("更新源地址缺少主机名。")
        if (scheme == "https") return Check.Valid(text)
        if (scheme == "http" && allowInsecureLan && EndpointAddressPolicy.isPrivateHost(host)) {
            return Check.Valid(text)
        }
        return Check.Invalid("更新源只允许 https 地址。")
    }
}