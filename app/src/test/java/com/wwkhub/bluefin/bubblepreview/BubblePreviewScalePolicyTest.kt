package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

class BubblePreviewScalePolicyTest {
    private fun request(
        scale: Float,
        safeWidth: Int = 1080,
        safeHeight: Int = 2000,
        fontScale: Float = 1f,
        hasUser: Boolean = true,
    ) = BubblePreviewScaleRequest(
        characterScale = scale,
        density = 2f,
        scaledDensity = 2f * fontScale,
        safeWidthPx = safeWidth,
        safeHeightPx = safeHeight,
        hasUserSummary = hasUser,
    )

    @Test
    fun bodyAndLabelMetricsFollowTheSpecifiedThreeScalePoints() {
        assertEquals(0f, BubblePreviewScalePolicy.tForScale(0.50f))
        assertEquals(0.4375f, BubblePreviewScalePolicy.tForScale(0.85f), 0.0001f)
        assertEquals(1f, BubblePreviewScalePolicy.tForScale(1.30f), 0.0001f)
        assertEquals(0.85f, BubblePreviewScalePolicy.kForScale(0.50f))
        assertEquals(1f, BubblePreviewScalePolicy.kForScale(0.85f))
        assertEquals(1.20f, BubblePreviewScalePolicy.kForScale(1.30f))

        assertEquals(11f, BubblePreviewScalePolicy.bodyTextSp(0.50f), 0.0001f)
        assertEquals(13.1875f, BubblePreviewScalePolicy.bodyTextSp(0.85f), 0.0001f)
        assertEquals(16f, BubblePreviewScalePolicy.bodyTextSp(1.30f), 0.0001f)
        assertEquals(160f, BubblePreviewScalePolicy.preferredWidthDp(0.50f), 0.0001f)
        assertEquals(212.5f, BubblePreviewScalePolicy.preferredWidthDp(0.85f), 0.0001f)
        assertEquals(280f, BubblePreviewScalePolicy.preferredWidthDp(1.30f), 0.0001f)
    }

    @Test
    fun defaultPlanReservesFourTextLinesAndTheFixedActionRow() {
        val plan = BubblePreviewScalePolicy.plan(request(0.85f))!!
        assertEquals(4, plan.bodyLines)
        assertTrue(plan.includeUserSummary)
        assertEquals(5, plan.totalTextLines)
        assertEquals(2f * 44f, plan.actionRowPx.toFloat(), 0.001f)
        // The final integer bbox includes the seam-overlapped 5 dp tail budget.
        val expectedHeight = 200.5625f * 2f
        assertEquals(expectedHeight, plan.windowHeightPx.toFloat(), 1.5f)
        assertTrue(plan.windowWidthPx <= (1080 * 0.72f).toInt())
        assertEquals(212.5f * 2f, plan.windowWidthPx.toFloat(), 1.0f)
    }

    @Test
    fun tallScaleDropsFromFourToTwoBodyLines() {
        val plan = BubblePreviewScalePolicy.plan(request(1.30f))!!
        assertEquals(2, plan.bodyLines)
        assertTrue(plan.includeUserSummary)
        assertTrue(plan.windowHeightPx <= 220f * 2f)
    }

    @Test
    fun fontScaleFifteenRetriesTwoLines() {
        val plan = BubblePreviewScalePolicy.plan(request(0.85f, fontScale = 1.5f))!!
        assertEquals(2, plan.bodyLines)
        assertTrue(plan.includeUserSummary)
        assertTrue(plan.windowHeightPx <= 220f * 2f)
    }

    @Test
    fun landscapeShortSafeHeightFallsBackToRecord() {
        assertNull(BubblePreviewScalePolicy.plan(request(0.85f, safeWidth = 2400, safeHeight = 700)))
    }

    @Test
    fun userSummaryCanBeDroppedBeforeGivingUp() {
        // 410px safe height caps the window at 114.8px, which cannot hold the
        // default four-line plan or two-line-with-user plan.
        val plan = BubblePreviewScalePolicy.plan(request(0.85f, safeHeight = 410))
        assertNull(plan)

        val loose = BubblePreviewScalePolicy.plan(request(0.85f, safeHeight = 1000))
        assertNotNull(loose)
        assertEquals(2, loose!!.bodyLines)
        assertTrue(!loose.includeUserSummary)
    }

    @Test
    fun widthHonoursTheSeventyTwoPercentSafeLimit() {
        val plan = BubblePreviewScalePolicy.plan(
            request(1.30f, safeWidth = 500, safeHeight = 2000),
        )!!
        assertEquals((500 * 0.72f).toInt(), plan.windowWidthPx)
        assertTrue(plan.windowWidthPx <= (500 * 0.72f).toInt())
    }

    @Test
    fun narrowSafeWidthWithoutRoomForActionsReturnsNoPlan() {
        assertNull(BubblePreviewScalePolicy.plan(request(0.85f, safeWidth = 160)))
    }

    @Test
    fun realFontMetricsWinWhenTallerThanTheReferenceLine() {
        val base = request(0.85f)
        val tall = BubblePreviewScalePolicy.plan(
            base.copy(realBodyLinePx = 60f, realNameLinePx = 50f),
        )!!
        assertTrue(tall.windowHeightPx > BubblePreviewScalePolicy.plan(base)!!.windowHeightPx)
        assertEquals(60f, tall.bodyLineHeightPx)
        assertEquals(50f, tall.nameLineHeightPx)
    }

    @Test
    fun fallbackPlansAreBoundedToTwoAndPreferFullThenCompact() {
        val plans = BubblePreviewScalePolicy.fallbackPlans(request(0.85f))
        assertTrue(plans.size in 1..2)
        assertEquals(4, plans.first().bodyLines)
        if (plans.size == 2) {
            assertEquals(2, plans[1].bodyLines)
            assertTrue(plans[1].windowHeightPx <= plans[0].windowHeightPx)
        }
    }

    @Test
    fun invalidInputsReturnNoPlanInsteadOfThrowing() {
        assertNull(BubblePreviewScalePolicy.plan(request(Float.NaN)))
        assertNull(BubblePreviewScalePolicy.plan(request(0.85f, safeWidth = 0)))
        assertNull(BubblePreviewScalePolicy.plan(request(0.85f, safeHeight = 0)))
        assertNull(
            BubblePreviewScalePolicy.plan(
                request(0.85f).copy(density = 0f),
            ),
        )
    }

    /**
     * Ported from the independent audit. The pre-rasterised float height may be
     * inside the cap while `ceil()` crosses it, so every final integer pixel
     * dimension must be checked against the floored limit.
     */
    @Test
    fun randomSizingNeverCrossesTheFlooredPixelCaps() {
        val random = Random(71203)
        repeat(10_000) {
            val density = 0.75f + random.nextFloat() * 3.25f
            val safeWidth = (240 + random.nextInt(840)) * density
            val safeHeight = (320 + random.nextInt(1400)) * density
            val scaleRequest = BubblePreviewScaleRequest(
                characterScale = 0.50f + random.nextFloat() * 0.80f,
                density = density,
                scaledDensity = density * (1f + random.nextFloat() * 0.5f),
                safeWidthPx = safeWidth.toInt(),
                safeHeightPx = safeHeight.toInt(),
                hasUserSummary = random.nextBoolean(),
            )
            val maxWidth = floor(safeWidth.toDouble() * 0.72).toInt()
            val heightCap = floor(
                min(220.0 * density, safeHeight.toDouble() * 0.28),
            ).toInt()
            BubblePreviewScalePolicy.fallbackPlans(scaleRequest).forEach { plan ->
                assertTrue(
                    "width ${plan.windowWidthPx} exceeds $maxWidth for $scaleRequest",
                    plan.windowWidthPx <= maxWidth,
                )
                assertTrue(
                    "height ${plan.windowHeightPx} exceeds $heightCap for $scaleRequest",
                    plan.windowHeightPx <= heightCap,
                )
                assertTrue(plan.totalTextLines <= 5)
                assertTrue(plan.actionRowPx >= ceil(44.0 * density).toInt())
            }
        }
    }
}