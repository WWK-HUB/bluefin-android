package com.wwkhub.bluefin.voice.tts

import android.content.Context
import com.wwkhub.bluefin.BluefinStore
import com.wwkhub.bluefin.BuildConfig
import com.wwkhub.bluefin.EndpointAddressPolicy

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
        return when (val check = EndpointAddressPolicy.check(raw)) {
            is EndpointAddressPolicy.Check.Valid -> check.normalized
            is EndpointAddressPolicy.Check.Invalid -> ""
        }
    }

    fun isConfigured(context: Context): Boolean = baseUrl(context).isNotEmpty()

    /** Validates a candidate override; returns the normalized URL or null. */
    fun normalizeOverride(raw: String): String? =
        when (val check = EndpointAddressPolicy.check(raw)) {
            is EndpointAddressPolicy.Check.Valid -> check.normalized
            is EndpointAddressPolicy.Check.Invalid -> null
        }

    fun url(context: Context, path: String): String = baseUrl(context) + path
}