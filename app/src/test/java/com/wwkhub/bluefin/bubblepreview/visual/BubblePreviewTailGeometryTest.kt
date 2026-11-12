package com.wwkhub.bluefin.bubblepreview.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePreviewTailGeometryTest {
    private val eps = 0.001f

    private fun body(
        width: Float = 200f,
        height: Float = 100f,
        left: Float = 0f,
        top: Float = 0f,
    ): BubblePreviewTailRect = BubblePreviewTailRect(left, top, left + width, top + height)

    private fun request(
        direction: BubblePreviewTailDirection,
        body: BubblePreviewTailRect = body(),
        anchorX: Float = body.left + body.width / 2f,
        anchorY: Float = body.top + body.height / 2f,
        scale: Float = 0.85f,
        density: Float = 1f,
        hasUserSummary: Boolean = false,
    ): BubblePreviewTailRequest = BubblePreviewTailRequest(
        direction = direction,
        characterBody = body,
        anchorX = anchorX,
        anchorY = anchorY,
        characterScale = scale,
        density = density,
        hasUserSummary = hasUserSummary,
    )

    private fun assertRect(
        layout: BubblePreviewTailLayout,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val rect = layout.rect
        assertNotNull(rect)
        assertEquals(left, rect!!.left, eps)
        assertEquals(top, rect.top, eps)
        assertEquals(right, rect.right, eps)
        assertEquals(bottom, rect.bottom, eps)
    }

    private fun assertHidden(
        layout: BubblePreviewTailLayout,
        reason: BubblePreviewTailHiddenReason,
    ) {
        assertNull(layout.rect)
        assertFalse(layout.isVisible)
        assertEquals(reason, layout.hiddenReason)
    }

    @Test
    fun downAtCenteredAnchorUses12x6WithOneDpSeam() {
        val layout = BubblePreviewTailGeometry.resolve(request(BubblePreviewTailDirection.DOWN))

        assertTrue(layout.isVisible)
        assertEquals(BubblePreviewTailHiddenReason.NONE, layout.hiddenReason)
        assertEquals(12f, layout.tailWidthPx, eps)
        assertEquals(6f, layout.tailHeightPx, eps)
        assertEquals(1f, layout.seamPx, eps)
        assertEquals(24f, layout.cornerMarginPx, eps)
        assertRect(layout, 94f, 99f, 106f, 105f)
    }

    @Test
    fun downClampsAnchorToLeftCornerMargin() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, anchorX = 0f),
        )

        assertRect(layout, 24f, 99f, 36f, 105f)
    }

    @Test
    fun downClampsAnchorToRightCornerMargin() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, anchorX = 1000f),
        )

        assertRect(layout, 164f, 99f, 176f, 105f)
    }

    @Test
    fun downClampsAnchorWellOutsideEitherEdge() {
        val left = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, anchorX = -1_000f),
        )
        val right = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, anchorX = 10_000f),
        )

        assertRect(left, 24f, 99f, 36f, 105f)
        assertRect(right, 164f, 99f, 176f, 105f)
    }

    @Test
    fun upAtCenteredAnchorWithoutUserSummaryExtendsAboveBody() {
        val layout = BubblePreviewTailGeometry.resolve(request(BubblePreviewTailDirection.UP))

        assertTrue(layout.isVisible)
        assertEquals(6f, layout.tailHeightPx, eps)
        assertRect(layout, 94f, -5f, 106f, 1f)
    }

    @Test
    fun upTailIsHiddenWhenUserSummaryIsPresent() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.UP, hasUserSummary = true),
        )

        assertHidden(layout, BubblePreviewTailHiddenReason.USER_SUMMARY_BLOCKS_UP)
        assertEquals(12f, layout.tailWidthPx, eps)
        assertEquals(6f, layout.tailHeightPx, eps)
    }

    @Test
    fun userSummaryUpRuleWinsOverNoFitResult() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.UP,
                body = body(width = 10f),
                hasUserSummary = true,
            ),
        )

        assertHidden(layout, BubblePreviewTailHiddenReason.USER_SUMMARY_BLOCKS_UP)
    }

    @Test
    fun downTailStillWorksWithUserSummary() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, hasUserSummary = true),
        )

        assertRect(layout, 94f, 99f, 106f, 105f)
    }

    @Test
    fun leftAtCenteredAnchorUses6x12WithOneDpSeam() {
        val layout = BubblePreviewTailGeometry.resolve(request(BubblePreviewTailDirection.LEFT))

        assertTrue(layout.isVisible)
        assertEquals(6f, layout.tailWidthPx, eps)
        assertEquals(12f, layout.tailHeightPx, eps)
        assertRect(layout, -5f, 44f, 1f, 56f)
    }

    @Test
    fun leftClampsAnchorToTopCornerMargin() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.LEFT, anchorY = 0f),
        )

        assertRect(layout, -5f, 24f, 1f, 36f)
    }

    @Test
    fun leftClampsAnchorToBottomCornerMargin() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.LEFT, anchorY = 1000f),
        )

        assertRect(layout, -5f, 64f, 1f, 76f)
    }

    @Test
    fun rightAtCenteredAnchorExtendsFromBodyRightEdge() {
        val layout = BubblePreviewTailGeometry.resolve(request(BubblePreviewTailDirection.RIGHT))

        assertTrue(layout.isVisible)
        assertEquals(6f, layout.tailWidthPx, eps)
        assertEquals(12f, layout.tailHeightPx, eps)
        assertRect(layout, 199f, 44f, 205f, 56f)
    }

    @Test
    fun rightClampsAnchorToBothVerticalEdges() {
        val top = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.RIGHT, anchorY = -500f),
        )
        val bottom = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.RIGHT, anchorY = 500f),
        )

        assertRect(top, 199f, 24f, 205f, 36f)
        assertRect(bottom, 199f, 64f, 205f, 76f)
    }

    @Test
    fun noneDirectionIsHiddenAndNeedsNoGeometry() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.NONE,
                body = BubblePreviewTailRect(Float.NaN, 0f, 0f, 0f),
            ),
        )

        assertHidden(layout, BubblePreviewTailHiddenReason.NOT_REQUESTED)
        assertEquals(BubblePreviewTailDirection.NONE, layout.direction)
    }

    @Test
    fun minimumCharacterScaleUses085kTailDimensions() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, scale = 0.50f),
        )

        assertEquals(10.2f, layout.tailWidthPx, eps)
        assertEquals(5.1f, layout.tailHeightPx, eps)
        assertEquals(20.4f, layout.cornerMarginPx, eps)
        assertRect(layout, 94.9f, 99f, 105.1f, 104.1f)
    }

    @Test
    fun maximumCharacterScaleUses12kTailDimensions() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, scale = 1.30f),
        )

        assertEquals(14.4f, layout.tailWidthPx, eps)
        assertEquals(7.2f, layout.tailHeightPx, eps)
        assertEquals(28.8f, layout.cornerMarginPx, eps)
        assertRect(layout, 92.8f, 99f, 107.2f, 106.2f)
    }

    @Test
    fun scaleAboveRangeIsClampedToMaximumK() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, scale = 10f),
        )

        assertEquals(14.4f, layout.tailWidthPx, eps)
        assertEquals(28.8f, layout.cornerMarginPx, eps)
    }

    @Test
    fun scaleBelowRangeIsClampedToMinimumK() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, scale = 0.1f),
        )

        assertEquals(10.2f, layout.tailWidthPx, eps)
        assertEquals(20.4f, layout.cornerMarginPx, eps)
    }

    @Test
    fun densityScalesTailAndSeam() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, density = 2f),
        )

        assertEquals(24f, layout.tailWidthPx, eps)
        assertEquals(12f, layout.tailHeightPx, eps)
        assertEquals(2f, layout.seamPx, eps)
        assertEquals(48f, layout.cornerMarginPx, eps)
        assertRect(layout, 88f, 98f, 112f, 110f)
    }

    @Test
    fun seamIsOneDpAndNotScaledByK() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.DOWN,
                body = body(width = 400f),
                scale = 1.30f,
                density = 3f,
            ),
        )

        assertEquals(3f, layout.seamPx, eps)
        assertEquals(43.2f, layout.tailWidthPx, eps)
        assertEquals(21.6f, layout.tailHeightPx, eps)
        assertEquals(86.4f, layout.cornerMarginPx, eps)
    }

    @Test
    fun maximumScaleAndDensityStillProducesFiniteGeometry() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.RIGHT,
                body = body(width = 1_000f, height = 1_000f),
                scale = 1.30f,
                density = 8f,
            ),
        )

        assertTrue(layout.isVisible)
        assertEquals(57.6f, layout.tailWidthPx, eps)
        assertEquals(115.2f, layout.tailHeightPx, eps)
        assertEquals(8f, layout.seamPx, eps)
        assertEquals(230.4f, layout.cornerMarginPx, eps)
        assertTrue(layout.isFinite)
    }

    @Test
    fun nonzeroBodyOriginProducesSameCharacterRelativeRect() {
        val origin = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.DOWN,
                body = body(left = 1_000f, top = 500f),
                anchorX = 1_100f,
            ),
        )
        val centered = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN),
        )

        assertEquals(centered.rect, origin.rect)
    }

    @Test
    fun nonzeroAnchorOriginIsConvertedToBodyRelativeCoordinates() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.LEFT,
                body = body(left = 800f, top = 300f),
                anchorY = 350f,
            ),
        )

        assertRect(layout, -5f, 44f, 1f, 56f)
    }

    @Test
    fun nonzeroOriginsKeepAllFourDirectionsAligned() {
        val shiftedBody = body(left = -2_000f, top = 1_500f)
        BubblePreviewTailDirection.entries
            .filter { it != BubblePreviewTailDirection.NONE }
            .forEach { direction ->
                val base = BubblePreviewTailGeometry.resolve(request(direction))
                val shifted = BubblePreviewTailGeometry.resolve(
                    request(
                        direction,
                        body = shiftedBody,
                        anchorX = shiftedBody.left + shiftedBody.width / 2f,
                        anchorY = shiftedBody.top + shiftedBody.height / 2f,
                    ),
                )
                assertEquals(base.rect, shifted.rect)
            }
    }

    @Test
    fun verticalTailIsHiddenWhenBodyIsNarrowerThan60DpAtKOne() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, body = body(width = 59f)),
        )

        assertHidden(layout, BubblePreviewTailHiddenReason.NO_FIT)
        assertEquals(12f, layout.tailWidthPx, eps)
        assertEquals(6f, layout.tailHeightPx, eps)
    }

    @Test
    fun verticalTailFitsExactlyAtMinimumWidth() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.DOWN,
                body = body(width = 60f),
                anchorX = 30f,
            ),
        )

        assertTrue(layout.isVisible)
        assertRect(layout, 24f, 99f, 36f, 105f)
    }

    @Test
    fun horizontalTailIsHiddenWhenBodyIsShorterThan60DpAtKOne() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.LEFT, body = body(height = 59f)),
        )

        assertHidden(layout, BubblePreviewTailHiddenReason.NO_FIT)
        assertEquals(6f, layout.tailWidthPx, eps)
        assertEquals(12f, layout.tailHeightPx, eps)
    }

    @Test
    fun horizontalTailFitsExactlyAtMinimumHeight() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.RIGHT,
                body = body(height = 60f),
                anchorY = 30f,
            ),
        )

        assertTrue(layout.isVisible)
        assertRect(layout, 199f, 24f, 205f, 36f)
    }

    @Test
    fun noFitThresholdGrowsWithK() {
        val bodyAtThreshold = body(width = 60f)
        val minScale = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, body = bodyAtThreshold, scale = 0.50f),
        )
        val maxScale = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, body = bodyAtThreshold, scale = 1.30f),
        )

        assertTrue(minScale.isVisible)
        assertHidden(maxScale, BubblePreviewTailHiddenReason.NO_FIT)
    }

    @Test
    fun allVisibleDirectionsReturnFiniteValidRects() {
        listOf(
            BubblePreviewTailDirection.DOWN,
            BubblePreviewTailDirection.UP,
            BubblePreviewTailDirection.LEFT,
            BubblePreviewTailDirection.RIGHT,
        ).forEach { direction ->
            val layout = BubblePreviewTailGeometry.resolve(request(direction))
            assertTrue("$direction should be visible", layout.isVisible)
            assertTrue("$direction rect finite", layout.rect!!.isFinite())
            assertTrue("$direction rect valid", layout.rect.isValid())
            assertTrue("$direction layout finite", layout.isFinite)
        }
    }

    @Test
    fun downAndUpShareTheSameHorizontalClamp() {
        val down = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.DOWN, anchorX = 190f),
        )
        val up = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.UP, anchorX = 190f),
        )

        assertEquals(down.rect!!.left, up.rect!!.left, eps)
        assertEquals(down.rect.right, up.rect.right, eps)
    }

    @Test
    fun leftAndRightShareTheSameVerticalClamp() {
        val left = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.LEFT, anchorY = 95f),
        )
        val right = BubblePreviewTailGeometry.resolve(
            request(BubblePreviewTailDirection.RIGHT, anchorY = 95f),
        )

        assertEquals(left.rect!!.top, right.rect!!.top, eps)
        assertEquals(left.rect.bottom, right.rect.bottom, eps)
    }

    @Test
    fun nonFiniteBodyWidthIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.DOWN, body = body(width = Float.NaN)))
    }

    @Test
    fun nonFiniteBodyOriginIsRejected() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.DOWN,
                body = BubblePreviewTailRect(Float.NEGATIVE_INFINITY, 0f, 200f, 100f),
            ),
        )

        assertHidden(layout, BubblePreviewTailHiddenReason.INVALID_INPUT)
    }

    @Test
    fun nonFiniteAnchorXIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.DOWN, anchorX = Float.NaN))
    }

    @Test
    fun nonFiniteAnchorYIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.LEFT, anchorY = Float.POSITIVE_INFINITY))
    }

    @Test
    fun nonFiniteCharacterScaleIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.DOWN, scale = Float.NaN))
    }

    @Test
    fun nonFiniteDensityIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.DOWN, density = Float.NaN))
    }

    @Test
    fun zeroDensityIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.DOWN, density = 0f))
    }

    @Test
    fun negativeDensityIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.DOWN, density = -2f))
    }

    @Test
    fun densityAboveSafeRangeIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.DOWN, density = 8.01f))
    }

    @Test
    fun densityBelowSafeRangeIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.DOWN, density = 0.09f))
    }

    @Test
    fun boundaryDensitiesAreAccepted() {
        val largeBody = body(width = 10_000f, height = 10_000f)
        val min = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.DOWN,
                body = largeBody,
                density = BubblePreviewTailGeometry.MIN_DENSITY,
            ),
        )
        val max = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.DOWN,
                body = largeBody,
                density = BubblePreviewTailGeometry.MAX_DENSITY,
            ),
        )

        assertTrue(min.isVisible)
        assertTrue(max.isVisible)
    }

    @Test
    fun zeroWidthBodyIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.DOWN, body = body(width = 0f)))
    }

    @Test
    fun negativeWidthBodyIsRejected() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.DOWN,
                body = BubblePreviewTailRect(100f, 0f, 0f, 100f),
            ),
        )

        assertHidden(layout, BubblePreviewTailHiddenReason.INVALID_INPUT)
    }

    @Test
    fun hugeCoordinatesBeyondSafeRangeAreRejected() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.DOWN,
                body = BubblePreviewTailRect(0f, 0f, 2_000_000f, 100f),
                anchorX = 100f,
            ),
        )

        assertHidden(layout, BubblePreviewTailHiddenReason.INVALID_INPUT)
    }

    @Test
    fun hugeAnchorBeyondSafeRangeIsRejected() {
        assertInvalid(request(BubblePreviewTailDirection.DOWN, anchorX = 2_000_000f))
    }

    @Test
    fun floatMaxBodyIsRejectedRatherThanOverflowing() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.DOWN,
                body = BubblePreviewTailRect(0f, 0f, Float.MAX_VALUE, 100f),
            ),
        )

        assertHidden(layout, BubblePreviewTailHiddenReason.INVALID_INPUT)
    }

    @Test
    fun largeButSafeBodyStillReturnsFiniteRect() {
        val layout = BubblePreviewTailGeometry.resolve(
            request(
                BubblePreviewTailDirection.DOWN,
                body = body(width = 900_000f, height = 100f),
                anchorX = 450_000f,
            ),
        )

        assertTrue(layout.isVisible)
        assertTrue(layout.isFinite)
        assertTrue(layout.rect!!.isFinite())
    }

    @Test
    fun kForScaleMatchesSpecFormula() {
        assertEquals(0.85f, BubblePreviewTailGeometry.kForScale(0.50f), eps)
        assertEquals(1f, BubblePreviewTailGeometry.kForScale(0.85f), eps)
        assertEquals(1.20f, BubblePreviewTailGeometry.kForScale(1.30f), eps)
    }

    private fun assertInvalid(request: BubblePreviewTailRequest) {
        val layout = BubblePreviewTailGeometry.resolve(request)
        assertHidden(layout, BubblePreviewTailHiddenReason.INVALID_INPUT)
    }
}
