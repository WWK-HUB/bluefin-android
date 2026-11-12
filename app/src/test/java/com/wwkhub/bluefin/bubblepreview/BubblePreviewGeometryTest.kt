package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePreviewGeometryTest {
    @Test
    fun rectanglesExposePositiveSizeAndRejectInvertedBounds() {
        val rect = BubblePreviewRect(10, 20, 110, 220)
        assertTrue(rect.isValid())
        assertEquals(100, rect.width)
        assertEquals(200, rect.height)
        assertFalse(BubblePreviewRect(10, 20, 10, 220).isValid())
        assertFalse(BubblePreviewRect(10, 20, 110, 20).isValid())
    }

    @Test
    fun rectangleIntersectionAndInsetAreFiniteAndOnePass() {
        val display = BubblePreviewRect(0, 0, 1_000, 2_000)
        val safe = display.inset(12, 24, 36, 48)
        assertEquals(BubblePreviewRect(12, 24, 964, 1_952), safe)

        val visible = BubblePreviewRect(0, 100, 1_000, 1_500)
        assertEquals(
            BubblePreviewRect(12, 100, 964, 1_500),
            safe!!.intersect(visible),
        )
        assertNull(display.inset(600, 0, 600, 0))
        assertNull(display.inset(-1, 0, 0, 0))
    }

    @Test
    fun safeBoundsApplyInsetsAndMarginOnceThenIntersectRoot() {
        val snapshot = BubblePreviewGeometrySnapshot(
            hostGeneration = 7L,
            geometryGeneration = 3L,
            sampledAtElapsedMs = 123_456L,
            displayBounds = BubblePreviewRect(0, 0, 1_000, 2_000),
            systemInsets = BubblePreviewInsets(left = 10, top = 20, right = 30, bottom = 40),
            rootVisibleBounds = BubblePreviewRect(0, 100, 1_000, 1_500),
            characterBounds = BubblePreviewRect(100, 400, 300, 800),
            headAnchor = BubblePreviewPoint(200f, 400f),
            orbWindowBounds = BubblePreviewRect(900, 1_200, 980, 1_280),
        )
        assertTrue(snapshot.isUsable())
        // Bars are inset once, the root clips the top edge, and only then does
        // the 5 px margin apply to every edge of that final intersection.
        assertEquals(
            BubblePreviewRect(15, 105, 965, 1_495),
            snapshot.safeScreenBounds(marginPx = 5),
        )
        assertNull(snapshot.safeScreenBounds(marginPx = -1))
    }

    @Test
    fun safetyMarginSurvivesRootAndImeClippingOnAllFourEdges() {
        val snapshot = BubblePreviewGeometrySnapshot(
            hostGeneration = 1L,
            geometryGeneration = 1L,
            sampledAtElapsedMs = 1L,
            displayBounds = BubblePreviewRect(0, 0, 1_000, 2_000),
            systemInsets = BubblePreviewInsets(left = 10, top = 20, right = 30, bottom = 40),
            rootVisibleBounds = BubblePreviewRect(100, 100, 900, 1_400),
        )
        assertTrue(snapshot.isUsable())
        assertEquals(
            BubblePreviewRect(112, 112, 888, 1_388),
            snapshot.safeScreenBounds(marginPx = 12),
        )
    }

    @Test
    fun unrepresentableRectangleDimensionsAreRejectedInsteadOfZeroWidth() {
        val horizontalOverflow = BubblePreviewRect(Int.MIN_VALUE, 0, Int.MAX_VALUE, 100)
        assertFalse(horizontalOverflow.isValid())
        assertEquals(0, horizontalOverflow.width)
        assertEquals(100, horizontalOverflow.height)

        val verticalOverflow = BubblePreviewRect(0, Int.MIN_VALUE, 100, Int.MAX_VALUE)
        assertFalse(verticalOverflow.isValid())
        assertEquals(100, verticalOverflow.width)
        assertEquals(0, verticalOverflow.height)

        val largestRepresentable = BubblePreviewRect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(largestRepresentable.isValid())
        assertEquals(Int.MAX_VALUE, largestRepresentable.width)
        assertEquals(Int.MAX_VALUE, largestRepresentable.height)

        assertNull(horizontalOverflow.intersect(largestRepresentable))
        assertNull(horizontalOverflow.inset(0, 0, 0, 0))
    }

    @Test
    fun safeBoundsRejectUnrepresentableDisplayBeforeAnySubtraction() {
        val snapshot = BubblePreviewGeometrySnapshot(
            hostGeneration = 0L,
            geometryGeneration = 0L,
            sampledAtElapsedMs = 0L,
            displayBounds = BubblePreviewRect(Int.MIN_VALUE, 0, Int.MAX_VALUE, 100),
            systemInsets = BubblePreviewInsets(0, 0, 0, 0),
            rootVisibleBounds = BubblePreviewRect(0, 0, 100, 100),
        )
        assertFalse(snapshot.isUsable())
        assertNull(snapshot.safeScreenBounds(marginPx = 12))
    }

    @Test
    fun safeBoundsRejectSnapshotsWithNoVisibleIntersection() {
        val snapshot = BubblePreviewGeometrySnapshot(
            hostGeneration = 1L,
            geometryGeneration = 1L,
            sampledAtElapsedMs = 1L,
            displayBounds = BubblePreviewRect(0, 0, 500, 500),
            systemInsets = BubblePreviewInsets(0, 0, 0, 0),
            rootVisibleBounds = BubblePreviewRect(600, 600, 700, 700),
        )
        assertTrue(snapshot.isUsable())
        assertNull(snapshot.safeScreenBounds(marginPx = 12))
    }

    @Test
    fun anchorPrefersHeadHitAndFallsBackToUnionTopMidpoint() {
        val character = BubblePreviewRect(100, 400, 301, 800)
        val withHead = BubblePreviewGeometrySnapshot(
            hostGeneration = 0L,
            geometryGeneration = 0L,
            sampledAtElapsedMs = 0L,
            displayBounds = BubblePreviewRect(0, 0, 1_000, 2_000),
            systemInsets = BubblePreviewInsets(0, 0, 0, 0),
            rootVisibleBounds = BubblePreviewRect(0, 0, 1_000, 2_000),
            characterBounds = character,
            headAnchor = BubblePreviewPoint(150f, 390f),
        )
        assertEquals(BubblePreviewPoint(150f, 390f), withHead.resolvedAnchor())

        val withoutHead = withHead.copy(headAnchor = null)
        assertEquals(BubblePreviewPoint(200.5f, 400f), withoutHead.resolvedAnchor())
        assertTrue(withoutHead.hasCharacterAnchor())

        val withoutCharacter = withoutHead.copy(characterBounds = null)
        assertNull(withoutCharacter.resolvedAnchor())
        assertFalse(withoutCharacter.hasCharacterAnchor())
    }

    @Test
    fun invalidGeometryIsRejectedInsteadOfGuessed() {
        val base = BubblePreviewGeometrySnapshot(
            hostGeneration = 0L,
            geometryGeneration = 0L,
            sampledAtElapsedMs = 0L,
            displayBounds = BubblePreviewRect(0, 0, 1_000, 2_000),
            systemInsets = BubblePreviewInsets(0, 0, 0, 0),
            rootVisibleBounds = BubblePreviewRect(0, 0, 1_000, 2_000),
        )
        assertTrue(base.isUsable())
        assertFalse(base.copy(hostGeneration = -1L).isUsable())
        assertFalse(base.copy(displayBounds = BubblePreviewRect(0, 0, 0, 0)).isUsable())
        assertFalse(base.copy(systemInsets = BubblePreviewInsets(-1, 0, 0, 0)).isUsable())
        assertFalse(base.copy(headAnchor = BubblePreviewPoint(Float.NaN, 0f)).isUsable())
        assertFalse(
            base.copy(characterBounds = BubblePreviewRect(10, 10, 10, 20)).isUsable(),
        )
        assertNull(base.copy(headAnchor = BubblePreviewPoint(Float.POSITIVE_INFINITY, 0f)).resolvedAnchor())
    }
}
class BubblePreviewHostFrameTest {
    @Test
    fun hitUnionUsesActualCirclesWithoutHitWindowPadding() {
        val hits = listOf(
            BubblePreviewHit(100f, 100f, 20f),
            BubblePreviewHit(160f, 180f, 40f),
        )
        val bounds = BubblePreviewHitGeometry.characterBounds(hits)!!
        assertEquals(BubblePreviewRect(80, 80, 200, 220), bounds)
        val anchor = BubblePreviewHitGeometry.headAnchor(hits)!!
        assertEquals(100f, anchor.x, 0.001f)
        assertEquals(80f, anchor.y, 0.001f)
    }

    @Test
    fun emptyNegativeNonFiniteAndHugeHitsInvalidateTheWholeSnapshot() {
        assertNull(BubblePreviewHitGeometry.characterBounds(emptyList()))
        assertNull(BubblePreviewHitGeometry.characterBounds(listOf(BubblePreviewHit(1f, 2f, 0f))))
        assertNull(BubblePreviewHitGeometry.characterBounds(listOf(BubblePreviewHit(Float.NaN, 2f, 3f))))
        assertNull(
            BubblePreviewHitGeometry.characterBounds(
                listOf(BubblePreviewHit(2_000_000f, 2f, 3f)),
            ),
        )
        assertNull(
            BubblePreviewHitGeometry.characterBounds(
                listOf(
                    BubblePreviewHit(100f, 100f, 20f),
                    BubblePreviewHit(Float.POSITIVE_INFINITY, 100f, 20f),
                ),
            ),
        )
    }

    @Test
    fun imeAndSystemBottomInsetsAreCombinedWithMaxNotDoubleSubtracted() {
        val snapshot = BubblePreviewGeometrySnapshot(
            hostGeneration = 1L,
            geometryGeneration = 1L,
            sampledAtElapsedMs = 1L,
            displayBounds = BubblePreviewRect(0, 0, 1080, 2400),
            systemInsets = BubblePreviewInsets(0, 60, 0, 100),
            rootVisibleBounds = BubblePreviewRect(0, 0, 1080, 2400),
            imeInsets = BubblePreviewInsets(0, 0, 0, 900),
        )
        // Bottom uses max(100, 900), not 100 + 900.
        assertEquals(
            BubblePreviewRect(10, 70, 1070, 1490),
            snapshot.safeScreenBounds(marginPx = 10),
        )
    }

    @Test
    fun frameKeepsAllGeometryAsImmutableValues() {
        val geometry = BubblePreviewGeometrySnapshot(
            hostGeneration = 2L,
            geometryGeneration = 3L,
            sampledAtElapsedMs = 4L,
            displayBounds = BubblePreviewRect(0, 0, 1000, 2000),
            systemInsets = BubblePreviewInsets(0, 0, 0, 0),
            rootVisibleBounds = BubblePreviewRect(0, 0, 1000, 2000),
            characterBounds = BubblePreviewRect(100, 100, 200, 300),
            headAnchor = BubblePreviewPoint(150f, 100f),
        )
        val frame = BubblePreviewHostFrame(
            hostGeneration = 2L,
            geometryGeneration = 3L,
            sampledAtElapsedMs = 4L,
            stageAttached = true,
            pageReady = true,
            clickThrough = false,
            hidden = false,
            opacity = 1f,
            geometry = geometry,
        )
        assertEquals(2L, frame.hostGeneration)
        assertEquals(3L, frame.geometryGeneration)
        assertTrue(frame.geometry!!.hasCharacterAnchor())
    }
}
