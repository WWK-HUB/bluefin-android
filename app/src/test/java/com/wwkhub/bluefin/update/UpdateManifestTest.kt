package com.wwkhub.bluefin.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestTest {
    private val sha = "a".repeat(64)

    private fun manifest(extra: String = "", shaValue: String = sha, code: Int = 28): String = """
        {
          "versionCode": $code,
          "versionName": "0.10.0-rc2",
          "apkUrl": "https://update.example.cn/bluefin-28.apk",
          "sha256": "$shaValue",
          "sizeBytes": 12345678,
          "notes": "修复动作清单"
          $extra
        }
    """.trimIndent()

    @Test
    fun parsesEveryField() {
        val parsed = UpdateManifest.parse(manifest())!!
        assertEquals(28, parsed.versionCode)
        assertEquals("0.10.0-rc2", parsed.versionName)
        assertEquals("https://update.example.cn/bluefin-28.apk", parsed.apkUrl)
        assertEquals(sha, parsed.sha256)
        assertEquals(12345678L, parsed.sizeBytes)
        assertEquals("修复动作清单", parsed.notes)
    }

    @Test
    fun sizeAndNotesAreOptional() {
        val raw = """
            {"versionCode": 28, "versionName": "x", "apkUrl": "https://h/a.apk", "sha256": "$sha"}
        """.trimIndent()
        val parsed = UpdateManifest.parse(raw)!!
        assertNull(parsed.sizeBytes)
        assertNull(parsed.notes)
    }

    @Test
    fun uppercaseShaIsNormalized() {
        val parsed = UpdateManifest.parse(manifest(shaValue = sha.uppercase()))!!
        assertEquals(sha, parsed.sha256)
    }

    @Test
    fun rejectsUnusableManifests() {
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse("{}"))
        assertNull(UpdateManifest.parse(manifest(code = 0)))
        assertNull(UpdateManifest.parse(manifest(code = -3)))
        assertNull(UpdateManifest.parse(manifest(shaValue = "abc")))
        assertNull(UpdateManifest.parse(manifest(shaValue = "g".repeat(64))))
        assertNull(UpdateManifest.parse(manifest().replace("\"sizeBytes\": 12345678", "\"sizeBytes\": 0")))
        assertNull(UpdateManifest.parse(manifest().replace("\"versionName\": \"0.10.0-rc2\"", "\"versionName\": \"\"")))
        assertNull(UpdateManifest.parse(manifest().replace("https://update.example.cn/bluefin-28.apk", "")))
    }
}