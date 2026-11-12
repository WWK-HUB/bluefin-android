package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePreviewUpdatePolicyTest {
    private fun placement(
        left: Int,
        top: Int,
        width: Int = 200,
        height: Int = 300,
        side: BubblePreviewSide = BubblePreviewSide.ABOVE,
        lines: Int = 4,
        includeUser: Boolean = true,
    ) = BubblePreviewPlacement(
        rect = BubblePreviewRect(left, top, left + width, top + height),
        side = side,
        slackPx = 10,
        plan = BubblePreviewLayoutPlan(
            windowWidthPx = width,
            windowHeightPx = height,
            bodyLines = lines,
            includeUserSummary = includeUser,
            bodyTextPx = 26f,
            labelTextPx = 24f,
            padHorizontalPx = 24f,
            padVerticalPx = 20f,
            zoneGapPx = 4f,
            actionGapPx = 16f,
            actionRowPx = 88,
            cornerRadiusPx = 24f,
            bodyLineHeightPx = 34f,
            nameLineHeightPx = 32f,
            userLineHeightPx = 34f,
            contentWidthPx = 152f,
        ),
    )

    @Test
    fun firstPlacementAppliesThenStaticFramesAreSuppressed() {
        val gate = BubblePreviewUpdateGate()
        val first = placement(100, 100)
        assertEquals(BubblePreviewUpdateDecision.Apply, gate.decide(1000L, first))
        gate.markApplied(1000L, first)

        repeat(20) {
            assertEquals(BubblePreviewUpdateDecision.Skip, gate.decide(1000L + it, first))
        }
    }

    @Test
    fun subTwoDpMovesAreSuppressedButSizeChangesBypassThatCheck() {
        val gate = BubblePreviewUpdateGate()
        val first = placement(100, 100)
        gate.markApplied(1000L, first)

        assertEquals(
            BubblePreviewUpdateDecision.Skip,
            gate.decide(1100L, placement(101, 101)),
        )
        assertEquals(
            BubblePreviewUpdateDecision.Apply,
            gate.decide(1100L, placement(100, 100, width = 220)),
        )
        gate.markApplied(1100L, placement(100, 100, width = 220))
        assertEquals(
            BubblePreviewUpdateDecision.Apply,
            gate.decide(1200L, placement(100, 100, width = 220, lines = 2)),
        )
    }

    @Test
    fun updatesAreLimitedToTwentyPerSecondEvenDuringContinuousDrag() {
        val gate = BubblePreviewUpdateGate()
        var applied = 0
        var pending = placement(100, 100)
        var now = 0L
        gate.markApplied(now, pending)
        applied += 1

        repeat(500) { index ->
            now += 5L
            val candidate = placement(100 + index * 5, 100)
            when (val decision = gate.decide(now, candidate)) {
                BubblePreviewUpdateDecision.Apply -> {
                    applied += 1
                    gate.markApplied(now, candidate)
                    pending = candidate
                }
                is BubblePreviewUpdateDecision.Schedule -> {
                    assertTrue(decision.delayMs in 1..50)
                }
                BubblePreviewUpdateDecision.Skip -> Unit
            }
        }
        // 500 frames at 5 ms cover 2.5 seconds => at most 50 updates.
        assertTrue("applied=$applied", applied <= 51)
        assertEquals(100 + 499 * 5, pending.rect.left)
    }

    @Test
    fun rapidFramesScheduleOnlyTheRemainingTimeToTheNextSlot() {
        val gate = BubblePreviewUpdateGate()
        gate.markApplied(1000L, placement(100, 100))
        val early = gate.decide(1010L, placement(150, 100))
        assertEquals(BubblePreviewUpdateDecision.Schedule(40L), early)
        assertEquals(
            BubblePreviewUpdateDecision.Apply,
            gate.decide(1050L, placement(150, 100)),
        )
    }

    @Test
    fun sideChangeIsAnUpdateEvenAtTheSameCoordinates() {
        val gate = BubblePreviewUpdateGate()
        val first = placement(100, 100, side = BubblePreviewSide.ABOVE)
        gate.markApplied(1000L, first)
        assertEquals(
            BubblePreviewUpdateDecision.Apply,
            gate.decide(1100L, placement(100, 100, side = BubblePreviewSide.BELOW)),
        )
    }

    @Test
    fun countersTrackWindowAndCallbackWorkWithoutBodyText() {
        val counters = BubblePreviewCounters()
        counters.added()
        counters.updated()
        counters.updated()
        counters.removed()
        counters.callbackDelivered()
        counters.taskScheduled()
        counters.metricsRead()
        counters.failure()
        val snapshot = counters.snapshot()
        assertEquals(1, snapshot.add)
        assertEquals(2, snapshot.update)
        assertEquals(1, snapshot.remove)
        assertEquals(1, snapshot.callbacks)
        assertEquals(1, snapshot.scheduled)
        assertEquals(1, snapshot.metricsReads)
        assertEquals(1, snapshot.failures)
    }
}