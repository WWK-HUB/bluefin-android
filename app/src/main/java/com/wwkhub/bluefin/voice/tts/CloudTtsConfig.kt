package com.wwkhub.bluefin.voice.tts

import android.content.Context
import com.wwkhub.bluefin.BluefinStore
import com.wwkhub.bluefin.BuildConfig
import com.wwkhub.bluefin.EndpointAddressPolicy
import java.net.URI
import java.util.Locale

/**
 * Where the cloud TTS worker lives. The address is ordinary configuration —
 * never a credential. Resolution order: user override in prefs (lets mainland
 * users switch to a custom domain when workers.dev is unreachable), then the
 * value baked at build time via -PcloudTtsBase.
 *
 * The same address policy as chat applies: https anywhere, plain http only
 * for localhost/LAN (emulator verification against a local worker).
 */
internal object CloudTtsConfig {
    fun baseUrl(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
        val override = BluefinStore.readCloudTtsBase(prefs)
        val raw = override ?: BuildConfig.CLOUD_TTS_BASE_URL
        return normalizeOverride(raw).orEmpty()
    }

    fun isConfigured(context: Context): Boolean = baseUrl(context).isNotEmpty()

    /** Validates a candidate override; returns the normalized URL or null. */
    fun normalizeOverride(raw: String): String? {
        val checked = EndpointAddressPolicy.check(raw) as? EndpointAddressPolicy.Check.Valid ?: return null
        val uri = URI(checked.normalized)
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null
        val defaultPort = if (scheme == "https") 443 else 80
        val authority = if (port == -1 || port == defaultPort) host else "$host:$port"
        return "$scheme://$authority${uri.normalize().rawPath.trimEnd('/')}"
    }
}