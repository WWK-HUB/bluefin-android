package com.wwkhub.bluefin.update

import com.wwkhub.bluefin.JsonParseException
import com.wwkhub.bluefin.MiniJson

/**
 * The update manifest both sources serve. One schema, two hosts: the domestic
 * primary and the GitHub fallback must stay interchangeable, so nothing here
 * knows about either host.
 */
internal data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long?,
    val notes: String?,
) {
    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** Returns null for anything unusable; callers fall back to the next source. */
        fun parse(raw: String): UpdateManifest? {
            val obj = try {
                MiniJson.parse(raw).asObject()
            } catch (_: JsonParseException) {
                null
            } ?: return null
            val code = obj.field("versionCode")?.intOrNull() ?: return null
            if (code <= 0) return null
            val name = obj.field("versionName")?.asString()?.trim().orEmpty()
            if (name.isEmpty()) return null
            val url = obj.field("apkUrl")?.asString()?.trim().orEmpty()
            if (url.isEmpty()) return null
            val sha = obj.field("sha256")?.asString()?.trim()?.lowercase().orEmpty()
            if (!SHA256.matches(sha)) return null
            val size = obj.field("sizeBytes")?.let { value ->
                val number = value.asDouble() ?: return null
                if (number < 1 || number > UpdatePolicy.MAX_APK_BYTES || number != number.toLong().toDouble()) {
                    return null
                }
                number.toLong()
            }
            val notes = obj.field("notes")?.asString()?.trim()?.takeIf { it.isNotEmpty() }
            return UpdateManifest(code, name, url, sha, size, notes)
        }
    }
}