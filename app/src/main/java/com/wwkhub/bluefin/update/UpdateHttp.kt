package com.wwkhub.bluefin.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** The manifest and the APK use the same redirect and connection rules. */
internal object UpdateHttp {
    fun open(url: String, allowInsecureLan: Boolean): HttpURLConnection {
        val validated = UpdateUrlPolicy.check(url, allowInsecureLan)
        if (validated !is UpdateUrlPolicy.Check.Valid) throw IOException("更新地址不受信任")
        val original = URI(validated.url)
        var current = original
        repeat(6) {
            val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
            }
            var handOff = false
            try {
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) {
                    handOff = true
                    return connection
                }
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("更新源未提供跳转地址")
                val next = try {
                    current.resolve(location)
                } catch (error: IllegalArgumentException) {
                    throw IOException("更新源跳转地址无效", error)
                }
                if (!UpdateUrlPolicy.allowsRedirect(original, next, allowInsecureLan)) {
                    throw IOException("更新源跳转目标不受信任")
                }
                current = next
            } finally {
                if (!handOff) connection.disconnect()
            }
        }
        throw IOException("更新源跳转次数过多")
    }
}
