package com.wwkhub.bluefin.bubblepreview

import com.wwkhub.bluefin.ChatFailureText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException

/**
 * Stage-D release contracts: formal visuals actually wired, the final gate's
 * pure predicate, foreground/round-generation safety, bounded measurement and
 * hostile service-error sanitisation.
 */
class BubblePreviewStageDContractTest {
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

    @Test
    fun formalSpeechShapesTailsIconsDotsAndAnimationsAreActuallyUsed() {
        val window = source("src/main/java/$modulePath/BubblePreviewWindow.kt")
        listOf(
            "R.drawable.bf_speech_input_body",
            "R.drawable.bf_speech_reply_body",
            "R.drawable.bf_speech_user_chip",
            "R.drawable.bf_speech_tail_down",
            "R.drawable.bf_speech_tail_up",
            "R.drawable.bf_speech_tail_left",
            "R.drawable.bf_speech_tail_right",
            "R.drawable.bf_speech_ic_error",
            "R.drawable.bf_speech_ic_stop",
            "R.drawable.bf_speech_ic_expand",
            "R.drawable.bf_speech_ic_close",
            "R.drawable.bf_speech_dots",
            "R.drawable.bf_speech_ellipsis_static",
            "R.anim.bf_speech_enter",
            "R.anim.bf_speech_exit",
        ).forEach { resource ->
            assertTrue("$resource must be wired, not merely imported", window.contains(resource))
        }
        assertTrue(window.contains("BubbleTailView("))
        assertTrue(window.contains("usePackReply"))
        assertTrue(window.contains("GradientDrawable"))

        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        assertTrue(controller.contains("packBubbleColors = packBubbleColors"))
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        assertTrue(host.contains("packColors = currentManifest?.bubble"))
    }

    @Test
    fun userCloseUsesBoundedExitButInvalidationStillRemovesImmediately() {
        val window = source("src/main/java/$modulePath/BubblePreviewWindow.kt")
        val close = methodBody(window, "    private fun requestUserClose()")
        assertTrue(close.contains("R.anim.bf_speech_exit"))
        assertTrue(close.contains("EXIT_ANIMATION_MILLIS"))
        assertTrue(close.contains("catch (error: Exception)"))
        assertEquals(80L, Regex("EXIT_ANIMATION_MILLIS = (\\d+)L")
            .find(window)!!.groupValues[1].toLong())
        assertTrue(methodBody(window, "    fun hideForCleanup()").contains("removeCallbacks"))

        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        val dismiss = methodBody(controller, "    private fun dismissInternal(")
        assertFalse(dismiss.contains("bf_bubble_exit"))
        val remove = methodBody(controller, "    private fun removeWindow()")
        assertTrue(remove.indexOf("hideForCleanup()") < remove.indexOf("removeImmediateOnce()"))
    }

    @Test
    fun openingConstructorDoesNotAllocateTheOptionalVisibilityListener() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        assertFalse(host.contains("private val bubbleAppVisibilityListener"))
        assertTrue(host.contains("private var bubbleAppVisibilityListener"))

        val install = methodBody(host, "    private fun installBubbleAppVisibility()")
        assertTrue(install.contains("OverlayAppVisibility.Listener"))
        assertTrue(install.contains("OverlayAppVisibility.addListener"))
        assertTrue(install.contains("handler.post"))
        assertTrue(install.indexOf("catch (error: Exception)") <
            install.indexOf("OverlayAppVisibility.addListener"))
        // The body after post must contain its own Exception boundary.
        val posted = install.substringAfter("handler.post {")
        assertTrue(posted.contains("catch (error: Exception)"))

        listOf(
            "    fun show() {",
            "    private fun layoutOrb() {",
            "    private fun applyStageSize() {",
            "    private fun screenSize(): Pair<Int, Int> {",
            "    private fun setupOrb() {",
            "    private fun setupWebView() {",
            "    private fun inflateRoot(): FrameLayout {",
            "    fun syncFromPrefs() {",
        ).forEach { signature ->
            assertFalse(
                "$signature must not allocate the optional listener",
                methodBody(host, signature).contains("OverlayAppVisibility.Listener"),
            )
        }
    }

    @Test
    fun currentForegroundIsRecheckedBeforeEntryPreflightAddAndRestore() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val schedule = methodBody(host, "    private fun scheduleBubblePreviewAfterStarted(")
        val foreground = schedule.indexOf("OverlayAppVisibility.foreground")
        val controller = schedule.indexOf("BubblePreviewController(")
        assertTrue(foreground in 0 until controller)

        assertTrue(methodBody(host, "    private fun bubbleRestoreAllowed()")
            .contains("!OverlayAppVisibility.foreground"))
        val finish = methodBody(host, "    private fun finishBubbleFirstAttach(")
        val restorePost = finish.indexOf("handler.post")
        val postForeground = finish.indexOf("OverlayAppVisibility.foreground", restorePost)
        assertTrue(restorePost in 0 until postForeground)
        assertTrue(methodBody(host, "    private fun startAcceptedBubbleRound(")
            .contains("OverlayAppVisibility.foreground"))

        val window = source("src/main/java/$modulePath/BubblePreviewController.kt")
        val eligible = methodBody(window, "    private fun isEligible(")
        assertTrue(eligible.contains("OverlayAppVisibility.foreground"))
    }

    @Test
    fun lateRoundCallbacksCarryTheirCapturedToken() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val post = methodBody(host, "    private fun postBubbleCallback(")
        assertTrue(post.contains("roundToken: Long?"))
        assertTrue(post.contains("bubbleSignalToken ?: bubbleAwaitingAttachToken"))
        assertTrue(post.contains("current != roundToken"))
        val finished = methodBody(host, "            override fun onBubbleRoundFinished(")
        assertTrue(finished.contains("postBubbleCallback(\"finished\", token)"))
    }

    @Test
    fun constructorFailureOwnsOneAttemptAndHostErrorAbortsTheRound() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val schedule = methodBody(host, "    private fun scheduleBubblePreviewAfterStarted(")
        assertTrue(schedule.contains("circuit.beginDisplayAttempt(nowMillis())"))
        assertTrue(schedule.contains("attempt.recordFailure(nowMillis())"))
        assertTrue(schedule.contains("catch (error: Exception)"))
        assertTrue(schedule.contains("throw error"))
        assertTrue(schedule.contains("abortBubblePreviewRound()"))
        val abort = methodBody(host, "    private fun abortBubblePreviewRound()")
        assertTrue(abort.contains("cancelBubbleFirstAttach()"))
        assertTrue(abort.contains("detachBubbleChatListener()"))
        assertTrue(abort.contains("dismissForHostUi()"))
        assertTrue(methodBody(host, "    private fun publishBubbleGeometry(")
            .contains("abortBubblePreviewRound()"))
    }

    @Test
    fun hostileServiceFailureNeverReachesBubbleOrRecordCopy() {
        val hostile = listOf(
            "sk-fakeKey123",
            "https://api.example.com/v1/chat?key=sk-fakeKey123",
            """{"error":"raw-json","key":"sk-fakeKey123","url":"https://api.example.com"}""",
            "Bearer sk-fakeKey123\nraw body",
        )
        hostile.forEach { raw ->
            val safe = ChatFailureText.forBubble(raw)
            assertEquals(ChatFailureText.BUBBLE_FAILURE, safe)
            assertFalse(safe.contains("fakeKey"))
            assertFalse(safe.contains("example.com"))
            assertFalse(safe.contains("raw-json"))
        }
        assertEquals(ChatFailureText.SERVICE_FAILURE, ChatFailureText.forServiceFailure())

        // Known locally mapped HTTP/network reasons remain readable.
        assertEquals(
            ChatFailureText.forStatus(401),
            ChatFailureText.forBubble(ChatFailureText.forStatus(401)),
        )
        assertEquals(
            ChatFailureText.forThrowable(SocketTimeoutException()),
            ChatFailureText.forBubble(ChatFailureText.forThrowable(SocketTimeoutException())),
        )

        val session = source("src/main/java/com/wwkhub/bluefin/ChatSession.kt")
        assertTrue(session.contains("ChatFailureText.forServiceFailure()"))
        assertFalse(session.contains("oneLine(error.message"))
        assertFalse(session.contains("error.message ?:"))

        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        assertTrue(host.contains("ChatFailureText.forBubble(snapshot.lastError)"))
        assertFalse(host.contains("safeFailureText = snapshot.lastError"))
    }

    @Test
    fun paintMetricsAreMeasuredOncePerControllerNotPerLayoutFrame() {
        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        assertTrue(controller.contains("measuredActionWidths: Map<BubblePreviewAction, Float> by lazy"))
        assertTrue(controller.contains("measuredBodyLinePx: Float by lazy"))
        assertTrue(controller.contains("measuredNameLinePx: Float by lazy"))
        val compute = methodBody(controller, "    private fun computeReplyPlacement(")
        assertTrue(compute.contains("measuredBodyLinePx"))
        assertTrue(compute.contains("measuredNameLinePx"))
        assertFalse(compute.contains("measureActionTextWidths()"))
        assertFalse(compute.contains("measureBodyLinePx()"))
        assertFalse(compute.contains("measureNameLinePx()"))
    }

    @Test
    fun tailAndActionsFitInTheCompactWindowWithoutCoveringTheReply() {
        val window = source("src/main/java/$modulePath/BubblePreviewWindow.kt")
        val body = methodBody(window, "    private fun bodyRect(")
        assertTrue(body.contains("plan.chipHeightPx"))
        assertTrue(body.contains("tailShort"))
        assertTrue(body.contains("BubblePreviewSide.ABOVE"))
        assertTrue(body.contains("BubblePreviewSide.BELOW"))
        assertTrue(body.contains("BubblePreviewSide.LEFT"))
        assertTrue(body.contains("BubblePreviewSide.RIGHT"))
        val tail = methodBody(window, "    private fun positionTail(")
        assertTrue(tail.contains("body.bottom - dp(1)"))
        assertTrue(tail.contains("body.top - height + dp(1)"))
        assertTrue(tail.contains("body.left - width + dp(1)"))
        assertTrue(tail.contains("body.right - dp(1)"))
        val actions = methodBody(window, "    private fun buildReplyFace(")
        assertTrue(actions.contains("plan.windowWidthPx - actionWidth"))
        assertTrue(actions.contains("R.string.orb_reply_full_short"))
        assertTrue(actions.contains("{ onStop() }"))
        assertTrue(actions.contains("setOnClickListener { onExpand() }"))
        assertTrue(actions.contains("bf_speech_ic_close"))
        assertTrue(actions.contains("touchPx()"))
    }
}
