package com.wwkhub.bluefin.bubblepreview

import com.wwkhub.bluefin.OverlayLayout
import kotlin.math.roundToInt

/**
 * Projected collapsed orb-button rectangle, used only as a bounded preflight
 * before the record panel is explicitly collapsed. It is not the expanded
 * full-screen orb window and must never be reported as the actual collision
 * rectangle after attachment.
 */
internal object BubblePreviewOrbProjection {
    fun collapsedButtonBounds(
        displayBounds: BubblePreviewRect,
        sideRight: Boolean,
        orbYDp: Float,
        orbSizeDp: Float,
        density: Float,
    ): BubblePreviewRect? {
        if (!displayBounds.isValid()) return null
        if (!density.isFinite() || density <= 0f) return null
        val d = density.coerceAtLeast(0.5f)
        val sizeDp = if (orbSizeDp.isFinite()) orbSizeDp else OverlayLayout.ORB_SIZE_DP.toFloat()
        val size = (sizeDp * d).roundToInt().coerceAtLeast(1)
        val margin = (OverlayLayout.ORB_MARGIN_DP * d).roundToInt()
        val safeBottomDp = OverlayLayout.orbSafeBottomDp(displayBounds.height, d, sizeDp)
        val clampedYDp = OverlayLayout.clampOrbYDp(orbYDp, safeBottomDp, sizeDp)
        val y = (clampedYDp * d).roundToInt()
            .coerceIn(0, (displayBounds.height - size).coerceAtLeast(0))
        val maxX = (displayBounds.width - size - margin).coerceAtLeast(0)
        val x = if (sideRight) maxX else margin.coerceIn(0, maxX)
        val left = displayBounds.left + x
        val top = displayBounds.top + y
        return BubblePreviewRect(left, top, left + size, top + size)
            .takeIf { it.isValid() }
    }
}
