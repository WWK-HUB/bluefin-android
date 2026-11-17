package com.wwkhub.bluefin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverlayClickThroughTest {
    private fun source(path: String): String = File(path).readText()

    private fun body(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        assertTrue("missing $startMarker", start >= 0)
        val end = text.indexOf(endMarker, start + startMarker.length)
        assertTrue("missing $endMarker after $startMarker", end > start)
        return text.substring(start, end)
    }

    @Test
    fun clickThroughAlwaysDropsThePetHitWindow() {
        assertFalse(OverlayClickThroughPolicy.shouldAttachHit(clickThrough = true, hasHits = true))
        assertFalse(OverlayClickThroughPolicy.shouldAttachHit(clickThrough = true, hasHits = false))
    }

    @Test
    fun interactivePetAttachesOnlyWhenHitsExist() {
        assertTrue(OverlayClickThroughPolicy.shouldAttachHit(clickThrough = false, hasHits = true))
        assertFalse(OverlayClickThroughPolicy.shouldAttachHit(clickThrough = false, hasHits = false))
    }

    @Test
    fun placeHitDropsBeforeItEverAddsThePetWindow() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val placeHit = body(window, "private fun placeHit(", "private fun dropHitWindow(")
        assertTrue(placeHit.contains("OverlayClickThroughPolicy.shouldAttachHit(clickThrough, hasHits)"))
        val dropAt = placeHit.indexOf("dropHitWindow()")
        val addAt = placeHit.indexOf("windowManager.addView(hit, hitParams)")
        assertTrue(dropAt >= 0)
        assertTrue(addAt > dropAt)
    }

    @Test
    fun toggleOnlyPersistsAndAppliesTheClickThroughSetting() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val listener = body(
            window,
            "R.id.overlay_click_through_switch,\n        ).setOnCheckedChangeListener",
            "orbPanel.findViewById<View>(R.id.overlay_hide_button)",
        )
        assertTrue(listener.contains("if (orbSyncing) return@setOnCheckedChangeListener"))
        assertTrue(listener.contains("BluefinStore.writeClickThrough(prefs(), checked)"))
        assertTrue(window.contains("BluefinStore.KEY_CLICK_THROUGH -> applyClickThroughSetting()"))
        assertFalse(listener.contains("OverlayLauncher"))
        assertFalse(listener.contains("requestPermissions"))
        assertFalse(listener.contains("canDrawOverlays"))
        assertFalse(listener.contains("show()"))
        assertFalse(listener.contains("addView"))
        assertFalse(listener.contains("removeView"))
    }

    @Test
    fun applyingClickThroughTouchesOnlyThePetWindow() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val apply = body(
            window,
            "private fun applyClickThroughSetting() {",
            "private fun cheapPackKey()",
        )
        assertTrue(apply.contains("BluefinStore.readClickThrough(prefs())"))
        assertTrue(apply.contains("dropHitWindow()"))
        assertTrue(apply.contains("window.__bluefinOverlay.publish()"))
        assertFalse(apply.contains("windowManager"))
        assertFalse(apply.contains("orbRoot"))
        assertFalse(apply.contains("orbPanel"))
        assertFalse(apply.contains("chatPanel"))
        assertFalse(apply.contains("OverlayLauncher"))
        assertFalse(apply.contains("requestPermissions"))
        assertFalse(apply.contains("canDrawOverlays"))
    }

    @Test
    fun stageStaysVisualOnlyAndOrbAndChatStayTouchable() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val init = body(window, "init {", "fun show() {")
        assertTrue(init.contains("clickThrough = BluefinStore.readClickThrough(prefs())"))
        assertTrue(init.contains("touchable = false"))
        assertTrue(init.contains("title = OverlayLayout.WINDOW_TITLE_STAGE"))
        assertTrue(init.contains("orbParams = fullscreenParams(touchable = true"))
        val setupOrb = body(window, "private fun setupOrb() {", "private fun addOrbPanelListeners()")
        assertTrue(setupOrb.contains("orbPanel.isClickable = true"))
        assertTrue(setupOrb.contains("chatPanel.isClickable = true"))
        val fullscreenParams = body(window, "private fun fullscreenParams(", "private fun applyStageSize()")
        assertTrue(
            fullscreenParams.contains(
                "OverlayLayout.WINDOW_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE",
            ),
        )

        val orb = source("src/main/res/layout/overlay_orb.xml")
        assertTrue(orb.contains("@+id/overlay_orb_panel"))
        assertTrue(orb.contains("@+id/overlay_chat_panel"))
        assertTrue(orb.contains("@+id/overlay_click_through_switch"))
        assertTrue(orb.contains("@string/orb_click_through"))
        val panelAt = orb.indexOf("@+id/overlay_orb_panel")
        val switchAt = orb.indexOf("@+id/overlay_click_through_switch")
        val chatAt = orb.indexOf("@+id/overlay_chat_panel")
        assertTrue("the switch must live in the function orb panel", panelAt < switchAt && switchAt < chatAt)
        assertFalse(orb.contains("android:clickable=\"false\""))
        assertFalse(orb.contains("android:enabled=\"false\""))
        assertFalse(orb.contains("FLAG_NOT_TOUCHABLE"))
    }

    @Test
    fun refreshControlsMirrorTheStoredSwitchAndRespectOrbSyncing() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val sync = body(window, "fun syncFromPrefs() {", "private fun applyClickThroughSetting()")
        assertTrue(sync.contains("applyClickThroughSetting()"))
        val refresh = body(window, "private fun refreshOrbControls() {", "private fun refreshActionButtons()")
        assertTrue(refresh.contains("overlay_click_through_switch"))
        assertTrue(refresh.contains("BluefinStore.readClickThrough(prefs())"))
    }

    @Test
    fun launcherKeepsNoClickThroughKnowledgeAtAll() {
        val launcher = source("src/main/java/com/wwkhub/bluefin/OverlayLauncher.kt")
        assertFalse(launcher.contains("KEY_CLICK_THROUGH"))
        assertFalse(launcher.contains("readClickThrough"))
        assertFalse(launcher.contains("OverlayClickThroughPolicy"))
        assertFalse(launcher.contains("click_through"))
    }

    @Test
    fun showKeepsTheProvenOrderWithoutReadingTheSwitch() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val show = body(window, "fun show() {", "fun close() {")
        val expected = listOf(
            "if (attached || destroyed) return",
            "applyStageSize()",
            "registerDisplayCallbacks()",
            "windowManager.addView(root, params)",
            "attached = true",
            "layoutOrb()",
            "loadedPackKey = cheapPackKey()",
            "loadStage(cheapStageUrl())",
            "handler.post { if (attached && !destroyed) syncFromPrefs() }",
        )
        var cursor = -1
        expected.forEach { line ->
            val at = show.indexOf(line, cursor + 1)
            assertTrue("$line must appear in order", at > cursor)
            cursor = at
        }
        assertFalse(show.contains("KEY_CLICK_THROUGH"))
        assertFalse(show.contains("readClickThrough"))
        assertFalse(show.contains("OverlayLauncher"))
        assertFalse(show.contains("canDrawOverlays"))
        assertFalse(show.contains("requestPermissions"))
        assertFalse(show.contains("Chat"))
        assertFalse(show.contains("Keystore"))
        assertFalse(show.contains("ApiKeyVault"))
        assertFalse(show.contains("PackRepository.resolveCurrent"))
    }

    @Test
    fun launcherShowWindowStillOnlyRequiresTheOverlayPermission() {
        val launcher = source("src/main/java/com/wwkhub/bluefin/OverlayLauncher.kt")
        val showWindow = body(launcher, "private fun showWindow(", "fun hide(")
        assertTrue(showWindow.contains("if (!Settings.canDrawOverlays(app)) return false"))
        assertFalse(showWindow.contains("requestPermissions"))
        assertFalse(showWindow.contains("KEY_CLICK_THROUGH"))
        assertFalse(showWindow.contains("readClickThrough"))
        assertFalse(showWindow.contains("Chat"))
        assertFalse(showWindow.contains("PackRepository"))
    }

    @Test
    fun productHasTheNewCopyAndNoRc1TouchOrBubbleArtifacts() {
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(strings.contains("<string name=\"orb_click_through\">宠物点击穿透</string>"))
        assertFalse(strings.contains("响应触碰"))
        assertFalse(strings.contains("touch_enabled"))

        val productionSources = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .toList()
        assertTrue(productionSources.isNotEmpty())
        productionSources.forEach { file ->
            val text = file.readText()
            assertFalse("${file.path} must not revive bubbles", text.contains("OverlayBubbles"))
            assertFalse("${file.path} must not revive bubbles", text.contains("BubbleView"))
            assertFalse("${file.path} must not revive bubbles", text.contains("OverlayVisualPolicy"))
            assertFalse(
                "${file.path} must not recurse into screenSize()",
                text.contains("override fun screenSize() = screenSize()"),
            )
            assertFalse("${file.path} must not use rc1 copy", text.contains("响应触碰"))
        }
    }
}
