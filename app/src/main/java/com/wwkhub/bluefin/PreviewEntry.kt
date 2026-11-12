package com.wwkhub.bluefin

import java.net.URLEncoder

object PreviewEntry {
    const val HOST = "https://appassets.androidplatform.net"
    const val ROUTE_B = "bluefin/index.html"
    const val ROUTE_A = "www/bluefin-preview.html"

    /** Host page for a user-imported Coopanion figure pack. */
    const val PACK_HOST = "native/pack-host.html"

    /**
     * One-line entry switch, relative to `src/main/assets/`.
     * null uses [ROUTE_B] when `bluefin/index.html` is packaged, otherwise [ROUTE_A]
     * for a local self-test. Delivery builds keep this null and ship Route B.
     */
    val ENTRY_ASSET: String? = null

    fun choose(hasRouteB: Boolean, hasRouteA: Boolean, forced: String? = ENTRY_ASSET): String {
        if (!forced.isNullOrBlank()) return forced
        if (hasRouteB) return ROUTE_B
        if (hasRouteA) return ROUTE_A
        return ROUTE_B
    }

    fun urlFor(assetPath: String): String = "$HOST/assets/$assetPath"

    /**
     * URL for the imported-pack host. The default built-in character never uses
     * this helper, so [ROUTE_B] stays the default appearance.
     */
    fun packHostUrl(id: String, scheme: String? = null): String {
        val base = urlFor(PACK_HOST) + "?pack=" + encodeQuery(id)
        val trimmed = scheme?.trim().orEmpty()
        return if (trimmed.isEmpty()) base else "$base&scheme=" + encodeQuery(trimmed)
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    fun isSelfTest(assetPath: String): Boolean = assetPath == ROUTE_A
}
