package com.wwkhub.bluefin.bubblepreview

/**
 * Pixel rectangle exchanged across the optional module boundary.
 *
 * This is deliberately not `android.graphics.Rect`: the preview module only
 * receives immutable data snapshots and can never call back into the host for
 * a screen size, density or measurement.
 */
internal data class BubblePreviewRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = span(right, left)

    val height: Int get() = span(bottom, top)

    private fun span(high: Int, low: Int): Int {
        val value = high.toLong() - low.toLong()
        return if (value in 0L..Int.MAX_VALUE.toLong()) value.toInt() else 0
    }

    /**
     * A rectangle is valid only when both dimensions are positive and exactly
     * representable as an `Int`. `right - left` alone can overflow (for example
     * `Int.MAX_VALUE - Int.MIN_VALUE`); such a rectangle must be rejected rather
     * than looking valid while reporting a silently clamped width of zero.
     */
    fun isValid(): Boolean {
        val width = right.toLong() - left.toLong()
        val height = bottom.toLong() - top.toLong()
        return width > 0L && width <= Int.MAX_VALUE.toLong() &&
            height > 0L && height <= Int.MAX_VALUE.toLong()
    }

    /** Expands by [amount] px on every edge; used for pet/orb clearance checks. */
    fun expand(amount: Int): BubblePreviewRect? {
        if (amount < 0) return null
        val newLeft = left.toLong() - amount
        val newTop = top.toLong() - amount
        val newRight = right.toLong() + amount
        val newBottom = bottom.toLong() + amount
        if (newLeft < Int.MIN_VALUE || newTop < Int.MIN_VALUE) return null
        if (newRight > Int.MAX_VALUE || newBottom > Int.MAX_VALUE) return null
        val result = BubblePreviewRect(
            newLeft.toInt(),
            newTop.toInt(),
            newRight.toInt(),
            newBottom.toInt(),
        )
        return result.takeIf { it.isValid() }
    }

    fun intersect(other: BubblePreviewRect): BubblePreviewRect? {
        if (!isValid() || !other.isValid()) return null
        val left = maxOf(this.left, other.left)
        val top = maxOf(this.top, other.top)
        val right = minOf(this.right, other.right)
        val bottom = minOf(this.bottom, other.bottom)
        return BubblePreviewRect(left, top, right, bottom).takeIf { it.isValid() }
    }

    /** Insets the rectangle; returns null instead of an inverted/empty rect. */
    fun inset(left: Int, top: Int, right: Int, bottom: Int): BubblePreviewRect? {
        if (left < 0 || top < 0 || right < 0 || bottom < 0) return null
        val newLeft = this.left.toLong() + left
        val newTop = this.top.toLong() + top
        val newRight = this.right.toLong() - right
        val newBottom = this.bottom.toLong() - bottom
        if (newLeft < Int.MIN_VALUE || newLeft > Int.MAX_VALUE) return null
        if (newTop < Int.MIN_VALUE || newTop > Int.MAX_VALUE) return null
        if (newRight < Int.MIN_VALUE || newRight > Int.MAX_VALUE) return null
        if (newBottom < Int.MIN_VALUE || newBottom > Int.MAX_VALUE) return null
        val result = BubblePreviewRect(
            newLeft.toInt(),
            newTop.toInt(),
            newRight.toInt(),
            newBottom.toInt(),
        )
        return result.takeIf { it.isValid() }
    }
}

/** Non-negative system-bar/cutout insets, still expressed in pixels. */
internal data class BubblePreviewInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun isValid(): Boolean = left >= 0 && top >= 0 && right >= 0 && bottom >= 0
}

/** A finite point in the same screen-pixel coordinate space as the rectangles. */
internal data class BubblePreviewPoint(
    val x: Float,
    val y: Float,
) {
    fun isValid(): Boolean = x.isFinite() && y.isFinite()
}

/**
 * One immutable geometry generation handed to the optional module.
 *
 * All fields are values. There are no callbacks, no lazy host accessors and no
 * host-size delegate. The host can drop this snapshot without the module being
 * able to trigger measurement, layout, preference reads or window work.
 */
internal data class BubblePreviewGeometrySnapshot(
    val hostGeneration: Long,
    val geometryGeneration: Long,
    val sampledAtElapsedMs: Long,
    val displayBounds: BubblePreviewRect,
    val systemInsets: BubblePreviewInsets,
    val rootVisibleBounds: BubblePreviewRect,
    val imeInsets: BubblePreviewInsets = BubblePreviewInsets(0, 0, 0, 0),
    val characterBounds: BubblePreviewRect? = null,
    val headAnchor: BubblePreviewPoint? = null,
    val orbWindowBounds: BubblePreviewRect? = null,
) {
    fun isUsable(): Boolean {
        if (hostGeneration < 0L || geometryGeneration < 0L || sampledAtElapsedMs < 0L) {
            return false
        }
        if (!displayBounds.isValid() || !systemInsets.isValid() || !imeInsets.isValid()) {
            return false
        }
        if (!rootVisibleBounds.isValid()) return false
        if (characterBounds != null && !characterBounds.isValid()) return false
        if (orbWindowBounds != null && !orbWindowBounds.isValid()) return false
        if (headAnchor != null && !headAnchor.isValid()) return false
        return true
    }

    fun hasCharacterAnchor(): Boolean =
        (headAnchor?.isValid() == true) || (characterBounds?.isValid() == true)

    /**
     * Head-circle top when supplied, otherwise the union top midpoint. No image
     * scan and no host callback are involved.
     */
    fun resolvedAnchor(): BubblePreviewPoint? {
        val head = headAnchor
        if (head != null && head.isValid()) return head
        val character = characterBounds
        if (character == null || !character.isValid()) return null
        val midX = (character.left.toLong() + character.right.toLong()) / 2.0
        return BubblePreviewPoint(midX.toFloat(), character.top.toFloat())
    }

    /**
     * Apply the system bars and cutout exactly once, intersect with the attached
     * root's visible rectangle, then apply the caller's pixel safety margin to
     * all four edges of that final intersection. Keeping the margin last means
     * a clipped root or IME edge never loses the requested gap.
     */
    fun safeScreenBounds(marginPx: Int): BubblePreviewRect? {
        if (marginPx < 0 || !isUsable()) return null
        // System bars and IME insets can overlap (the IME sits above the
        // navigation bar). Use the larger edge exactly once rather than adding
        // the two and shrinking the window twice.
        val barSafe = displayBounds.inset(
            maxOf(systemInsets.left, imeInsets.left),
            maxOf(systemInsets.top, imeInsets.top),
            maxOf(systemInsets.right, imeInsets.right),
            maxOf(systemInsets.bottom, imeInsets.bottom),
        ) ?: return null
        val rootClipped = barSafe.intersect(rootVisibleBounds) ?: return null
        return rootClipped.inset(marginPx, marginPx, marginPx, marginPx)
    }
}
