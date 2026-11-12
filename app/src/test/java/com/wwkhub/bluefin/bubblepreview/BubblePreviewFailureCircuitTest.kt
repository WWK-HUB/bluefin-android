package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePreviewFailureCircuitTest {
    @Test
    fun startsClosedAndDoesNotTripBeforeThreeIndependentAttempts() {
        val circuit = BubblePreviewFailureCircuit()

        assertFalse(circuit.isTripped())
        assertEquals(0, circuit.consecutiveFailures())
        assertEquals(3, circuit.snapshot().failureThreshold)
        assertEquals(2_000L, circuit.snapshot().sustainedSuccessMs)

        val first = circuit.beginDisplayAttempt(10L)
        assertTrue(first != null)
        first!!.recordFailure(11L)
        assertFalse(circuit.isTripped())
        assertEquals(1, circuit.consecutiveFailures())

        val second = circuit.beginDisplayAttempt(20L)
        assertTrue(second != null)
        second!!.recordFailure(21L)
        assertFalse(circuit.isTripped())
        assertEquals(2, circuit.consecutiveFailures())
    }

    @Test
    fun threeIndependentFailedAttemptsTripTheProcessCircuit() {
        val circuit = BubblePreviewFailureCircuit()
        repeat(3) { index ->
            val attempt = circuit.beginDisplayAttempt(index.toLong())
            assertTrue(attempt != null)
            attempt!!.recordFailure(index.toLong())
        }

        assertTrue(circuit.isTripped())
        assertEquals(3, circuit.consecutiveFailures())
        assertNull(circuit.beginDisplayAttempt(4L))
        assertTrue(circuit.isTripped())
    }

    @Test
    fun duplicateFailureReportsOnOneAttemptCountOnlyOnce() {
        val circuit = BubblePreviewFailureCircuit()
        val first = circuit.beginDisplayAttempt(0L)!!
        first.recordFailure(1L)
        first.recordFailure(2L)
        first.recordFailure(3L)
        assertEquals(1, circuit.consecutiveFailures())

        val second = circuit.beginDisplayAttempt(4L)!!
        second.recordFailure(5L)
        second.recordFailure(6L)
        assertEquals(2, circuit.consecutiveFailures())
        assertFalse(circuit.isTripped())
    }

    @Test
    fun successShorterThanTwoSecondsDoesNotResetTheStreak() {
        val circuit = BubblePreviewFailureCircuit()
        circuit.beginDisplayAttempt(0L)!!.recordFailure(1L)
        circuit.beginDisplayAttempt(2L)!!.recordFailure(3L)

        val visible = circuit.beginDisplayAttempt(4L)!!
        assertTrue(visible.markVisible(1_000L))
        assertFalse(visible.confirmSustainedSuccess(2_999L))
        assertEquals(2, circuit.consecutiveFailures())

        visible.recordFailure(2_999L)
        assertTrue(circuit.isTripped())
    }

    @Test
    fun sustainedSuccessForTwoSecondsResetsTheStreak() {
        val circuit = BubblePreviewFailureCircuit()
        circuit.beginDisplayAttempt(0L)!!.recordFailure(1L)
        circuit.beginDisplayAttempt(2L)!!.recordFailure(3L)

        val visible = circuit.beginDisplayAttempt(4L)!!
        assertTrue(visible.markVisible(1_000L))
        assertTrue(visible.confirmSustainedSuccess(3_000L))
        assertEquals(0, circuit.consecutiveFailures())
        assertFalse(circuit.isTripped())
    }

    @Test
    fun failureAfterSustainedSuccessStartsAFreshStreak() {
        val circuit = BubblePreviewFailureCircuit()
        circuit.beginDisplayAttempt(0L)!!.recordFailure(1L)
        circuit.beginDisplayAttempt(2L)!!.recordFailure(3L)
        val visible = circuit.beginDisplayAttempt(4L)!!
        visible.markVisible(0L)
        assertTrue(visible.confirmSustainedSuccess(2_000L))

        visible.recordFailure(2_500L)
        assertEquals(1, circuit.consecutiveFailures())
        assertFalse(circuit.isTripped())

        circuit.beginDisplayAttempt(2_600L)!!.recordFailure(2_600L)
        circuit.beginDisplayAttempt(2_700L)!!.recordFailure(2_700L)
        assertTrue(circuit.isTripped())
    }

    @Test
    fun aNewAttemptAfterTwoVisibleSecondsAlsoSettlesTheSuccess() {
        val circuit = BubblePreviewFailureCircuit()
        circuit.beginDisplayAttempt(0L)!!.recordFailure(1L)
        circuit.beginDisplayAttempt(2L)!!.recordFailure(3L)

        val visible = circuit.beginDisplayAttempt(4L)!!
        visible.markVisible(100L)
        circuit.beginDisplayAttempt(2_100L)

        assertEquals(0, circuit.consecutiveFailures())
        assertFalse(circuit.isTripped())
    }

    @Test
    fun aNewAttemptBeforeTwoSecondsDiscardsThePendingSuccess() {
        val circuit = BubblePreviewFailureCircuit()
        circuit.beginDisplayAttempt(0L)!!.recordFailure(1L)
        circuit.beginDisplayAttempt(2L)!!.recordFailure(3L)

        val visible = circuit.beginDisplayAttempt(4L)!!
        visible.markVisible(0L)
        val next = circuit.beginDisplayAttempt(1_999L)!!
        assertEquals(2, circuit.consecutiveFailures())

        next.recordFailure(2_000L)
        assertTrue(circuit.isTripped())
    }

    @Test
    fun aStaleAttemptCannotResetOrFailTheNewGeneration() {
        val circuit = BubblePreviewFailureCircuit()
        circuit.beginDisplayAttempt(0L)!!.recordFailure(1L)
        circuit.beginDisplayAttempt(2L)!!.recordFailure(3L)

        val stale = circuit.beginDisplayAttempt(4L)!!
        stale.markVisible(0L)
        val current = circuit.beginDisplayAttempt(1_000L)!!
        assertFalse(stale.confirmSustainedSuccess(5_000L))
        stale.recordFailure(5_000L)
        assertEquals(2, circuit.consecutiveFailures())
        assertFalse(circuit.isTripped())

        current.recordFailure(5_100L)
        assertTrue(circuit.isTripped())
    }

    @Test
    fun earlyVisibilityEndingDoesNotAccumulateHiddenTimeAsSuccess() {
        val circuit = BubblePreviewFailureCircuit()
        circuit.beginDisplayAttempt(0L)!!.recordFailure(1L)
        circuit.beginDisplayAttempt(2L)!!.recordFailure(3L)

        // Visible for one second, then hidden. The ten-second reopen must not
        // treat the hidden gap as part of a continuous two-second success.
        val early = circuit.beginDisplayAttempt(0L)!!
        assertTrue(early.markVisible(1_000L))
        assertTrue(early.endVisibility(2_000L))
        assertFalse(early.confirmSustainedSuccess(10_000L))

        val reopened = circuit.beginDisplayAttempt(10_000L)!!
        assertFalse(reopened.confirmSustainedSuccess(10_000L))
        assertEquals(2, circuit.consecutiveFailures())
        assertFalse(circuit.isTripped())
    }

    @Test
    fun healthyTwoSecondVisibilityEndingResetsTheStreak() {
        val circuit = BubblePreviewFailureCircuit()
        circuit.beginDisplayAttempt(0L)!!.recordFailure(1L)
        circuit.beginDisplayAttempt(2L)!!.recordFailure(3L)

        val healthy = circuit.beginDisplayAttempt(0L)!!
        assertTrue(healthy.markVisible(1_000L))
        assertTrue(healthy.endVisibility(3_000L))

        assertEquals(0, circuit.consecutiveFailures())
        assertFalse(circuit.isTripped())
        assertTrue(healthy.confirmSustainedSuccess(3_000L))
    }

    @Test
    fun reopeningTheCurrentAttemptStartsAFreshVisibilityWindow() {
        val circuit = BubblePreviewFailureCircuit()
        circuit.beginDisplayAttempt(0L)!!.recordFailure(1L)
        circuit.beginDisplayAttempt(2L)!!.recordFailure(3L)

        val attempt = circuit.beginDisplayAttempt(0L)!!
        assertTrue(attempt.markVisible(1_000L))
        assertTrue(attempt.endVisibility(2_000L))
        assertFalse(attempt.confirmSustainedSuccess(10_000L))

        assertTrue(attempt.markVisible(10_000L))
        assertFalse(attempt.confirmSustainedSuccess(11_999L))
        assertTrue(attempt.confirmSustainedSuccess(12_000L))
        assertEquals(0, circuit.consecutiveFailures())
    }

    @Test
    fun endedOrStaleTokenCannotReportForANewerAttempt() {
        val circuit = BubblePreviewFailureCircuit()
        val stale = circuit.beginDisplayAttempt(0L)!!
        assertTrue(stale.markVisible(0L))
        assertTrue(stale.endVisibility(1_000L))

        val current = circuit.beginDisplayAttempt(10_000L)!!
        assertFalse(stale.confirmSustainedSuccess(12_000L))
        stale.recordFailure(12_000L)
        assertFalse(stale.endAttempt(12_001L))
        assertEquals(0, circuit.consecutiveFailures())
        assertFalse(circuit.isTripped())

        current.recordFailure(12_002L)
        assertEquals(1, circuit.consecutiveFailures())
        assertFalse(circuit.isTripped())
    }

    @Test
    fun aTrippedCircuitCannotBeClearedByLateSuccessOrReopening() {
        val circuit = BubblePreviewFailureCircuit()
        circuit.beginDisplayAttempt(0L)!!.recordFailure(1L)
        circuit.beginDisplayAttempt(2L)!!.recordFailure(3L)
        val last = circuit.beginDisplayAttempt(4L)!!
        last.recordFailure(5L)
        assertTrue(circuit.isTripped())

        last.markVisible(10_000L)
        assertFalse(last.confirmSustainedSuccess(20_000L))
        assertFalse(last.endVisibility(20_001L))
        assertFalse(last.endAttempt(20_002L))
        last.recordFailure(20_003L)
        assertNull(circuit.beginDisplayAttempt(20_004L))
        assertTrue(circuit.isTripped())
        assertEquals(3, circuit.consecutiveFailures())
    }

    @Test
    fun processSessionKeepsTheTripAcrossWindowLikeCloseAndReopen() {
        val first = BubblePreviewProcessSession.forExplicitUserChat()
        repeat(3) { index ->
            first.beginDisplayAttempt(index.toLong())!!.recordFailure(index.toLong())
        }
        assertTrue(first.isTripped())

        // A new OverlayWindow / hide-show cycle only asks the process holder
        // again; it must receive the same tripped circuit.
        val afterReopen = BubblePreviewProcessSession.forExplicitUserChat()
        assertSame(first, afterReopen)
        assertTrue(afterReopen.isTripped())
        assertNull(afterReopen.beginDisplayAttempt(10L))
    }
}