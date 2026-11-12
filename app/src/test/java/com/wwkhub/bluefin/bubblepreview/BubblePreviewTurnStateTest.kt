package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePreviewTurnStateTest {

    @Test
    fun beginTurnBoundsSummaryAndRoleToSafeGraphemeClusters() {
        val state = BubblePreviewTurnState()
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"

        val snapshot = state.beginTurn(
            token = 7L,
            userSummary = family.repeat(100),
            roleName = family.repeat(50),
            nowMillis = 0L,
        )

        assertEquals(7L, snapshot.token)
        assertEquals(BubbleTurnPhase.THINKING, snapshot.phase)
        assertEquals(BubbleVisibleState.THINKING, snapshot.visibleState)
        assertEquals(BubbleTurnStatus.NONE, snapshot.status)
        assertEquals(family.repeat(80), snapshot.userSummary)
        assertEquals(family.repeat(40), snapshot.roleName)
        assertEquals("", snapshot.visibleText)
        assertTrue(snapshot.isVisible)
    }

    @Test
    fun cumulativeSnapshotsAreDiffsAndNeverAppendedTwice() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)

        val first = state.onSnapshot(1L, "\u4F60\u597D", isTerminal = false, nowMillis = 0L)
        assertEquals(BubbleTurnPhase.STREAMING, first.phase)
        assertEquals(BubbleVisibleState.TYPING, first.visibleState)
        assertEquals("", first.visibleText)
        assertEquals("\u4F60", state.onTick(1L, 33L).visibleText)

        val repeated = state.onSnapshot(1L, "\u4F60\u597D", isTerminal = false, nowMillis = 66L)
        assertEquals("\u4F60", repeated.visibleText)
        assertEquals("\u4F60", state.onTick(1L, 66L).visibleText)

        state.onSnapshot(1L, "\u4F60\u597D\u4E16\u754C", isTerminal = false, nowMillis = 99L)
        assertEquals("\u4F60\u597D", state.onTick(1L, 132L).visibleText)
    }

    @Test
    fun completionStartsHoldAtVisualCatchUpThenCollapsesAndHidesAtExactBoundaries() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        state.onSnapshot(1L, "hello", isTerminal = true, nowMillis = 0L)

        val (holdAt, hold) = tickUntilHold(state, 1L)
        assertEquals(BubbleTurnPhase.COMPLETE, hold.phase)
        assertEquals(BubbleVisibleState.HOLD, hold.visibleState)
        assertEquals("hello", hold.visibleText)

        assertEquals(BubbleVisibleState.HOLD, state.onTick(1L, holdAt + 7_999L).visibleState)
        val collapsed = state.onTick(1L, holdAt + 8_000L)
        assertEquals(BubbleVisibleState.COLLAPSED, collapsed.visibleState)
        assertEquals("hello", collapsed.visibleText)
        assertEquals(BubbleTurnPhase.COMPLETE, collapsed.phase)

        assertEquals(BubbleVisibleState.COLLAPSED, state.onTick(1L, holdAt + 37_999L).visibleState)
        val hidden = state.onTick(1L, holdAt + 38_000L)
        assertEquals(BubbleVisibleState.HIDDEN, hidden.visibleState)
        assertTrue(hidden.suppressed)
        assertEquals(BubbleSuppressionReason.TIMEOUT, hidden.suppressionReason)
    }

    @Test
    fun repeatedTerminalSnapshotDoesNotReplayTextOrExtendTheHold() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        state.onSnapshot(1L, "hello", isTerminal = true, nowMillis = 0L)
        val (holdAt, hold) = tickUntilHold(state, 1L)

        val repeated = state.onSnapshot(1L, "hello", isTerminal = true, nowMillis = holdAt + 1_000L)
        assertEquals(hold.visibleText, repeated.visibleText)
        assertEquals(hold.visibleGraphemes, repeated.visibleGraphemes)
        assertEquals(BubbleVisibleState.HOLD, repeated.visibleState)
        assertFalse(repeated.textChanged)

        assertEquals(BubbleVisibleState.HOLD, state.onTick(1L, holdAt + 7_999L).visibleState)
        assertEquals(BubbleVisibleState.COLLAPSED, state.onTick(1L, holdAt + 8_000L).visibleState)
    }

    @Test
    fun stopFlushesReceivedTextImmediatelyAndMarksStopped() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        state.onSnapshot(1L, "\u5DF2\u6536\u5230\u90E8\u5206", isTerminal = false, nowMillis = 0L)
        assertEquals("", state.snapshot(0L).visibleText)

        val stopped = state.onStop(1L, "\u5DF2\u6536\u5230\u90E8\u5206", nowMillis = 100L)
        assertEquals(BubbleTurnPhase.STOPPED, stopped.phase)
        assertEquals(BubbleTurnStatus.STOPPED, stopped.status)
        assertEquals(BubbleVisibleState.HOLD, stopped.visibleState)
        assertEquals("\u5DF2\u6536\u5230\u90E8\u5206", stopped.visibleText)
        assertFalse(stopped.hasMore)

        assertEquals(BubbleVisibleState.HOLD, state.onTick(1L, 8_099L).visibleState)
        assertEquals(BubbleVisibleState.COLLAPSED, state.onTick(1L, 8_100L).visibleState)
        assertEquals(BubbleVisibleState.HIDDEN, state.onTick(1L, 38_100L).visibleState)
    }

    @Test
    fun stopWithNoReceivedTextShowsAnEmptyStoppedHoldWithoutFakeContent() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)

        val stopped = state.onStop(1L, "", nowMillis = 0L)

        assertEquals(BubbleTurnStatus.STOPPED, stopped.status)
        assertEquals(BubbleVisibleState.HOLD, stopped.visibleState)
        assertEquals("", stopped.visibleText)
    }

    @Test
    fun errorFlushesPartialTextThenShowsFailureChipAndHidesAtBoundaries() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        state.onSnapshot(1L, "\u5DF2\u6536\u5230", isTerminal = false, nowMillis = 0L)

        val error = state.onError(1L, "\u5DF2\u6536\u5230", nowMillis = 100L)
        assertEquals(BubbleTurnPhase.FAILED, error.phase)
        assertEquals(BubbleTurnStatus.FAILED, error.status)
        assertEquals(BubbleVisibleState.ERROR, error.visibleState)
        assertEquals("\u5DF2\u6536\u5230", error.visibleText)
        assertFalse(error.isFailureChip)

        assertEquals(BubbleVisibleState.ERROR, state.onTick(1L, 8_099L).visibleState)
        val chip = state.onTick(1L, 8_100L)
        assertEquals(BubbleVisibleState.COLLAPSED, chip.visibleState)
        assertTrue(chip.isFailureChip)
        assertEquals("\u5DF2\u6536\u5230", chip.visibleText)
        assertEquals(BubbleSuppressionReason.TIMEOUT, state.onTick(1L, 38_100L).suppressionReason)
        assertEquals(BubbleVisibleState.HIDDEN, state.onTick(1L, 38_100L).visibleState)
    }

    @Test
    fun stopAndErrorFromTerminalPhasesCannotReviveOrOverwriteStatus() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        state.onError(1L, "partial", nowMillis = 0L)

        val stop = state.onStop(1L, "late", nowMillis = 10L)
        assertEquals(BubbleTurnStatus.FAILED, stop.status)
        assertEquals(BubbleVisibleState.ERROR, stop.visibleState)
        assertEquals("partial", stop.visibleText)
    }

    @Test
    fun collapsedClickExpandsCachedTextWithFreshHoldAndNoReplay() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        state.onSnapshot(1L, "cached reply", isTerminal = true, nowMillis = 0L)
        val (holdAt, hold) = tickUntilHold(state, 1L)
        val collapsed = state.onTick(1L, holdAt + 8_000L)
        assertEquals(BubbleVisibleState.COLLAPSED, collapsed.visibleState)

        val clickAt = holdAt + 20_000L
        val expanded = state.onCollapsedClick(1L, clickAt)
        assertEquals(BubbleVisibleState.HOLD, expanded.visibleState)
        assertEquals(hold.visibleText, expanded.visibleText)
        assertEquals(expanded.targetGraphemes, expanded.visibleGraphemes)
        assertFalse(expanded.textChanged)

        assertEquals(BubbleVisibleState.HOLD, state.onTick(1L, clickAt + 7_999L).visibleState)
        assertEquals(BubbleVisibleState.COLLAPSED, state.onTick(1L, clickAt + 8_000L).visibleState)
    }

    @Test
    fun everySuppressionReasonPermanentlyBlocksTheOldToken() {
        val state = BubblePreviewTurnState()
        BubbleSuppressionReason.entries.forEachIndexed { index, reason ->
            val token = index.toLong()
            state.beginTurn(token, "summary", "role", 0L)
            state.onSnapshot(token, "received", isTerminal = false, nowMillis = 0L)

            val suppressed = state.suppress(reason, nowMillis = 1L)
            assertEquals(BubbleVisibleState.HIDDEN, suppressed.visibleState)
            assertTrue(suppressed.suppressed)
            assertEquals(reason, suppressed.suppressionReason)

            val lateSnapshot = state.onSnapshot(token, "late reply", isTerminal = true, nowMillis = 2L)
            val lateTick = state.onTick(token, 100L)
            assertEquals(BubbleVisibleState.HIDDEN, lateSnapshot.visibleState)
            assertEquals(BubbleVisibleState.HIDDEN, lateTick.visibleState)
            assertEquals(token, lateTick.token)
        }
    }

    @Test
    fun tokenIdentityRejectsOldSnapshotsAndTicks() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "old summary", "Old", 0L)
        val current = state.beginTurn(2L, "new summary", "New", 100L)

        val oldSnapshot = state.onSnapshot(1L, "old reply", isTerminal = true, nowMillis = 200L)
        val oldTick = state.onTick(1L, 300L)

        for (snapshot in listOf(oldSnapshot, oldTick)) {
            assertEquals(2L, snapshot.token)
            assertEquals("new summary", snapshot.userSummary)
            assertEquals("New", snapshot.roleName)
            assertEquals("", snapshot.visibleText)
            assertEquals(BubbleVisibleState.THINKING, snapshot.visibleState)
        }
        assertEquals(BubbleVisibleState.THINKING, current.visibleState)

        val accepted = state.onSnapshot(2L, "new reply", isTerminal = false, nowMillis = 400L)
        assertEquals(BubbleTurnPhase.STREAMING, accepted.phase)
    }

    @Test
    fun sameTextWithNewTokenStartsFreshAndDoesNotReplayCachedText() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "same summary", "same role", 0L)
        state.onSnapshot(1L, "same text", isTerminal = true, nowMillis = 0L)
        val (holdAt, _) = tickUntilHold(state, 1L)
        state.onTick(1L, holdAt + 8_000L)

        val fresh = state.beginTurn(2L, "same summary", "same role", holdAt + 20_000L)
        assertEquals(BubbleVisibleState.THINKING, fresh.visibleState)
        assertEquals("", fresh.visibleText)

        val terminal = state.onSnapshot(2L, "same text", isTerminal = true, nowMillis = holdAt + 20_000L)
        assertEquals("", terminal.visibleText)
        assertEquals(BubbleVisibleState.TYPING, terminal.visibleState)
        assertEquals(0, terminal.visibleGraphemes)

        val next = state.onTick(2L, holdAt + 20_033L)
        assertEquals(1, next.visibleGraphemes)
        assertFalse(next.visibleText == "same text")
    }

    @Test
    fun longReplyIsCappedAt240RetainedClustersWithoutKeepingTheWholeSnapshot() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        val longReply = "\u4E2D".repeat(5_000)

        state.onSnapshot(1L, longReply, isTerminal = true, nowMillis = 0L)
        val caughtUp = state.onTick(1L, 2_000L)

        assertEquals(240, caughtUp.visibleGraphemes)
        assertEquals(240, caughtUp.targetGraphemes)
        assertEquals("\u4E2D".repeat(240), caughtUp.visibleText)
        assertTrue(caughtUp.overflow)
        assertTrue(caughtUp.hasMore)
        assertTrue(caughtUp.retainedReplyGraphemes <= BubblePreviewTurnState.MAX_REPLY_GRAPHEMES)

        val repeated = state.onSnapshot(1L, longReply, isTerminal = true, nowMillis = 3_000L)
        assertEquals(caughtUp.visibleText, repeated.visibleText)
        assertEquals(240, repeated.retainedReplyGraphemes)
    }

    @Test
    fun geometrySuspensionHidesPresentationButPreservesAbsoluteHoldDeadlines() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        state.onSnapshot(1L, "hello", isTerminal = true, nowMillis = 0L)
        val (holdAt, _) = tickUntilHold(state, 1L)

        val hidden = state.setGeometryAvailable(false, holdAt + 1_000L)
        assertEquals(BubbleVisibleState.HIDDEN, hidden.visibleState)
        assertTrue(hidden.geometrySuspended)
        assertEquals("hello", hidden.visibleText)

        assertEquals(BubbleVisibleState.HIDDEN, state.snapshot(holdAt + 5_000L).visibleState)
        val resumed = state.setGeometryAvailable(true, holdAt + 6_000L)
        assertEquals(BubbleVisibleState.HOLD, resumed.visibleState)

        state.setGeometryAvailable(false, holdAt + 6_000L)
        val resumedAgain = state.setGeometryAvailable(true, holdAt + 6_000L)
        assertEquals(BubbleVisibleState.HOLD, resumedAgain.visibleState)
        assertEquals(BubbleVisibleState.HOLD, state.onTick(1L, holdAt + 7_999L).visibleState)
        assertEquals(BubbleVisibleState.COLLAPSED, state.onTick(1L, holdAt + 8_000L).visibleState)
    }

    @Test
    fun geometrySuspensionDoesNotReviveACollapseDeadlineAlreadyElapsed() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        state.onSnapshot(1L, "hello", isTerminal = true, nowMillis = 0L)
        val (holdAt, _) = tickUntilHold(state, 1L)

        state.setGeometryAvailable(false, holdAt + 1_000L)
        val reopened = state.setGeometryAvailable(true, holdAt + 38_000L)

        assertEquals(BubbleVisibleState.HIDDEN, reopened.visibleState)
        assertTrue(reopened.suppressed)
        assertEquals(BubbleSuppressionReason.TIMEOUT, reopened.suppressionReason)
    }

    @Test
    fun suppressedTurnIsReplacedOnlyByANewExplicitBegin() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "old", "Old", 0L)
        state.suppress(BubbleSuppressionReason.CLEAR, 1L)

        val fresh = state.beginTurn(2L, "fresh", "New", 2L)
        assertFalse(fresh.suppressed)
        assertEquals(null, fresh.suppressionReason)
        assertEquals(BubbleVisibleState.THINKING, fresh.visibleState)
        assertEquals("fresh", fresh.userSummary)
        assertEquals("New", fresh.roleName)
        assertEquals("", fresh.visibleText)
        assertNotNull(fresh.token)
    }

    @Test
    fun nextDeadlineExposesOnlyTheAbsoluteHoldAndChipBoundaries() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        assertNull(state.nextDeadlineMillis(0L))

        state.onSnapshot(1L, "hello", isTerminal = true, nowMillis = 0L)
        // Still typing the finite preview: backlog owns the 33 ms ticker.
        assertNull(state.nextDeadlineMillis(0L))

        val (holdAt, hold) = tickUntilHold(state, 1L)
        assertEquals(BubbleVisibleState.HOLD, hold.visibleState)
        assertEquals(
            holdAt + BubblePreviewTurnState.HOLD_MILLIS,
            state.nextDeadlineMillis(holdAt),
        )
        assertEquals(
            holdAt + BubblePreviewTurnState.HOLD_MILLIS,
            state.nextDeadlineMillis(holdAt - 1L),
        )

        val collapsed = state.onTick(1L, holdAt + 8_000L)
        assertEquals(BubbleVisibleState.COLLAPSED, collapsed.visibleState)
        assertEquals(
            holdAt + 8_000L + BubblePreviewTurnState.COLLAPSED_MILLIS,
            state.nextDeadlineMillis(holdAt + 8_000L),
        )

        val hidden = state.onTick(
            1L,
            holdAt + 8_000L + BubblePreviewTurnState.COLLAPSED_MILLIS,
        )
        assertEquals(BubbleVisibleState.HIDDEN, hidden.visibleState)
        assertNull(state.nextDeadlineMillis(holdAt + 38_000L))
    }

    @Test
    fun failureChipDeadlineIsTheOnlyRemainingDeadlineAfterTheErrorWindow() {
        val state = BubblePreviewTurnState()
        state.beginTurn(1L, "我", "蓝鳍", 0L)
        state.onError(1L, "partial", nowMillis = 0L)
        assertEquals(
            BubblePreviewTurnState.ERROR_MILLIS,
            state.nextDeadlineMillis(0L),
        )
        val chip = state.onTick(1L, BubblePreviewTurnState.ERROR_MILLIS)
        assertEquals(BubbleVisibleState.COLLAPSED, chip.visibleState)
        assertTrue(chip.isFailureChip)
        assertEquals(
            BubblePreviewTurnState.ERROR_MILLIS + BubblePreviewTurnState.FAILURE_CHIP_MILLIS,
            state.nextDeadlineMillis(BubblePreviewTurnState.ERROR_MILLIS),
        )
    }

    private fun tickUntilHold(
        state: BubblePreviewTurnState,
        token: Long,
    ): Pair<Long, BubbleTurnSnapshot> {
        var now = 0L
        while (now <= 10_000L) {
            now += 33L
            val snapshot = state.onTick(token, now)
            if (snapshot.visibleState == BubbleVisibleState.HOLD) return now to snapshot
        }
        throw AssertionError("turn did not reach HOLD")
    }
}
