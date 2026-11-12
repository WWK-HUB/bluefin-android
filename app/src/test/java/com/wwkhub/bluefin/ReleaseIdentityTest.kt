package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseIdentityTest {
    @Test
    fun displayNameIsExactlyTheTwoApprovedCodePoints() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val line = strings.lineSequence().first { it.contains("name=\"app_name\"") }
        val name = Regex(">([^<]+)<").find(line)?.groupValues?.get(1).orEmpty()
        assertEquals("蓝鳍", name)
        assertEquals(2, name.length)
        assertEquals(0x84DD, name[0].code)
        assertEquals(0x9CCD, name[1].code)
        listOf("蓝鲗", "蓝鳐", "鳐", "鱚", "鳞", "蓝鳞").forEach { forbidden ->
            assertFalse("strings.xml must not contain $forbidden", strings.contains(forbidden))
        }
    }

    @Test
    fun versionAndPackageMatchTheRelease() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionName = \"0.8.0-rc1\""))
        assertTrue(gradle.contains("versionCode = 23"))
        assertFalse(gradle.contains("0.6.0-rc3"))
        assertFalse(gradle.contains("versionCode = 21"))
        assertTrue(gradle.contains("applicationId = \"com.wwkhub.bluefin\""))
        assertTrue(gradle.contains("namespace = \"com.wwkhub.bluefin\""))
    }

    @Test
    fun internetIsARealPermissionAndOverlayPermissionRemains() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("android.permission.INTERNET\" tools:node=\"remove\""))
        assertTrue(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW\" tools:node=\"remove\""))
    }

    @Test
    fun aboutNoLongerClaimsThereIsNoNetworkOrNoChat() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertFalse(strings.contains("不申请网络权限"))
        assertFalse(strings.contains("不含 AI 对话"))
        assertTrue(strings.contains("文字对话"))
        assertTrue(strings.contains("API Key"))
        assertTrue(strings.contains("角色外观包"))
        assertFalse(strings.contains("不含多角色"))
        assertTrue(strings.contains("不含语音、电脑控制、内置官方密钥"))
    }
}
