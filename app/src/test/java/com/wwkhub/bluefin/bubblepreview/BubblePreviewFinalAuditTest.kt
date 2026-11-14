package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure and source contracts for the final independent-audit follow-up:
 * screen-coordinate conversion, panel placement, preference races, animation
 * gating, one-attempt measurement failures, tail origin and host boundaries.
 */
class BubblePreviewFinalAuditTest {
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
    fun rootVisibleRectIsConvertedThroughANonZeroWindowOrigin() {
        val converted = BubblePreviewScreenCoordinates.visibleBounds(
            localVisible = BubblePreviewRect(10, 20, 110, 220),
            locationOnScreen = BubblePreviewPoint(300f, 500f),
            locationInWindow = BubblePreviewPoint(40f, 60f),
        )
        assertEquals(BubblePreviewRect(270, 460, 370, 660), converted)

        val negativeOrigin = BubblePreviewScreenCoordinates.visibleBounds(
            localVisible = BubblePreviewRect(1, 2, 21, 32),
            locationOnScreen = BubblePreviewPoint(-30f, -40f),
            locationInWindow = BubblePreviewPoint(0f, 0f),
        )
        assertEquals(BubblePreviewRect(-29, -38, -9, -8), negativeOrigin)

        assertNull(
            BubblePreviewScreenCoordinates.visibleBounds(
                localVisible = BubblePreviewRect(10, 10, 10, 30),
                locationOnScreen = BubblePreviewPoint(300f, 500f),
                locationInWindow = BubblePreviewPoint(40f, 60f),
            ),
        )
    }

    @Test
    fun compactOrbBoundsUseTheActualLocationAndLaidOutSize() {
        assertEquals(
            BubblePreviewRect(320, 480, 370, 540),
            BubblePreviewScreenCoordinates.compactBounds(
                locationOnScreen = BubblePreviewPoint(320f, 480f),
                widthPx = 50,
                heightPx = 60,
            ),
        )
        assertNull(
            BubblePreviewScreenCoordinates.compactBounds(
                locationOnScreen = BubblePreviewPoint(320f, 480f),
                widthPx = 0,
                heightPx = 60,
            ),
        )
    }

    @Test
    fun panelFrameRepositionsHighOrbAndCompactsSmallLandscape() {
        assertEquals(
            BubblePreviewChatPanelFrame(100, 300, compact = false),
            BubblePreviewPanelGeometry.chatPanelFrame(
                requestedTopPx = 100,
                baselineHeightPx = 300,
                visibleTopPx = 0,
                visibleFrameBottomPx = 1000,
                bottomMarginPx = 8,
                compactMinimumHeightPx = 160,
            ),
        )
        // A high orb would put the bottom under the IME; move the same panel up
        // instead of leaving hidden controls behind.
        assertEquals(
            BubblePreviewChatPanelFrame(492, 500, compact = false),
            BubblePreviewPanelGeometry.chatPanelFrame(
                requestedTopPx = 800,
                baselineHeightPx = 500,
                visibleTopPx = 100,
                visibleFrameBottomPx = 1000,
                bottomMarginPx = 8,
                compactMinimumHeightPx = 160,
            ),
        )
        // Small landscape: keep the full stack only while it fits, then compact.
        assertEquals(
            BubblePreviewChatPanelFrame(50, 242, compact = true),
            BubblePreviewPanelGeometry.chatPanelFrame(
                requestedTopPx = 50,
                baselineHeightPx = 500,
                visibleTopPx = 0,
                visibleFrameBottomPx = 300,
                bottomMarginPx = 8,
                compactMinimumHeightPx = 160,
            ),
        )
        // Even the compact controls cannot fit: use the real visible frame
        // rather than returning null and leaving the baseline panel behind.
        assertEquals(
            BubblePreviewChatPanelFrame(0, 120, compact = true),
            BubblePreviewPanelGeometry.chatPanelFrame(
                requestedTopPx = 10,
                baselineHeightPx = 500,
                visibleTopPx = 0,
                visibleFrameBottomPx = 120,
                bottomMarginPx = 8,
                compactMinimumHeightPx = 160,
            ),
        )
        // Unknown/empty visible insets are ordinary missing geometry.
        assertNull(
            BubblePreviewPanelGeometry.chatPanelFrame(
                requestedTopPx = 10,
                baselineHeightPx = 500,
                visibleTopPx = 0,
                visibleFrameBottomPx = 0,
                bottomMarginPx = 8,
                compactMinimumHeightPx = 160,
            ),
        )
    }

    @Test
    fun preferenceNotificationCapturesOffAndOnCannotReviveTheRound() {
        val off = BubblePreviewPreferenceChange(roundGeneration = 7L, enabledAtNotification = false)
        val on = BubblePreviewPreferenceChange(roundGeneration = 7L, enabledAtNotification = true)
        assertTrue(BubblePreviewPreferencePolicy.shouldDismiss(off, currentRoundGeneration = 7L))
        assertFalse(BubblePreviewPreferencePolicy.shouldDismiss(off, currentRoundGeneration = 8L))
        assertFalse(BubblePreviewPreferencePolicy.shouldDismiss(on, currentRoundGeneration = 7L))
    }

    @Test
    fun childAnimationsAreDisabledByEitherSystemFlagOrZeroScale() {
        assertTrue(BubblePreviewAnimationPolicy.enabled(true, 1f))
        assertFalse(BubblePreviewAnimationPolicy.enabled(true, 0f))
        assertFalse(BubblePreviewAnimationPolicy.enabled(false, 1f))
        assertFalse(BubblePreviewAnimationPolicy.enabled(true, Float.NaN))
        assertFalse(BubblePreviewAnimationPolicy.enabled(true, -1f))
    }

    @Test
    fun measurementExceptionCountsOnceWhileMissingGeometryDoesNot() {
        val circuit = BubblePreviewFailureCircuit(
            failureThreshold = 3,
            sustainedSuccessMs = 2_000L,
        )

        val missingAttempt = circuit.beginDisplayAttempt(0L)!!
        recordIfFailed(BubblePreviewMeasure.capture<String> { null }, missingAttempt, 1L)
        assertEquals(0, circuit.consecutiveFailures())
        assertFalse(missingAttempt.isFailed())

        val first = circuit.beginDisplayAttempt(2L)!!
        recordIfFailed(BubblePreviewMeasure.capture<String> { throw IllegalStateException() }, first, 3L)
        assertTrue(first.isFailed())
        assertEquals(1, circuit.consecutiveFailures())
        // The same attempt cannot count twice for repeated exception reports.
        recordIfFailed(BubblePreviewMeasure.capture<String> { throw IllegalArgumentException() }, first, 4L)
        assertEquals(1, circuit.consecutiveFailures())

        val second = circuit.beginDisplayAttempt(5L)!!
        recordIfFailed(BubblePreviewMeasure.capture<String> { throw ArithmeticException() }, second, 6L)
        assertEquals(2, circuit.consecutiveFailures())

        val third = circuit.beginDisplayAttempt(7L)!!
        recordIfFailed(BubblePreviewMeasure.capture<String> { throw NullPointerException() }, third, 8L)
        assertEquals(3, circuit.consecutiveFailures())
        assertTrue(circuit.isTripped())
        assertNull(circuit.beginDisplayAttempt(9L))
    }

    private fun recordIfFailed(
        result: BubblePreviewMeasureResult<String>,
        attempt: BubblePreviewDisplayAttempt,
        now: Long,
    ) {
        if (result is BubblePreviewMeasureResult.Failed) attempt.recordFailure(now)
    }

    @Test
    fun displayBasisIsCachedAndReusedByPreflightAndSnapshot() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val read = methodBody(host, "    private fun readBubbleDisplayBasis(")
        assertTrue(read.contains("bubbleDisplayBasis?.let { return it }"))
        assertTrue(read.contains("BubblePreviewMeasure.capture { currentDisplayBasisOrNull() }"))
        assertTrue(read.contains("bubbleDisplayBasis = it"))
        val projection = methodBody(host, "    private fun projectedOrbBounds(")
        assertTrue(projection.contains("readBubbleDisplayBasis()"))
        val capture = methodBody(host, "    private fun captureBubbleScreenSnapshot(")
        assertTrue(capture.contains("readBubbleDisplayBasis()"))

        val layout = methodBody(host, "    private fun onLayout(")
        assertFalse(layout.contains("currentWindowMetrics"))
        val panel = methodBody(host, "    private fun applyChatPanelInsets(")
        assertFalse(panel.contains("currentWindowMetrics"))
        assertFalse(panel.contains("screenSize()"))
        assertTrue(methodBody(host, "    private fun relayout(").contains("invalidateBubbleDisplayBasis()"))
    }

    @Test
    fun panelAdaptationUsesTheAttachedRootAndNotTheBubbleImeGate() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val frame = methodBody(host, "    private fun chatVisibleFrame(")
        assertTrue(frame.contains("orbRoot.getWindowVisibleDisplayFrame(windowFrame)"))
        assertTrue(frame.contains("rootVisibleScreenBounds(orbRoot)"))
        assertTrue(frame.contains("orbRoot.getLocationOnScreen(location)"))
        assertFalse(frame.contains("bubbleImeGate"))
        assertFalse(frame.contains("screenSize()"))
        assertFalse(frame.contains("currentWindowMetrics"))

        val applyWrapper = methodBody(host, "    private fun applyChatPanelInsets()")
        assertFalse(applyWrapper.contains("bubbleImeGate"))
        assertFalse(applyWrapper.contains("dp(300)"))
        assertTrue(applyWrapper.contains("catch (error: Exception)"))
        val apply = methodBody(host, "    private fun applyChatPanelInsetsContained()")
        assertFalse(apply.contains("bubbleImeGate"))
        assertFalse(apply.contains("dp(300)"))
        assertTrue(apply.contains("BubblePreviewPanelGeometry.chatPanelFrame("))
        val compact = methodBody(host, "    private fun applyChatPanelCompact(")
        assertTrue(compact.contains("chatInput.maxLines = if (compact) 1 else 3"))
        assertTrue(compact.contains("chatScroll.visibility = if (compact) View.GONE else View.VISIBLE"))
        assertTrue(host.contains("chatInput.maxLines = 3"))
    }

    @Test
    fun tailAdapterUsesTheBodyRectangleAndAFreshAnchor() {
        val window = source("src/main/java/com/wwkhub/bluefin/bubblepreview/BubblePreviewWindow.kt")
        val body = methodBody(window, "    private fun bodyRect(")
        assertTrue(body.contains("tailShort"))
        assertTrue(body.contains("plan.chipHeightPx"))
        val tail = methodBody(window, "    private fun positionTail(")
        assertTrue(tail.contains("val body = bodyRect()"))
        assertTrue(tail.contains("alongEdge(localAnchorX"))
        assertTrue(tail.contains("alongEdge(localAnchorY"))
        assertTrue(tail.contains("body.bottom - dp(1)"))
        val placement = methodBody(window, "    fun updatePlacement(")
        assertTrue(placement.contains("if (anchorChanged) positionTail()"))
        assertTrue(placement.contains("layoutVisual(animate = false)"))
        assertTrue(placement.contains("updateWindow()"))

        val controller = source(
            "src/main/java/com/wwkhub/bluefin/bubblepreview/BubblePreviewController.kt",
        )
        val update = methodBody(controller, "    private fun requestUpdate(")
        assertTrue(update.contains("frame.geometry?.resolvedAnchor()"))
        val apply = methodBody(controller, "    private fun applyWindowState(")
        assertTrue(apply.contains("lastFrame?.geometry?.resolvedAnchor()"))
    }

    @Test
    fun childAnimationResourcesAreReachedOnlyBehindTheAnimationGate() {
        val window = source("src/main/java/com/wwkhub/bluefin/bubblepreview/BubblePreviewWindow.kt")
        listOf(
            "R.anim.bf_speech_enter",
            "R.anim.bf_speech_exit",
        ).forEach { resource ->
            val resourceIndex = window.indexOf(resource)
            assertTrue("missing $resource", resourceIndex >= 0)
            val gate = window.lastIndexOf("animationsEnabled()", resourceIndex)
            assertTrue("$resource must be gated", gate in 0 until resourceIndex)
        }
        assertTrue(window.contains("BubblePreviewAnimationPolicy.enabled("))
        assertTrue(window.contains("ValueAnimator.areAnimatorsEnabled()"))
    }

    @Test
    fun signalDeliveryKeepsNoTruncatedOrQueuedFullText() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        assertFalse(host.contains("MAX_BUBBLE_SIGNAL_CODE_UNITS"))
        assertFalse(host.contains("32768"))
        val attach = methodBody(host, "    private fun attachBubbleChatListener(")
        // Main-thread notification consumes the controller immediately.
        val mainDeliver = attach.indexOf("deliverBubbleSignal(token, bubbleSignalPayload(snapshot))")
        assertTrue(mainDeliver >= 0)
        // A defensive non-main notification posts the token only, then reads the
        // current snapshot on main; it never captures the snapshot in the post.
        val post = attach.indexOf("handler.post")
        val currentSnapshot = attach.indexOf("session.snapshot()", post)
        assertTrue(post in 0 until currentSnapshot)
        assertFalse(attach.contains("deliverBubbleSignal(token, snapshot)"))
        assertFalse(attach.contains("pendingSignal"))
    }

    @Test
    fun onlyTheOptionalHostTailAndNullableCallbacksOwnABoundary() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val layout = methodBody(host, "    private fun onLayout(")
        assertFalse(layout.contains("catch (error: Exception)"))
        assertTrue(layout.contains("publishBubbleHostTail(parsed, now)"))
        val placeHits = layout.indexOf("placeHit(parsed)")
        val tail = layout.indexOf("publishBubbleHostTail(parsed, now)")
        assertTrue(placeHits in 0 until tail)

        val tailBody = methodBody(host, "    private fun publishBubbleHostTail(")
        assertTrue(tailBody.contains("catch (error: Exception)"))
        assertTrue(tailBody.contains("abortBubblePreviewRound()"))
        assertFalse(tailBody.contains("placeHit("))
        assertFalse(tailBody.contains("OverlayPetPolicy"))

        val callback = methodBody(host, "    private fun runBubbleHostCallback(")
        assertTrue(callback.contains("catch (error: Exception)"))
        assertTrue(callback.contains("abortBubblePreviewRound()"))
        val dismiss = methodBody(host, "    private fun dismissBubblePreview(")
        assertTrue(dismiss.contains("catch (error: Exception)"))
        assertFalse(dismiss.contains("catch (error: Throwable)"))
    }
}
