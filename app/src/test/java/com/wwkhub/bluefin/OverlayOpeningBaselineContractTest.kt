package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Source lock for the 0.6.0-rc3 opening graph, taken from the approved
 * baseline aea44cc2a89a167596355ff1b0e46eeceb82e175.
 *
 * Stage A must not add bubbles, loads, chat setup or config reads to the
 * constructor/show/layout path. A hash comparison is intentionally strict:
 * the reviewed synchronous path is a contract, not a style suggestion.
 */
class OverlayOpeningBaselineContractTest {
    private val baselineCommit = "aea44cc2a89a167596355ff1b0e46eeceb82e175"

    private val expectedLauncherSha =
        "0361d4428c8e8a2aac639568c0650e6faa3fe574a11c6d389a9ca7abc73a3904"
    private val expectedShowSha =
        "d2b0641b34fa1bc530cdf64cc82423fb10e9c2dce57135cd7fe0aa16aa2f030d"
    private val expectedLayoutOrbSha =
        "a630e4719cc6761dd969ba37141817ddf0f23b6a68feadee0667f98d3ab336ce"
    private val expectedApplyStageSizeSha =
        "85afc51dfd7426b1f560673fd8ed72c9846dd393fb51b8491ca134ac7323210c"
    private val expectedScreenSizeSha =
        "d47f516485e9e54f4e4ff24fa29294c83e8f68f08a7f4082057d92bfb796fbf1"
    private val expectedSetupOrbSha =
        "4cde264e1807df3fe68534293670e5a74d836c1bd6c31afa69c542c3c3b6cdac"
    private val expectedSetupWebViewSha =
        "e0d1d518c992781732b315efb1ef571368dae952514e407e18025b552a4ce586"
    private val expectedInflateRootSha =
        "5957c288419f33eb0f2e423e09dddc5f0aa0fd2172dfd72fc3d571fef26dc897"
    private val expectedFullscreenParamsSha =
        "0291154456d378a40fa75bade03fa4e7b14c21fdde86ae5879e5c8efb5b09aa1"
    private val expectedStageAlphaSha =
        "e9b66442fcce859f05cccd8f1482a812cf5ad207d18e6c889902d453d1e444a9"

    private fun source(path: String): String = File(path).readText()

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("missing body for $signature", open >= 0)
        var depth = 0
        var index = open
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        while (index < source.length) {
            val char = source[index]
            val next = if (index + 1 < source.length) source[index + 1] else '\u0000'
            when {
                inLineComment -> if (char == '\n') inLineComment = false
                inBlockComment -> if (char == '*' && next == '/') {
                    inBlockComment = false
                    index += 1
                }
                inString -> when {
                    char == '\\' -> index += 1
                    char == '"' -> inString = false
                }
                inChar -> when {
                    char == '\\' -> index += 1
                    char == '\'' -> inChar = false
                }
                char == '/' && next == '/' -> {
                    inLineComment = true
                    index += 1
                }
                char == '/' && next == '*' -> {
                    inBlockComment = true
                    index += 1
                }
                char == '"' -> inString = true
                char == '\'' -> inChar = true
                char == '{' -> depth += 1
                char == '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1).trimEnd()
                }
            }
            index += 1
        }
        throw AssertionError("unbalanced body for $signature")
    }

    private fun constructorOpeningBlock(window: String): String {
        val start = window.indexOf("internal class OverlayWindow(")
        assertTrue("missing OverlayWindow declaration", start >= 0)
        val end = window.indexOf("\n    fun show() {", start)
        assertTrue("missing show() after constructor", end > start)
        return window.substring(start, end).trimEnd()
    }

    @Test
    fun launcherIsUntouchedFromTheApprovedBaseline() {
        val launcher = source("src/main/java/com/wwkhub/bluefin/OverlayLauncher.kt")
        assertEquals(
            "OverlayLauncher.kt changed from baseline $baselineCommit",
            expectedLauncherSha,
            sha256(launcher),
        )
        assertTrue(launcher.contains("if (!Settings.canDrawOverlays(app)) return false"))
        assertTrue(launcher.contains("candidate.show()"))
    }

    @Test
    fun constructorOnlyAddsAnInertNullableOptionalReference() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val block = constructorOpeningBlock(window)
        assertTrue(block.contains("private val cuePlayer = CuePlayer()"))
        assertTrue(block.contains("private var chatSession: ChatSession? = null"))
        assertTrue(block.contains("private var bubblePreview: BubblePreviewController? = null"))
        // A nullable reference is allowed, but the opening constructor may not
        // construct, inflate, subscribe, read prefs/resources or measure it.
        assertFalse(block.contains("BubblePreviewController("))
        assertFalse(block.contains("BubblePreviewWindow("))
        assertFalse(block.contains("BubblePreviewExplicitChatEntry"))
        assertFalse(block.contains("readBubblePreview"))
        assertFalse(block.contains("R.layout.bf_bubble"))
        assertFalse(block.contains("ChatSession.shared"))
        assertFalse(block.contains("PackRepository.resolveCurrent"))
        assertFalse(block.contains("registerOnSharedPreferenceChangeListener"))
    }

    @Test
    fun showStillUsesTheExactApprovedSynchronousSequence() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val show = methodBody(window, "    fun show() {")
        assertEquals(
            "OverlayWindow.show() changed from baseline $baselineCommit",
            expectedShowSha,
            sha256(show),
        )
        val expectedOrder = listOf(
            "if (attached || destroyed) return",
            "applyStageSize()",
            "registerDisplayCallbacks()",
            "windowManager.addView(root, params)",
            "attached = true",
            "layoutOrb()",
            "loadedPackKey = cheapPackKey()",
            "web.loadUrl(cheapStageUrl())",
            "handler.post { if (attached && !destroyed) syncFromPrefs() }",
        )
        var cursor = -1
        expectedOrder.forEach { line ->
            val at = show.indexOf(line, cursor + 1)
            assertTrue("$line must remain in show() order", at > cursor)
            cursor = at
        }
    }

    @Test
    fun layoutOrbMatchesTheBaselineAndTakesNoGeometryCallback() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val layout = methodBody(window, "    private fun layoutOrb() {")
        assertEquals(
            "OverlayWindow.layoutOrb() changed from baseline $baselineCommit",
            expectedLayoutOrbSha,
            sha256(layout),
        )
        assertFalse(layout.contains("BubblePreview"))
        assertTrue(layout.contains("val (sw, sh) = screenSize()"))
    }

    @Test
    fun screenSizeAndAllOpeningHelpersRemainByteIdentical() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val checks = listOf(
            "    private fun screenSize(): Pair<Int, Int> {" to expectedScreenSizeSha,
            "    private fun setupOrb() {" to expectedSetupOrbSha,
            "    private fun setupWebView() {" to expectedSetupWebViewSha,
            "    private fun inflateRoot(): FrameLayout {" to expectedInflateRootSha,
            "    private fun fullscreenParams(" to expectedFullscreenParamsSha,
            "    private fun stageAlphaForCurrentDevice(): Float {" to expectedStageAlphaSha,
        )
        checks.forEach { (signature, expected) ->
            assertEquals(
                "$signature changed from baseline $baselineCommit",
                expected,
                sha256(methodBody(window, signature)),
            )
        }
    }

    @Test
    fun applyStageSizeMatchesTheBaseline() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val apply = methodBody(window, "    private fun applyStageSize() {")
        assertEquals(
            "OverlayWindow.applyStageSize() changed from baseline $baselineCommit",
            expectedApplyStageSizeSha,
            sha256(apply),
        )
        assertTrue(apply.contains("val (sw, sh) = screenSize()"))
    }

    @Test
    fun mainActivityOnResumeOnlyNotifiesInertForegroundVisibility() {
        val main = source("src/main/java/com/wwkhub/bluefin/MainActivity.kt")
        val resume = methodBody(main, "    override fun onResume() {")
        // The explicit chat screen owns its own foreground signal. The activity
        // resume path may notify the inert visibility object, but must not
        // synchronously resolve/sync an overlay after a permission grant.
        assertTrue(resume.contains("OverlayAppVisibility.setForeground(true)"))
        assertTrue(resume.contains("OverlayLauncher.onResume(this)"))
        assertFalse(resume.contains("OverlayLauncher.sync("))
        assertFalse(resume.contains("OverlayLauncher.isShowing()"))
        assertFalse(resume.contains("OverlayLauncher.hide("))
        assertFalse(resume.contains("BubblePreview"))
        assertFalse(resume.contains("ChatSession"))
        assertFalse(resume.contains("ChatDraft"))
    }
}
