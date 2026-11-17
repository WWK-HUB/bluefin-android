package com.wwkhub.bluefin.update

import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlPolicyTest {
    private fun valid(url: String, lan: Boolean = false) =
        UpdateUrlPolicy.check(url, lan) is UpdateUrlPolicy.Check.Valid

    private fun invalid(url: String, lan: Boolean = false) =
        UpdateUrlPolicy.check(url, lan) is UpdateUrlPolicy.Check.Invalid

    @Test
    fun httpsIsAcceptedForAnyHost() {
        assertTrue(valid("https://update.example.cn/manifest.json"))
        assertTrue(valid("https://github.com/WWK-HUB/bluefin-android/releases/latest/download/update-manifest.json"))
        assertTrue(valid("https://10.0.2.2:8765/manifest.json"))
    }

    @Test
    fun plainHttpIsRejectedOutsideDebugLanTesting() {
        assertTrue(invalid("http://update.example.cn/manifest.json"))
        assertTrue(invalid("http://10.0.2.2:8765/manifest.json"))
        assertTrue(invalid("http://127.0.0.1:8765/manifest.json"))
    }

    @Test
    fun debugLanTestingAllowsOnlyPrivateHttp() {
        assertTrue(valid("http://10.0.2.2:8765/manifest.json", lan = true))
        assertTrue(valid("http://127.0.0.1:8765/manifest.json", lan = true))
        assertTrue(valid("http://192.168.1.5:8765/manifest.json", lan = true))
        assertTrue(invalid("http://update.example.cn/manifest.json", lan = true))
        assertTrue(invalid("http://8.8.8.8/manifest.json", lan = true))
    }

    @Test
    fun malformedAndForeignSchemesAreRejected() {
        assertTrue(invalid(""))
        assertTrue(invalid("   "))
        assertTrue(invalid("ftp://host/manifest.json"))
        assertTrue(invalid("file:///sdcard/manifest.json"))
        assertTrue(invalid("https://"))
        assertTrue(invalid("not a url"))
    }
}