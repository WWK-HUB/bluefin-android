package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Stage-C policy tests: measured action fitting, the lazy IME gate, the
 * bounded first-attach flow, panel height clamping and projected orb bounds.
 */
class BubblePreviewChatUiPolicyTest {

    @Test
    fun actionTouchWidthIsMeasuredTextPlusPaddingWithFortyFourDpFloor() {
        assertEquals(
            88,
            BubblePreviewActionLayout.touchWidthPx(
                textWidthPx = 10f,
                density = 2f,
                minimumTouchPx = 88,
            ),
        )
        assertEquals(
            132,
            BubblePreviewActionLayout.touchWidthPx(
                textWidthPx = 100f,
                density = 2f,
                minimumTouchPx = 88,
            ),
        )
        // Invalid measurements fall back to the touch floor, never to zero.
        assertEquals(
            88,
            BubblePreviewActionLayout.touchWidthPx(
                textWidthPx = Float.NaN,
                density = 2f,
                minimumTouchPx = 88,
            ),
        )
    }

    @Test
    fun holdRequiresViewAllAndFallsBackToTheRecordPanelWhenItDoesNotFit() {
        val measured = mapOf(
            BubblePreviewAction.STOP to 40f,
            BubblePreviewAction.VIEW_ALL to 100f,
            BubblePreviewAction.RECORDS to 30f,
            BubblePreviewAction.CLOSE to 20f,
        )
        val wide = BubblePreviewActionLayout.choose(
            state = BubbleVisibleState.HOLD,
            contentWidthPx = 400f,
            density = 2f,
            minimumTouchPx = 88,
            measured = measured,
        )
        assertEquals(
            listOf(
                BubblePreviewAction.VIEW_ALL,
                BubblePreviewAction.RECORDS,
                BubblePreviewAction.CLOSE,
            ),
            wide,
        )

        // 查看全部 is required for a long overflow; hiding it and keeping only
        // 记录/× would silently weaken the preview contract. null tells the
        // caller to use the record panel instead.
        val narrow = BubblePreviewActionLayout.choose(
            state = BubbleVisibleState.HOLD,
            contentWidthPx = 300f,
            density = 2f,
            minimumTouchPx = 88,
            measured = measured,
        )
        assertNull(narrow)
    }

    @Test
    fun holdFallsBackToTheRecordPanelInsteadOfTruncatingOrShrinkingLabels() {
        val oversized = mapOf(
            BubblePreviewAction.RECORDS to 250f,
            BubblePreviewAction.CLOSE to 250f,
        )
        assertNull(
            BubblePreviewActionLayout.choose(
                state = BubbleVisibleState.HOLD,
                contentWidthPx = 300f,
                density = 2f,
                minimumTouchPx = 88,
                measured = oversized,
            ),
        )
        assertNull(
            BubblePreviewActionLayout.choose(
                state = BubbleVisibleState.THINKING,
                contentWidthPx = Float.POSITIVE_INFINITY,
                density = 2f,
                minimumTouchPx = 88,
                measured = emptyMap(),
            ),
        )
    }

    @Test
    fun thinkingKeepsStopRecordsAndCloseWhenTheyFit() {
        val measured = mapOf(
            BubblePreviewAction.STOP to 40f,
            BubblePreviewAction.RECORDS to 30f,
            BubblePreviewAction.CLOSE to 10f,
        )
        assertEquals(
            listOf(
                BubblePreviewAction.STOP,
                BubblePreviewAction.RECORDS,
                BubblePreviewAction.CLOSE,
            ),
            BubblePreviewActionLayout.choose(
                state = BubbleVisibleState.TYPING,
                contentWidthPx = 400f,
                density = 2f,
                minimumTouchPx = 88,
                measured = measured,
            ),
        )
    }

    @Test
    fun imeGateStartsUnknownAndNeverConfusesUnknownWithHiddenZero() {
        val gate = BubblePreviewImeGate()
        assertEquals(BubblePreviewImeState.UNKNOWN, gate.state)
        assertFalse(gate.isTrustworthy())
        assertFalse(gate.isHiddenAndKnown())
        assertEquals(0, gate.imeBottomInsets().bottom)

        gate.onInsets(bottomPx = 0, visible = false)
        assertTrue(gate.isTrustworthy())
        assertTrue(gate.isHiddenAndKnown())
        assertEquals(0, gate.imeBottomInsets().bottom)

        gate.onInsets(bottomPx = 120, visible = true)
        assertEquals(BubblePreviewImeState.VISIBLE, gate.state)
        assertEquals(120, gate.imeBottomInsets().bottom)

        gate.reset()
        assertEquals(BubblePreviewImeState.UNKNOWN, gate.state)
        assertFalse(gate.isTrustworthy())
    }

    @Test
    fun firstAttachRequiresPreflightCollapseAndTrustworthyGeometry() {
        val failedPreflight = BubblePreviewFirstAttachFlow()
        assertEquals(
            BubblePreviewAttachDecision.PRECHECK_FAILED,
            failedPreflight.begin(nowMs = 0L, preflightFits = false),
        )
        assertEquals(
            BubblePreviewAttachDecision.CANCELLED,
            failedPreflight.onGeometry(nowMs = 1L, trustworthy = true),
        )

        val flow = BubblePreviewFirstAttachFlow()
        assertEquals(
            BubblePreviewAttachDecision.AWAIT_COLLAPSE,
            flow.begin(nowMs = 0L, preflightFits = true),
        )
        assertEquals(
            BubblePreviewAttachDecision.AWAIT_COLLAPSE,
            flow.onGeometry(nowMs = 1L, trustworthy = true),
        )
        assertEquals(
            BubblePreviewAttachDecision.AWAIT_GEOMETRY,
            flow.onPanelCollapsed(nowMs = 2L),
        )
        assertEquals(
            BubblePreviewAttachDecision.AWAIT_GEOMETRY,
            flow.onGeometry(nowMs = 3L, trustworthy = false),
        )
        assertEquals(
            BubblePreviewAttachDecision.READY,
            flow.onGeometry(nowMs = 4L, trustworthy = true),
        )
        // READY is one-shot: a late duplicate frame cannot attach again.
        assertEquals(
            BubblePreviewAttachDecision.CANCELLED,
            flow.onGeometry(nowMs = 5L, trustworthy = true),
        )
    }

    @Test
    fun firstAttachHasFiniteAttemptsAndARealDeadline() {
        val attempts = BubblePreviewFirstAttachFlow(maxGeometryAttempts = 2)
        attempts.begin(nowMs = 0L, preflightFits = true)
        attempts.onPanelCollapsed(nowMs = 1L)
        assertEquals(
            BubblePreviewAttachDecision.AWAIT_GEOMETRY,
            attempts.onGeometry(nowMs = 2L, trustworthy = false),
        )
        assertEquals(
            BubblePreviewAttachDecision.TIMED_OUT,
            attempts.onGeometry(nowMs = 3L, trustworthy = false),
        )

        val deadline = BubblePreviewFirstAttachFlow(timeoutMs = 1_000L)
        deadline.begin(nowMs = 0L, preflightFits = true)
        deadline.onPanelCollapsed(nowMs = 10L)
        assertEquals(
            BubblePreviewAttachDecision.AWAIT_GEOMETRY,
            deadline.onDeadline(nowMs = 999L),
        )
        assertEquals(
            BubblePreviewAttachDecision.TIMED_OUT,
            deadline.onDeadline(nowMs = 1_000L),
        )
    }

    @Test
    fun canceledFirstAttachCanNeverReportReady() {
        val flow = BubblePreviewFirstAttachFlow()
        flow.begin(nowMs = 0L, preflightFits = true)
        flow.onPanelCollapsed(nowMs = 1L)
        assertEquals(BubblePreviewAttachDecision.CANCELLED, flow.cancel())
        assertEquals(
            BubblePreviewAttachDecision.CANCELLED,
            flow.onGeometry(nowMs = 2L, trustworthy = true),
        )
    }

    @Test
    fun panelHeightShrinksToTheRealVisibleFrameButKeepsControls() {
        assertEquals(
            792,
            BubblePreviewPanelGeometry.chatPanelHeightPx(
                panelTopPx = 100,
                baselineHeightPx = 800,
                visibleFrameBottomPx = 900,
                bottomMarginPx = 8,
                minimumControlsHeightPx = 200,
            ),
        )
        assertEquals(
            392,
            BubblePreviewPanelGeometry.chatPanelHeightPx(
                panelTopPx = 100,
                baselineHeightPx = 800,
                visibleFrameBottomPx = 500,
                bottomMarginPx = 8,
                minimumControlsHeightPx = 200,
            ),
        )
        // No room for the controls: caller keeps the baseline rather than
        // drawing half a button.
        assertNull(
            BubblePreviewPanelGeometry.chatPanelHeightPx(
                panelTopPx = 100,
                baselineHeightPx = 800,
                visibleFrameBottomPx = 300,
                bottomMarginPx = 8,
                minimumControlsHeightPx = 200,
            ),
        )
        assertNull(
            BubblePreviewPanelGeometry.chatPanelHeightPx(
                panelTopPx = -1,
                baselineHeightPx = 800,
                visibleFrameBottomPx = 900,
                bottomMarginPx = 8,
                minimumControlsHeightPx = 200,
            ),
        )
        assertNull(
            BubblePreviewPanelGeometry.chatPanelHeightPx(
                panelTopPx = 100,
                baselineHeightPx = 800,
                visibleFrameBottomPx = 900,
                bottomMarginPx = 8,
                minimumControlsHeightPx = 0,
            ),
        )
    }

    @Test
    fun collapsedOrbProjectionStaysInsideANonZeroDisplayAndMarksBothSides() {
        val display = BubblePreviewRect(120, 240, 1200, 2640)
        val right = BubblePreviewOrbProjection.collapsedButtonBounds(
            displayBounds = display,
            sideRight = true,
            orbYDp = 400f,
            orbSizeDp = 64f,
            density = 2f,
        )
        val left = BubblePreviewOrbProjection.collapsedButtonBounds(
            displayBounds = display,
            sideRight = false,
            orbYDp = 400f,
            orbSizeDp = 64f,
            density = 2f,
        )
        assertNotNull(right)
        assertNotNull(left)
        assertTrue(right!!.left >= display.left)
        assertTrue(right.top >= display.top)
        assertTrue(right.right <= display.right)
        assertTrue(right.bottom <= display.bottom)
        assertTrue(left!!.left >= display.left)
        assertTrue(left.right < right.right)
        assertEquals(right.width, left.width)
        assertEquals(right.height, left.height)
    }

}
