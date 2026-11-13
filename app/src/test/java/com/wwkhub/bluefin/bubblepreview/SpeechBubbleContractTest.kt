package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * 0.8 head-top speech contracts: opening isolation, immediate click-through
 * removal, input/reply shape, small-screen caps, IME flags and entry routing.
 */
class SpeechBubbleContractTest {
    private val modulePath = "com/wwkhub/bluefin/bubblepreview"

    private fun source(path: String): String = File(path).readText()

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

    private fun request(
        mode: SpeechBubbleMode,
        state: BubbleVisibleState? = null,
        scale: Float = 0.85f,
        safeWidth: Int = 1080,
        safeHeight: Int = 2000,
        landscape: Boolean = false,
        lines: Int = 1,
        fontScale: Float = 1f,
    ) = SpeechBubbleScaleRequest(
        mode = mode,
        visibleState = state,
        characterScale = scale,
        density = 2f,
        scaledDensity = 2f * fontScale,
        safeWidthPx = safeWidth,
        safeHeightPx = safeHeight,
        landscape = landscape,
        inputLines = lines,
    )

    @Test
    fun openingSynchronousPathNeverTouchesTheSpeechInput() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        listOf(
            "    fun show() {",
            "    private fun applyStageSize() {",
            "    private fun screenSize(): Pair<Int, Int> {",
            "    private fun layoutOrb() {",
            "    private fun setupOrb() {",
            "    private fun setupWebView() {",
            "    private fun inflateRoot(): FrameLayout {",
        ).forEach { signature ->
            val body = methodBody(window, signature)
            listOf(
                "EditText",
                "requestFocus",
                "showSoftInput",
                "BubblePreviewWindow(",
                "SpeechBubble",
                "SpeechInput",
                "readBubblePreview",
            ).forEach { token ->
                assertFalse("$signature must not contain $token", body.contains(token))
            }
        }
        val constructor = window.substring(
            window.indexOf("internal class OverlayWindow("),
            window.indexOf("\n    fun show() {"),
        )
        assertFalse(constructor.contains("EditText("))
        assertFalse(constructor.contains("BubblePreviewController("))
        assertFalse(constructor.contains("BubblePreviewWindow("))
        assertFalse(constructor.contains("SpeechInput"))

        val launcher = source("src/main/java/com/wwkhub/bluefin/OverlayLauncher.kt")
        assertFalse(launcher.contains("BubblePreview"))
        assertFalse(launcher.contains("EditText"))

        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        assertFalse(controller.contains("screenSize"))
        assertFalse(controller.contains("currentWindowMetrics"))
    }

    @Test
    fun screenSizeHasNoSelfRecursionAndIsStillTheDirectWindowMetricsReader() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        assertFalse(window.contains("override fun screenSize"))
        assertFalse(window.contains("screenSize() = screenSize()"))
        assertFalse(window.contains("screenSize(): Pair<Int, Int> = screenSize"))
        val body = methodBody(window, "    private fun screenSize(): Pair<Int, Int> {")
        assertTrue(body.contains("currentWindowMetrics.bounds"))
        assertFalse(body.substringAfter("{").contains("screenSize("))
    }

    @Test
    fun clickThroughRemovesInputAndReplyImmediatelyBeforeAnyExitAnimation() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val apply = methodBody(host, "    private fun applyClickThroughSetting()")
        assertTrue(apply.contains("it.onClickThroughEnabled()"))
        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        val click = methodBody(controller, "    fun onClickThroughEnabled()")
        assertTrue(click.contains("CLICK_THROUGH"))
        assertTrue(click.contains("clearRound = true"))
        val remove = methodBody(controller, "    private fun removeWindow()")
        assertTrue(remove.indexOf("hideForCleanup()") < remove.indexOf("removeImmediateOnce()"))
        assertFalse(remove.contains("bf_speech_exit"))
        val window = source("src/main/java/$modulePath/BubblePreviewWindow.kt")
        val hide = methodBody(window, "    fun hideForCleanup()")
        assertTrue(hide.indexOf("releaseInputFocus()") < hide.indexOf("destroyedForCleanup = true"))
        assertTrue(hide.contains("root.visibility = View.GONE"))
    }

    @Test
    fun inputAndReplyFacesUseTheSpeechResourcesNotTheOldDoubleColourCard() {
        val window = source("src/main/java/$modulePath/BubblePreviewWindow.kt")
        listOf(
            "EditText(context)",
            "R.drawable.bf_speech_ic_expand",
            "R.drawable.bf_speech_btn_send",
            "R.drawable.bf_speech_input_body",
            "R.drawable.bf_speech_reply_body",
            "R.drawable.bf_speech_user_chip",
            "R.drawable.bf_speech_tail_down",
            "R.drawable.bf_speech_tail_up",
            "R.drawable.bf_speech_tail_left",
            "R.drawable.bf_speech_tail_right",
            "R.string.bf_speech_hint",
            "R.string.bf_speech_expand_cd",
            "R.string.bf_speech_send",
        ).forEach { token ->
            assertTrue("window must wire $token", window.contains(token))
        }
        assertFalse(window.contains("R.drawable.bf_bubble_user_body"))
        assertFalse(window.contains("R.drawable.bf_bubble_character_body"))
        assertFalse(window.contains("R.drawable.bf_bubble_chip"))
        val turn = source("src/main/java/$modulePath/BubblePreviewTurnState.kt")
        assertFalse(turn.contains("COLLAPSED"))
        assertFalse(turn.contains("collapsedChip"))
        assertFalse(turn.contains("isFailureChip"))
        assertFalse(turn.contains("onCollapsedClick"))
        assertTrue(turn.contains("CHIP_LINGER"))
        val scale = source("src/main/java/$modulePath/BubblePreviewScalePolicy.kt")
        assertFalse(scale.contains("fun chipPlan("))
    }

    @Test
    fun portraitCapsMatchTheSmallScreenContract() {
        val input = SpeechBubbleLayoutPolicy.inputPlans(
            request(SpeechBubbleMode.INPUT, lines = 3),
        ).first()
        assertNotNull(input)
        assertTrue(input.bodyLines in 1..3)
        assertTrue(input.windowWidthPx <= floor(1080 * 0.72).toInt())
        assertTrue(input.windowWidthPx <= (280 * 2))
        assertTrue(input.windowHeightPx <= floor(min(440.0, 2000 * 0.36)).toInt())

        val reply = SpeechBubbleLayoutPolicy.replyPlans(
            request(SpeechBubbleMode.REPLY, state = BubbleVisibleState.TYPING),
        ).first()
        assertTrue(reply.bodyLines in 1..4)
        assertTrue(reply.chipWidthPx <= floor(min(400.0, 1080 * 0.50)).toInt())
        assertTrue(reply.showActions)
        assertTrue(reply.actionRowPx >= ceil(96.0).toInt())
        assertTrue(reply.windowHeightPx <= floor(min(440.0, 2000 * 0.36)).toInt())

        val linger = SpeechBubbleLayoutPolicy.replyPlans(
            request(SpeechBubbleMode.REPLY, state = BubbleVisibleState.CHIP_LINGER),
        ).first()
        assertFalse(linger.showReplyBody)
        assertTrue(linger.windowHeightPx <= linger.chipHeightPx)
    }

    @Test
    fun replyActionsKeepTheirPositionWhenGenerationFinishes() {
        val streaming = SpeechBubbleLayoutPolicy.replyPlans(
            request(SpeechBubbleMode.REPLY, state = BubbleVisibleState.TYPING),
        ).first()
        val complete = SpeechBubbleLayoutPolicy.replyPlans(
            request(SpeechBubbleMode.REPLY, state = BubbleVisibleState.HOLD),
        ).first()
        assertEquals(streaming.windowHeightPx, complete.windowHeightPx)
        assertEquals(listOf(BubblePreviewAction.STOP, BubblePreviewAction.CLOSE), streaming.actionLabels)
        assertEquals(listOf(BubblePreviewAction.RECORDS, BubblePreviewAction.CLOSE), complete.actionLabels)
        assertTrue(complete.chipHeightPx >= complete.actionRowPx)
    }

    @Test
    fun landscapeCapsUseOneInputLineTwoReplyLinesAndTheShorterStack() {
        val input = SpeechBubbleLayoutPolicy.inputPlans(
            request(
                SpeechBubbleMode.INPUT,
                safeWidth = 2000,
                safeHeight = 600,
                landscape = true,
                lines = 3,
            ),
        ).first()
        assertEquals(1, input.bodyLines)
        assertTrue(input.windowHeightPx <= floor(min(320.0, 600 * 0.50)).toInt())

        val reply = SpeechBubbleLayoutPolicy.replyPlans(
            request(
                SpeechBubbleMode.REPLY,
                state = BubbleVisibleState.TYPING,
                safeWidth = 2000,
                safeHeight = 600,
                landscape = true,
            ),
        ).first()
        assertTrue(reply.bodyLines <= 2)
        assertTrue(reply.windowHeightPx <= floor(min(320.0, 600 * 0.50)).toInt())
    }

    @Test
    fun fontScaleAndCharacterScaleChangeRealSpNotFixedDp() {
        val normal = SpeechBubbleLayoutPolicy.replyPlans(
            request(SpeechBubbleMode.REPLY, state = BubbleVisibleState.HOLD),
        ).first()
        val scaled = SpeechBubbleLayoutPolicy.replyPlans(
            request(SpeechBubbleMode.REPLY, state = BubbleVisibleState.HOLD, fontScale = 1.5f),
        ).first()
        assertTrue(scaled.bodyTextPx > normal.bodyTextPx)
        val small = SpeechBubbleLayoutPolicy.replyPlans(
            request(SpeechBubbleMode.REPLY, state = BubbleVisibleState.HOLD, scale = 0.50f),
        ).first()
        val large = SpeechBubbleLayoutPolicy.replyPlans(
            request(SpeechBubbleMode.REPLY, state = BubbleVisibleState.HOLD, scale = 1.30f),
        ).first()
        assertTrue(large.bodyTextPx > small.bodyTextPx)
        assertEquals(11f, SpeechBubbleLayoutPolicy.bodyTextSp(0.50f), 0.0001f)
        assertEquals(16f, SpeechBubbleLayoutPolicy.bodyTextSp(1.30f), 0.0001f)
    }

    @Test
    fun imeUsesImplicitOptionalFocusAndNeverAlternateFocusOrForcedShow() {
        val window = source("src/main/java/$modulePath/BubblePreviewWindow.kt")
        assertTrue(window.contains("InputMethodManager.SHOW_IMPLICIT"))
        assertFalse(window.contains("SHOW_FORCED"))
        assertFalse(window.contains("FLAG_ALT_FOCUSABLE_IM"))
        val focus = methodBody(window, "    fun focusInputForTyping()")
        assertTrue(focus.contains("FLAG_NOT_FOCUSABLE.inv()"))
        assertTrue(focus.contains("SOFT_INPUT_ADJUST_RESIZE"))
        assertTrue(focus.contains("requestFocus()"))
        assertFalse(focus.contains("SHOW_FORCED"))
        val release = methodBody(window, "    fun releaseInputFocus()")
        assertTrue(release.contains("hideSoftInputFromWindow"))
        assertTrue(release.contains("clearFocus()"))
        assertTrue(release.indexOf("hideSoftInputFromWindow") < release.indexOf("params.flags = baseFlags"))
        val apply = methodBody(window, "    fun applyState(")
        assertTrue(apply.contains("releaseInputFocus()"))
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val close = methodBody(host, "    fun close()")
        assertTrue(close.contains("bubble?.close()"))
        val dismiss = methodBody(host, "    private fun dismissBubblePreview()")
        assertTrue(dismiss.contains("dismissForHostUi()"))
    }

    @Test
    fun orbChatEntryUsesTheHeadTopInputAndFallsBackToTheRecordPanel() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val listeners = methodBody(host, "    private fun addOrbPanelListeners()")
        val openChat = listeners.substringAfter("R.id.overlay_open_chat")
            .substringBefore("orbPanel.findViewById<View>(R.id.overlay_change_character)")
        assertTrue(openChat.contains("openSpeechEntry()"))
        assertFalse(openChat.contains("openChatPanel()"))

        val entry = methodBody(host, "    private fun openSpeechEntry()")
        assertTrue(entry.contains("ModelEndpointStore.isReady"))
        assertTrue(entry.contains("showOrbUnconfiguredPrompt()"))
        assertTrue(entry.contains("if (clickThrough)"))
        assertTrue(entry.contains("openChatPanel(requestInput = false)"))
        assertTrue(entry.contains("readBubblePreview"))
        assertTrue(entry.contains("circuit.isTripped()"))
        assertTrue(entry.contains("scheduleSpeechInputAfterEntry()"))

        val schedule = methodBody(host, "    private fun scheduleSpeechInputAfterEntry()")
        assertTrue(schedule.indexOf("handler.post") < schedule.indexOf("BubblePreviewController("))
        assertTrue(schedule.contains("!attached || !pageReady"))
        assertTrue(schedule.contains("clickThrough"))
        assertTrue(schedule.contains("controller.showInput("))
        assertTrue(schedule.contains("fallbackSpeechToRecord("))
        val fallback = methodBody(host, "    private fun fallbackSpeechToRecord(")
        assertTrue(fallback.contains("openChatPanel(requestInput = false"))

        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        assertTrue(controller.contains("fun showInput("))
        assertTrue(controller.contains("fun beginReplyFromInput("))
        val send = methodBody(host, "    private fun sendSpeechInput(")
        assertTrue(send.contains("session.send(text)"))
        assertTrue(send.contains("EMPTY -> bubblePreview?.keepInput()"))
        assertTrue(send.contains("ChatDraftSendOutcome.BUSY"))
        assertTrue(send.contains("beginReplyFromInput("))
    }
}
