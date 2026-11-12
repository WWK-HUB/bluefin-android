package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePreviewPlacementPolicyTest {
    private val safe = BubblePreviewRect(0, 0, 1000, 2000)
    private val character = BubblePreviewRect(400, 1000, 600, 1400)
    private val anchor = BubblePreviewPoint(500f, 1000f)

    private fun plan(width: Int = 200, height: Int = 300, lines: Int = 4) =
        BubblePreviewLayoutPlan(
            windowWidthPx = width,
            windowHeightPx = height,
            bodyLines = lines,
            includeUserSummary = true,
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
        )

    private fun place(
        plans: List<BubblePreviewLayoutPlan> = listOf(plan()),
        previous: BubblePreviewPlacement? = null,
        pending: BubblePreviewPendingSide? = null,
        now: Long = 0L,
        characterBounds: BubblePreviewRect = character,
        orbBounds: BubblePreviewRect? = null,
    ) = BubblePreviewPlacementPolicy.place(
        safeBounds = safe,
        characterBounds = characterBounds,
        anchor = BubblePreviewPoint(
            (characterBounds.left + characterBounds.right) / 2f,
            characterBounds.top.toFloat(),
        ),
        orbBounds = orbBounds,
        plans = plans,
        previous = previous,
        pendingSide = pending,
        nowElapsedMs = now,
        density = 1f,
    )

    @Test
    fun prefersDirectlyAboveTheCharacter() {
        val decision = place()
        val placement = decision.placement!!
        assertEquals(BubblePreviewSide.ABOVE, placement.side)
        assertEquals(character.top - 6 - 300, placement.rect.top)
        assertTrue(placement.rect.bottom <= character.top - 6)
        assertTrue(placement.rect.left >= safe.left)
        assertTrue(placement.rect.right <= safe.right)
    }

    @Test
    fun fallsBackBelowWhenThereIsNoRoomAbove() {
        val highCharacter = BubblePreviewRect(400, 220, 600, 600)
        val decision = place(characterBounds = highCharacter)
        assertEquals(BubblePreviewSide.BELOW, decision.placement!!.side)
        assertTrue(decision.placement.rect.top >= highCharacter.bottom + 6)
    }

    @Test
    fun fallsBackToTheCenterFacingSideWhenVerticalCandidatesCollide() {
        // A short safe area plus a tall character leaves no vertical room. The
        // orb is below, so the side candidate is the first collision-free one.
        val shortSafeCharacter = BubblePreviewRect(700, 100, 760, 700)
        val decision = BubblePreviewPlacementPolicy.place(
            safeBounds = BubblePreviewRect(0, 0, 1000, 800),
            characterBounds = shortSafeCharacter,
            anchor = BubblePreviewPoint(730f, 100f),
            orbBounds = BubblePreviewRect(0, 0, 1000, 120),
            plans = listOf(plan(width = 200, height = 250)),
            previous = null,
            pendingSide = null,
            nowElapsedMs = 0L,
            density = 1f,
        )
        val placement = decision.placement!!
        assertEquals(BubblePreviewSide.LEFT, placement.side)
        assertTrue(placement.rect.right <= shortSafeCharacter.left - 6)
    }

    @Test
    fun rejectsCandidatesThatCannotAvoidTheOrb() {
        val orb = BubblePreviewRect(-10, -10, 1010, 2010)
        assertNull(place(orbBounds = orb).placement)
    }

    @Test
    fun compactPlanIsTriedOnlyAfterTheFullPlanHasNoCandidate() {
        val full = plan(height = 600)
        val compact = plan(height = 150, lines = 2)
        val decision = BubblePreviewPlacementPolicy.place(
            safeBounds = BubblePreviewRect(0, 0, 600, 700),
            characterBounds = BubblePreviewRect(200, 100, 400, 300),
            anchor = BubblePreviewPoint(300f, 100f),
            orbBounds = null,
            plans = listOf(full, compact),
            previous = null,
            pendingSide = null,
            nowElapsedMs = 0L,
            density = 1f,
        )
        assertEquals(2, decision.placement!!.plan.bodyLines)
        assertTrue(decision.placement.rect.top >= 306)
    }

    @Test
    fun noCandidateReturnsNoSpaceInsteadOfLooping() {
        val hugeCharacter = BubblePreviewRect(-100, -100, 1100, 2100)
        assertNull(place(characterBounds = hugeCharacter).placement)
    }

    @Test
    fun sideHysteresisRequiresBothSixteenDpAndTwoHundredMilliseconds() {
        val previous = BubblePreviewPlacement(
            rect = BubblePreviewRect(700, 100, 900, 400),
            side = BubblePreviewSide.RIGHT,
            slackPx = 0,
            plan = plan(),
        )
        val first = place(previous = previous, now = 1000L)
        assertEquals(BubblePreviewSide.RIGHT, first.placement!!.side)
        assertEquals(BubblePreviewSide.ABOVE, first.pendingSide!!.side)
        assertEquals(1000L, first.pendingSide.sinceElapsedMs)

        val tooEarly = place(previous = previous, pending = first.pendingSide, now = 1199L)
        assertEquals(BubblePreviewSide.RIGHT, tooEarly.placement!!.side)

        val stable = place(previous = previous, pending = first.pendingSide, now = 1200L)
        assertEquals(BubblePreviewSide.ABOVE, stable.placement!!.side)
        assertNull(stable.pendingSide)
    }

    @Test
    fun currentSideIsKeptButItsPositionIsRecomputedAgainstTheCurrentAnchor() {
        val previous = BubblePreviewPlacement(
            rect = BubblePreviewRect(0, 100, 200, 400),
            side = BubblePreviewSide.LEFT,
            slackPx = 390,
            plan = plan(),
        )
        val movedCharacter = BubblePreviewRect(400, 1200, 600, 1600)
        val decision = place(
            previous = previous,
            now = 0L,
            characterBounds = movedCharacter,
        )
        val retained = decision.placement!!
        assertEquals(BubblePreviewSide.LEFT, retained.side)
        // The side is sticky, but the rect follows the current character rather
        // than freezing the old absolute position.
        assertNotSame(previous, retained)
        assertTrue(retained.rect.right <= movedCharacter.left - 6)
        assertTrue(retained.rect.top > previous.rect.top)
    }

    @Test
    fun aRecomputedPreviousSideThatNoLongerFitsSwitchesImmediately() {
        val edgeCharacter = BubblePreviewRect(800, 600, 980, 1000)
        val previous = BubblePreviewPlacement(
            rect = BubblePreviewRect(780, 650, 980, 950),
            side = BubblePreviewSide.RIGHT,
            slackPx = 0,
            plan = plan(),
        )
        val decision = place(
            previous = previous,
            now = 10L,
            characterBounds = edgeCharacter,
        )
        assertEquals(BubblePreviewSide.ABOVE, decision.placement!!.side)
    }

    @Test
    fun movingAnchorKeepsTheRetainedSidePositionCurrent() {
        val firstCharacter = BubblePreviewRect(400, 900, 600, 1300)
        val first = place(characterBounds = firstCharacter).placement!!
        assertEquals(BubblePreviewSide.ABOVE, first.side)
        val movedCharacter = BubblePreviewRect(400, 1100, 600, 1500)
        val second = place(
            previous = first,
            now = 50L,
            characterBounds = movedCharacter,
        ).placement!!
        assertEquals(BubblePreviewSide.ABOVE, second.side)
        assertEquals(movedCharacter.top - 6 - second.plan.windowHeightPx, second.rect.top)
        assertTrue(second.rect.top > first.rect.top)
    }

    @Test
    fun invalidGeometryReturnsNoPlacement() {
        val decision = BubblePreviewPlacementPolicy.place(
            safeBounds = BubblePreviewRect(0, 0, 0, 0),
            characterBounds = character,
            anchor = BubblePreviewPoint(Float.NaN, 0f),
            orbBounds = null,
            plans = listOf(plan()),
            previous = null,
            pendingSide = null,
            nowElapsedMs = 0L,
            density = 1f,
        )
        assertNull(decision.placement)
    }

    @Test
    fun candidateCountAndPlanCapStayBounded() {
        assertEquals(4, BubblePreviewPlacementPolicy.MAX_CANDIDATES_PER_PLAN)
        assertEquals(2, BubblePreviewPlacementPolicy.MAX_PLANS)
        assertEquals(16, BubblePreviewPlacementPolicy.SIDE_HYSTERESIS_DP)
        assertEquals(200L, BubblePreviewPlacementPolicy.SIDE_HYSTERESIS_MS)
        assertNotNull(place().placement)
    }
}