package com.wwkhub.bluefin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the 0.4 overlay-open path: opening the overlay must stay permission
 * only and must not synchronously touch chat, keys, or persona storage.
 */
class ChatAccessGateTest {
    private fun source(path: String): String = File(path).readText()

    private fun body(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        assertTrue("missing $startMarker", start >= 0)
        val end = text.indexOf(endMarker, start + startMarker.length)
        assertTrue("missing $endMarker", end > start)
        return text.substring(start, end)
    }

    @Test
    fun overlayWindowShowKeepsTheProvenZeroFourOrder() {
        val show = body(
            source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt"),
            "fun show() {",
            "fun close() {",
        )
        val expected = listOf(
            "if (attached || destroyed) return",
            "applyStageSize()",
            "registerDisplayCallbacks()",
            "windowManager.addView(root, params)",
            "attached = true",
            "layoutOrb()",
            "loadStage(",
        )
        var cursor = -1
        expected.forEach { line ->
            val at = show.indexOf(line, cursor + 1)
            assertTrue("$line must appear in order", at > cursor)
            cursor = at
        }
        assertFalse(show.contains("ChatSession"))
        assertFalse(show.contains("ApiKeyVault"))
        assertFalse(show.contains("EncryptedSharedPreferences"))
        assertFalse(show.contains("PersonaRepository"))
        assertFalse(show.contains("ChatCompletionsClient"))
    }

    @Test
    fun overlayLauncherShowWindowOnlyNeedsTheOverlayPermission() {
        val showWindow = body(
            source("src/main/java/com/wwkhub/bluefin/OverlayLauncher.kt"),
            "private fun showWindow(",
            "fun hide(",
        )
        assertTrue(showWindow.contains("Settings.canDrawOverlays(app)"))
        assertFalse(showWindow.contains("ChatSession"))
        assertFalse(showWindow.contains("ApiKeyVault"))
        assertFalse(showWindow.contains("EncryptedSharedPreferences"))
        assertFalse(showWindow.contains("PersonaRepository"))
        assertFalse(showWindow.contains("ChatCompletionsClient"))
        assertFalse(showWindow.contains("ModelEndpointStore"))
    }

    @Test
    fun activityCreationDoesNotEagerlyOpenChatOrSecrets() {
        val onCreate = body(
            source("src/main/java/com/wwkhub/bluefin/MainActivity.kt"),
            "override fun onCreate(",
            "override fun onNewIntent(",
        )
        assertFalse(onCreate.contains("ChatSession"))
        assertFalse(onCreate.contains("ApiKeyVault"))
        assertFalse(onCreate.contains("EncryptedSharedPreferences"))
        assertFalse(onCreate.contains("PersonaRepository"))
    }

    @Test
    fun unconfiguredChatEntryIsAUserActionNotAnOverlayGate() {
        val entry = source("src/main/java/com/wwkhub/bluefin/ChatEntry.kt")
        assertTrue(entry.contains("还没接通模型，要去设置里填写吗？"))
        assertTrue(entry.contains("\"去设置\""))
        assertTrue(entry.contains("\"取消\""))
        val launcher = source("src/main/java/com/wwkhub/bluefin/OverlayLauncher.kt")
        assertFalse(launcher.contains("ChatEntry"))
    }
}
