package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePreviewTextTest {
    @Test
    fun terminalChineseEnglishNewlinesAndCrLfAreExact() {
        val text = "\u4F60\u597D\uFF0CBluefin!\n\u7B2C\u4E8C\u884C\r\nThird"
        val preview = BubblePreviewText()

        val frame = preview.onSnapshot(text, isTerminal = true, nowMillis = 0L, flushImmediately = true)

        assertEquals(text, frame.visibleText)
        assertFalse(frame.overflow)
        assertFalse(frame.pendingGrapheme)
        assertFalse(frame.hasMore)
        assertTrue(frame.isComplete)
    }

    @Test
    fun streamingNeverRevealsACombiningMarkBeforeItsBaseIsComplete() {
        val preview = BubblePreviewText()

        preview.onSnapshot("e", isTerminal = false, nowMillis = 0L)
        assertEquals("", preview.onTick(33L).visibleText)

        preview.onSnapshot("e\u0301", isTerminal = false, nowMillis = 66L)
        assertEquals("", preview.onTick(99L).visibleText)

        preview.onSnapshot("e\u0301x", isTerminal = false, nowMillis = 132L)
        val frame = preview.onTick(165L)

        assertEquals("e\u0301", frame.visibleText)
        assertEquals(1, frame.visibleGraphemes)
    }

    @Test
    fun terminalCombiningMarkStaysOneGrapheme() {
        val preview = BubblePreviewText()

        val frame = preview.onSnapshot("e\u0301", isTerminal = true, nowMillis = 0L, flushImmediately = true)

        assertEquals("e\u0301", frame.visibleText)
        assertEquals(1, frame.visibleGraphemes)
    }

    @Test
    fun surrogatePairsAndSkinToneModifiersStayWhole() {
        val smile = "\uD83D\uDE00" // U+1F600
        val thumbWithTone = "\uD83D\uDC4D\uD83C\uDFFD" // U+1F44D U+1F3FD
        val preview = BubblePreviewText()

        preview.onSnapshot(smile, isTerminal = false, nowMillis = 0L)
        val held = preview.onTick(33L)
        assertEquals("", held.visibleText)
        assertFalse(held.visibleText.any { Character.isSurrogate(it) })

        preview.onSnapshot("$smile$thumbWithTone" + "x", isTerminal = false, nowMillis = 66L)

        // The interval while only a held cluster existed was idle, so the new
        // text starts with one fresh tick rather than spending banked time.
        val first = preview.onTick(99L)
        assertEquals(smile, first.visibleText)
        assertEquals(1, first.visibleGraphemes)

        val frame = preview.onTick(132L)
        assertEquals("$smile$thumbWithTone", frame.visibleText)
        assertEquals(2, frame.visibleGraphemes)
    }

    @Test
    fun zwjFamilyFlagsAndTagEmojiStayWhole() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        val china = "\uD83C\uDDE8\uD83C\uDDF3"
        val unitedStates = "\uD83C\uDDFA\uD83C\uDDF8"
        val england = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        val preview = BubblePreviewText()

        preview.onSnapshot("$family$china$unitedStates$england\n", isTerminal = false, nowMillis = 0L)
        val frame = preview.onTick(132L)

        assertEquals("$family$china$unitedStates$england", frame.visibleText)
        assertEquals(4, frame.visibleGraphemes)
    }

    @Test
    fun cumulativeSnapshotsAreDiffsAndNeverAppendTwice() {
        val preview = BubblePreviewText()

        preview.onSnapshot("\u4F60\u597D", isTerminal = false, nowMillis = 0L)
        assertEquals("", preview.frame().visibleText)
        assertEquals("\u4F60", preview.onTick(33L).visibleText)

        // A repeated cumulative snapshot must not append a second copy.
        preview.onSnapshot("\u4F60\u597D", isTerminal = false, nowMillis = 66L)
        assertEquals("\u4F60", preview.onTick(66L).visibleText)

        preview.onSnapshot("\u4F60\u597D\u4E16", isTerminal = false, nowMillis = 99L)
        assertEquals("\u4F60\u597D", preview.onTick(132L).visibleText)

        preview.onSnapshot("\u4F60\u597D\u4E16\u754C", isTerminal = false, nowMillis = 165L)
        assertEquals("\u4F60\u597D\u4E16", preview.onTick(198L).visibleText)
    }

    @Test
    fun twoThousandCharacterReplyIsCappedAt240Graphemes() {
        val text = "\u4E2D".repeat(2_000)
        val preview = BubblePreviewText()

        val start = preview.onSnapshot(text, isTerminal = true, nowMillis = 0L)
        assertEquals("", start.visibleText)

        val frame = preview.onTick(2_000L)

        assertEquals(240, frame.visibleGraphemes)
        assertEquals(240, frame.targetGraphemes)
        assertEquals("\u4E2D".repeat(240), frame.visibleText)
        assertTrue(frame.overflow)
        assertTrue(frame.hasMore)
        assertTrue(frame.isComplete)
    }

    @Test
    fun capCountsWholeGraphemesNotCodeUnits() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        val preview = BubblePreviewText()

        val frame = preview.onSnapshot(
            family.repeat(300),
            isTerminal = true,
            nowMillis = 0L,
            flushImmediately = true,
        )

        assertEquals(240, frame.visibleGraphemes)
        assertEquals(family.repeat(240), frame.visibleText)
        assertTrue(frame.overflow)
    }

    @Test
    fun terminalCatchupFinishesWithinTwoSeconds() {
        val text = "a".repeat(240)
        val preview = BubblePreviewText()

        preview.onSnapshot(text, isTerminal = true, nowMillis = 0L)
        val halfway = preview.onTick(1_000L)
        assertEquals(120, halfway.visibleGraphemes)
        assertTrue(halfway.isTyping)

        val done = preview.onTick(2_000L)
        assertEquals(240, done.visibleGraphemes)
        assertTrue(done.isComplete)
        assertFalse(done.isTyping)
        assertFalse(done.hasMore)
    }

    @Test
    fun smallTerminalReplyStillRevealsIncrementally() {
        val preview = BubblePreviewText()

        preview.onSnapshot("hello", isTerminal = true, nowMillis = 0L)
        assertEquals("", preview.frame().visibleText)
        assertEquals("h", preview.onTick(33L).visibleText)
        assertEquals("he", preview.onTick(66L).visibleText)
    }

    @Test
    fun streamingAdvancesAboutOneGraphemePerThirtyThreeMilliseconds() {
        val preview = BubblePreviewText()

        preview.onSnapshot("abcdef", isTerminal = false, nowMillis = 0L)
        assertEquals(0, preview.onTick(32L).visibleGraphemes)
        assertEquals(1, preview.onTick(33L).visibleGraphemes)
        assertEquals(2, preview.onTick(66L).visibleGraphemes)
        assertEquals(3, preview.onTick(99L).visibleGraphemes)
    }

    @Test
    fun streamingBacklogAboveSixtyJumpsDownToSixty() {
        val preview = BubblePreviewText()

        preview.onSnapshot("a".repeat(201), isTerminal = false, nowMillis = 0L)
        val frame = preview.onTick(33L)

        assertEquals(200, frame.targetGraphemes)
        assertEquals(141, frame.visibleGraphemes)
        assertTrue(frame.targetGraphemes - frame.visibleGraphemes <= 60)
    }

    @Test
    fun stopAndErrorFlushImmediately() {
        val text = "\u5DF2\u6536\u5230\u90E8\u5206\u5185\u5BB9"
        val stoppedPreview = BubblePreviewText()
        stoppedPreview.onSnapshot(text, isTerminal = false, nowMillis = 0L)
        stoppedPreview.onTick(33L)

        val stopped = stoppedPreview.onInterrupted(text, nowMillis = 66L)
        assertEquals(text, stopped.visibleText)
        assertTrue(stopped.isTerminal)
        assertTrue(stopped.isComplete)
        assertFalse(stopped.isTyping)
        assertFalse(stopped.hasMore)

        val failedPreview = BubblePreviewText()
        failedPreview.onSnapshot("\u51FA\u9519\u524D", isTerminal = false, nowMillis = 0L)
        val failed = failedPreview.onInterrupted("\u51FA\u9519\u524D", nowMillis = 33L)
        assertEquals("\u51FA\u9519\u524D", failed.visibleText)
    }

    @Test
    fun flushNowIncludesTheHeldFinalCluster() {
        val preview = BubblePreviewText()

        preview.onSnapshot("abc", isTerminal = false, nowMillis = 0L)
        assertEquals(2, preview.frame().targetGraphemes)

        val frame = preview.flushNow(33L)

        assertEquals("abc", frame.visibleText)
        assertEquals(3, frame.visibleGraphemes)
        assertTrue(frame.isComplete)
    }

    @Test
    fun resetForNewTurnClearsStateAndAcceptsShorterNewReply() {
        val preview = BubblePreviewText()
        preview.onSnapshot("a".repeat(300), isTerminal = true, nowMillis = 0L, flushImmediately = true)
        assertTrue(preview.frame().overflow)

        preview.resetForNewTurn()
        val empty = preview.frame()
        assertEquals("", empty.visibleText)
        assertEquals(0, empty.targetGraphemes)
        assertFalse(empty.overflow)
        assertFalse(empty.isTerminal)

        preview.onSnapshot("\u65B0\u7684\u4E00\u8F6E", isTerminal = true, nowMillis = 100L, flushImmediately = true)
        assertEquals("\u65B0\u7684\u4E00\u8F6E", preview.frame().visibleText)
    }

    @Test
    fun staleCumulativeSnapshotsCannotRollBackOrReviveTerminalPreview() {
        val preview = BubblePreviewText()
        preview.onSnapshot("abcdef", isTerminal = false, nowMillis = 0L)
        val before = preview.frame()

        val staleShort = preview.onSnapshot("abc", isTerminal = false, nowMillis = 33L)
        assertTrue(staleShort.staleInput)
        assertEquals(before.targetGraphemes, staleShort.targetGraphemes)
        assertEquals(before.visibleText, staleShort.visibleText)

        val staleChangedPrefix = preview.onSnapshot("abcdeg", isTerminal = false, nowMillis = 66L)
        assertTrue(staleChangedPrefix.staleInput)
        assertEquals(before.targetGraphemes, staleChangedPrefix.targetGraphemes)

        val terminalPreview = BubblePreviewText()
        terminalPreview.onSnapshot("final", isTerminal = true, nowMillis = 0L, flushImmediately = true)
        val settled = terminalPreview.frame()

        val lateNonTerminal = terminalPreview.onSnapshot("final", isTerminal = false, nowMillis = 100L)
        assertTrue(lateNonTerminal.staleInput)
        assertEquals(settled.visibleText, lateNonTerminal.visibleText)
        assertTrue(lateNonTerminal.isTerminal)
    }

    @Test
    fun cachedTerminalPreviewDoesNotReplayOnReopen() {
        val text = "\u5DF2\u7ECF\u5B8C\u6210\u7684\u56DE\u590D"
        val preview = BubblePreviewText()
        preview.onSnapshot(text, isTerminal = true, nowMillis = 0L)
        preview.onTick(2_000L)
        val settled = preview.frame()
        assertEquals(text, settled.visibleText)

        val reopened = preview.onSnapshot(text, isTerminal = true, nowMillis = 5_000L)
        assertFalse(reopened.textChanged)
        assertEquals(text, reopened.visibleText)
        assertEquals(settled.visibleGraphemes, reopened.visibleGraphemes)

        val afterTick = preview.onTick(5_033L)
        assertFalse(afterTick.textChanged)
        assertEquals(text, afterTick.visibleText)
    }

    @Test
    fun crlfIsHeldUntilBothCodeUnitsArePresent() {
        val preview = BubblePreviewText()

        preview.onSnapshot("a\r", isTerminal = false, nowMillis = 0L)
        assertEquals("a", preview.onTick(33L).visibleText)

        preview.onSnapshot("a\r\nb", isTerminal = false, nowMillis = 66L)
        val frame = preview.onTick(99L)

        assertEquals("a\r\n", frame.visibleText)
        assertEquals(2, frame.visibleGraphemes)
    }

    @Test
    fun pathologicalCombiningClusterIsBoundedWithoutPartialGrapheme() {
        val text = "o" + "k" + "\u0301".repeat(5_000)
        val preview = BubblePreviewText()

        val frame = preview.onSnapshot(text, isTerminal = true, nowMillis = 0L, flushImmediately = true)

        assertEquals("o", frame.visibleText)
        assertEquals(1, frame.targetGraphemes)
        assertTrue(frame.overflow)
        assertTrue(frame.hasMore)
        assertFalse(frame.visibleText.contains("\u0301"))
        assertFalse(frame.visibleText.endsWith("k"))
    }

    @Test
    fun terminalLoneSurrogateIsReplacedInsteadOfRevealedRaw() {
        val preview = BubblePreviewText()

        val frame = preview.onSnapshot("a\uD83D", isTerminal = true, nowMillis = 0L, flushImmediately = true)

        assertEquals("a\uFFFD", frame.visibleText)
        assertFalse(frame.visibleText.any { Character.isSurrogate(it) })
    }

    @Test
    fun streamingExactly240ClustersCountsTheHeldClusterWithoutFalseOverflow() {
        val preview = BubblePreviewText()

        val frame = preview.onSnapshot("a".repeat(240), isTerminal = false, nowMillis = 0L)

        assertEquals(239, frame.targetGraphemes)
        assertTrue(frame.pendingGrapheme)
        assertFalse(frame.overflow)
        assertTrue(frame.hasMore)
        assertRetainedWithinCap(frame)

        val flushed = preview.flushNow(33L)
        assertEquals(240, flushed.visibleGraphemes)
        assertEquals(240, flushed.targetGraphemes)
        assertFalse(flushed.pendingGrapheme)
        assertFalse(flushed.overflow)
        assertRetainedWithinCap(flushed)
    }

    @Test
    fun streaming241ClustersReportsActualOverflowWithoutRetainingCluster241() {
        val preview = BubblePreviewText()

        val frame = preview.onSnapshot("a".repeat(241), isTerminal = false, nowMillis = 0L)

        assertEquals(240, frame.targetGraphemes)
        assertFalse(frame.pendingGrapheme)
        assertTrue(frame.overflow)
        assertTrue(frame.hasMore)
        assertRetainedWithinCap(frame)
    }

    @Test
    fun terminalExactly240ClustersIsNotFalseOverflow() {
        val preview = BubblePreviewText()

        val frame = preview.onSnapshot(
            "a".repeat(240),
            isTerminal = true,
            nowMillis = 0L,
            flushImmediately = true,
        )

        assertEquals(240, frame.visibleGraphemes)
        assertEquals(240, frame.targetGraphemes)
        assertFalse(frame.pendingGrapheme)
        assertFalse(frame.overflow)
        assertFalse(frame.hasMore)
        assertTrue(frame.isComplete)
        assertRetainedWithinCap(frame)
    }

    @Test
    fun terminal241ClustersReportsOverflowAtTheCap() {
        val preview = BubblePreviewText()

        val frame = preview.onSnapshot(
            "a".repeat(241),
            isTerminal = true,
            nowMillis = 0L,
            flushImmediately = true,
        )

        assertEquals(240, frame.visibleGraphemes)
        assertEquals(240, frame.targetGraphemes)
        assertFalse(frame.pendingGrapheme)
        assertTrue(frame.overflow)
        assertTrue(frame.hasMore)
        assertRetainedWithinCap(frame)
    }

    @Test
    fun idleTicksWhileEmptyDoNotBurstNewlyArrivingStreamingText() {
        val preview = BubblePreviewText()

        preview.onSnapshot("", isTerminal = false, nowMillis = 0L)
        preview.onSnapshot("\u4F60\u597DX", isTerminal = false, nowMillis = 10_000L)

        assertEquals(0, preview.onTick(10_000L).visibleGraphemes)
        assertEquals("\u4F60", preview.onTick(10_033L).visibleText)
        assertEquals("\u4F60\u597D", preview.onTick(10_066L).visibleText)
    }

    @Test
    fun idleTicksWhileCaughtUpDoNotBurstNewlyArrivingText() {
        val preview = BubblePreviewText()

        preview.onSnapshot("ab", isTerminal = false, nowMillis = 0L)
        assertEquals("a", preview.onTick(33L).visibleText)
        assertEquals(1, preview.frame().visibleGraphemes)

        preview.onSnapshot("abc", isTerminal = false, nowMillis = 10_000L)

        assertEquals(1, preview.onTick(10_000L).visibleGraphemes)
        assertEquals(2, preview.onTick(10_033L).visibleGraphemes)
    }

    @Test
    fun idleTicksBeforeTerminalArrivalDoNotBurstTheTerminalPreview() {
        val preview = BubblePreviewText()

        preview.onSnapshot("", isTerminal = false, nowMillis = 0L)
        preview.onSnapshot("\u4F60\u597DX", isTerminal = true, nowMillis = 10_000L)

        assertEquals(0, preview.onTick(10_000L).visibleGraphemes)
        val firstTick = preview.onTick(10_033L)
        assertTrue(firstTick.visibleGraphemes <= 1)
    }

    @Test
    fun injectedSegmentationPolicySuppliesCandidateBoundaries() {
        val pairPolicy = SegmentationBoundaryPolicy { _, start, limit ->
            if (start >= limit) -1 else minOf(limit, start + 2)
        }
        val preview = BubblePreviewText(segmentationPolicy = pairPolicy)

        val frame = preview.onSnapshot("abcd", isTerminal = true, nowMillis = 0L, flushImmediately = true)

        assertEquals("abcd", frame.visibleText)
        assertEquals(2, frame.visibleGraphemes)
    }

    @Test
    fun breakIteratorBaselinePlusSafetyMergeKeepsComplexEmojiWhole() {
        val keycap = "1\uFE0F\u20E3"
        val technologistWithTone = "\uD83D\uDC68\uD83C\uDFFD\u200D\uD83D\uDCBB"
        val scotland = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        val text = "$keycap$technologistWithTone$scotland e\u0301"
        val preview = BubblePreviewText()

        val frame = preview.onSnapshot(text, isTerminal = true, nowMillis = 0L, flushImmediately = true)

        assertEquals(text, frame.visibleText)
        assertEquals(5, frame.visibleGraphemes)
        assertFalse(frame.overflow)
    }

    @Test
    fun throwingBoundaryPolicyFallsBackToTheSafeScanner() {
        val throwingPolicy = SegmentationBoundaryPolicy { _, _, _ ->
            throw IllegalStateException("platform iterator failed")
        }
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        val text = "$family x"
        val preview = BubblePreviewText(segmentationPolicy = throwingPolicy)

        val frame = preview.onSnapshot(text, isTerminal = true, nowMillis = 0L, flushImmediately = true)

        assertEquals(text, frame.visibleText)
        assertEquals(3, frame.visibleGraphemes)
    }

    @Test
    fun aPolicyBoundaryThatSplitsASurrogatePairIsRepaired() {
        val oneCodeUnitPolicy = SegmentationBoundaryPolicy { _, start, limit ->
            if (start >= limit) -1 else start + 1
        }
        val smile = "\uD83D\uDE00"
        val preview = BubblePreviewText(segmentationPolicy = oneCodeUnitPolicy)

        val frame = preview.onSnapshot("$smile!", isTerminal = true, nowMillis = 0L, flushImmediately = true)

        assertEquals("$smile!", frame.visibleText)
        assertEquals(2, frame.visibleGraphemes)
        assertFalse(frame.visibleText.contains('\uFFFD'))
    }

    @Test
    fun longSnapshotStillRejectsAChangeInsideThePreviewRelevantPrefix() {
        val preview = BubblePreviewText()
        preview.onSnapshot("a".repeat(5_000), isTerminal = true, nowMillis = 0L, flushImmediately = true)

        val changed = "b" + "a".repeat(4_999)
        val frame = preview.onSnapshot(changed, isTerminal = true, nowMillis = 1L)

        assertTrue(frame.staleInput)
        assertEquals(240, frame.visibleGraphemes)
    }

    @Test
    fun longSnapshotMayIgnoreAChangeOutsideThePreviewRelevantPrefix() {
        val preview = BubblePreviewText()
        preview.onSnapshot("a".repeat(5_000), isTerminal = true, nowMillis = 0L, flushImmediately = true)

        val changedBeyondCap = buildString {
            append("a".repeat(241))
            append('b')
            append("a".repeat(5_000 - 242))
        }
        val frame = preview.onSnapshot(changedBeyondCap, isTerminal = true, nowMillis = 1L)

        assertFalse(frame.staleInput)
        assertEquals(240, frame.visibleGraphemes)
        assertTrue(frame.overflow)
    }

    @Test
    fun frozenCapDoesNotResegmentOrRebuildGrowingSnapshots() {
        var boundaryCalls = 0
        val countingPolicy = SegmentationBoundaryPolicy { _, start, limit ->
            boundaryCalls += 1
            if (start >= limit) -1 else minOf(limit, start + 1)
        }
        val preview = BubblePreviewText(
            maxGraphemes = 8,
            segmentationPolicy = countingPolicy,
        )

        preview.onSnapshot("a".repeat(30), isTerminal = false, nowMillis = 0L)
        val callsAtCap = boundaryCalls
        assertTrue(callsAtCap > 0)
        assertEquals(8, preview.frame().targetGraphemes)
        assertTrue(preview.frame().overflow)

        val visible = preview.onTick(1_000L).visibleText
        assertEquals("a".repeat(8), visible)

        preview.onSnapshot("a".repeat(40), isTerminal = false, nowMillis = 1_033L)
        preview.onSnapshot("a".repeat(80), isTerminal = false, nowMillis = 1_066L)

        assertEquals(callsAtCap, boundaryCalls)
        assertEquals("a".repeat(8), preview.frame().visibleText)
        assertEquals(8, preview.frame().targetGraphemes)
        assertTrue(preview.frame().overflow)
    }

    @Test
    fun frozenCapKeepsComplexEmojiWholeAndNeverDuplicatesClusters() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        var boundaryCalls = 0
        val countingPolicy = SegmentationBoundaryPolicy { _, start, limit ->
            boundaryCalls += 1
            if (start >= limit) -1 else minOf(limit, start + 1)
        }
        val preview = BubblePreviewText(
            maxGraphemes = 4,
            segmentationPolicy = countingPolicy,
        )

        preview.onSnapshot(family.repeat(10), isTerminal = false, nowMillis = 0L)
        val callsAtCap = boundaryCalls
        val visible = preview.onTick(1_000L).visibleText

        assertEquals(family.repeat(4), visible)
        assertEquals(4, preview.frame().targetGraphemes)
        assertTrue(preview.frame().overflow)

        preview.onSnapshot(family.repeat(12), isTerminal = false, nowMillis = 1_033L)

        assertEquals(callsAtCap, boundaryCalls)
        assertEquals(family.repeat(4), preview.frame().visibleText)
        assertFalse(preview.frame().visibleText.contains('\uFFFD'))
    }

    @Test
    fun frozenCapStillRejectsShorterOrChangedPrefixAndCatchesUpTerminal() {
        var boundaryCalls = 0
        val countingPolicy = SegmentationBoundaryPolicy { _, start, limit ->
            boundaryCalls += 1
            if (start >= limit) -1 else minOf(limit, start + 1)
        }
        val preview = BubblePreviewText(
            maxGraphemes = 8,
            segmentationPolicy = countingPolicy,
        )

        preview.onSnapshot("a".repeat(30), isTerminal = false, nowMillis = 0L)
        val callsAtCap = boundaryCalls

        assertTrue(preview.onSnapshot("a".repeat(10), isTerminal = false, nowMillis = 1L).staleInput)
        assertTrue(
            preview.onSnapshot("b" + "a".repeat(29), isTerminal = false, nowMillis = 2L).staleInput,
        )
        assertEquals(callsAtCap, boundaryCalls)
        assertEquals(8, preview.frame().targetGraphemes)
        assertTrue(preview.frame().overflow)

        val terminal = preview.onSnapshot("a".repeat(30), isTerminal = true, nowMillis = 3L)
        assertFalse(terminal.staleInput)
        assertEquals(0, terminal.visibleGraphemes)
        assertEquals(callsAtCap, boundaryCalls)

        val done = preview.onTick(2_003L)
        assertEquals(8, done.visibleGraphemes)
        assertEquals("a".repeat(8), done.visibleText)
        assertTrue(done.isComplete)
        assertEquals(callsAtCap, boundaryCalls)
    }

    @Test
    fun pendingFinalClusterBecomesTheCapWithoutDuplicatingIt() {
        val preview = BubblePreviewText()

        preview.onSnapshot("a".repeat(240), isTerminal = false, nowMillis = 0L)
        assertEquals(239, preview.frame().targetGraphemes)
        assertTrue(preview.frame().pendingGrapheme)

        preview.onSnapshot("a".repeat(241), isTerminal = false, nowMillis = 33L)
        val capped = preview.frame()
        assertEquals(240, capped.targetGraphemes)
        assertTrue(capped.overflow)
        assertFalse(capped.pendingGrapheme)

        val flushed = preview.flushNow(66L)
        assertEquals("a".repeat(240), flushed.visibleText)
        assertEquals(240, flushed.visibleGraphemes)
    }

    private fun assertRetainedWithinCap(frame: BubblePreviewFrame, max: Int = 240) {
        val pending = if (frame.pendingGrapheme) 1 else 0
        assertTrue(frame.targetGraphemes + pending <= max)
        assertTrue(frame.visibleGraphemes + pending <= max)
    }
}
