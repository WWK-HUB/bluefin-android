package com.wwkhub.bluefin.bubblepreview.visual

/** Tail tip direction, not the side of the bubble the pet is on. */
enum class BubblePreviewTailDirection {
    DOWN,
    UP,
    LEFT,
    RIGHT,
    NONE,
}

/**
 * Floating rectangle in the caller's coordinate space. For a
 * [BubblePreviewTailLayout] it is relative to the character body's top-left;
 * negative top/left is normal for up/left tails.
 */
data class BubblePreviewTailRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left

    val height: Float get() = bottom - top

    fun isFinite(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    fun isValid(): Boolean = isFinite() && right > left && bottom > top
}

enum class BubblePreviewTailHiddenReason {
    NONE,
    NOT_REQUESTED,
    USER_SUMMARY_BLOCKS_UP,
    INVALID_INPUT,
    NO_FIT,
    NUMERIC_OVERFLOW,
}

/**
 * Result of tail sizing/placement. [rect] is null whenever the tail must be
 * hidden (direction NONE, up tail with a user summary, invalid numbers or a
 * character body too short for the 24k margin on both sides).
 */
data class BubblePreviewTailLayout(
    val direction: BubblePreviewTailDirection,
    val rect: BubblePreviewTailRect?,
    val seamPx: Float,
    val tailWidthPx: Float,
    val tailHeightPx: Float,
    val cornerMarginPx: Float,
    val hiddenReason: BubblePreviewTailHiddenReason,
) {
    val isVisible: Boolean get() = rect != null

    val isFinite: Boolean
        get() = seamPx.isFinite() && tailWidthPx.isFinite() && tailHeightPx.isFinite() &&
            cornerMarginPx.isFinite() && (rect?.isFinite() != false)
}

/**
 * Inputs for one tail decision.
 *
 * [characterBody] is the actual character shape rectangle (not the root window
 * containing the user zone). [anchorX]/[anchorY] are in the same coordinate
 * space as the body; the relevant coordinate is clamped along the body edge.
 */
data class BubblePreviewTailRequest(
    val direction: BubblePreviewTailDirection,
    val characterBody: BubblePreviewTailRect,
    val anchorX: Float,
    val anchorY: Float,
    val characterScale: Float,
    val density: Float,
    val hasUserSummary: Boolean = false,
)

/**
 * Pure finite-arithmetic tail geometry.
 *
 * Scale: `k = clamp(S / 0.85, 0.85, 1.20)`. Default day/night tokens do not
 * affect the geometry.
 *
 * - DOWN/UP tail: `12k dp` wide, `6k dp` high.
 * - LEFT/RIGHT tail: `6k dp` wide, `12k dp` high.
 * - Seam: exactly `1dp` (never scaled by k).
 * - Corner/edge margin: `24k dp` on each end of the character edge.
 *
 * Placement follows the resource contract relative to the character body:
 * - DOWN: top = `bodyHeight - seam`, extends below.
 * - UP: bottom = `seam`, extends above; hidden when a user summary exists.
 * - LEFT: right = `seam`, extends left.
 * - RIGHT: left = `bodyWidth - seam`, extends right.
 * The perpendicular coordinate is clamped so the whole tail keeps the 24k dp
 * margin. If `bodyW < 48k + 12k` (vertical tail) or
 * `bodyH < 48k + 12k` (horizontal tail), the tail is hidden rather than
 * stretched or dragged across the screen.
 */
object BubblePreviewTailGeometry {
    const val TAIL_LONG_DP = 12f
    const val TAIL_SHORT_DP = 6f
    const val SEAM_DP = 1f
    const val CORNER_MARGIN_DP = 24f
    const val MIN_CHARACTER_SCALE = 0.50f
    const val MAX_CHARACTER_SCALE = 1.30f
    const val BASE_CHARACTER_SCALE = 0.85f
    const val MIN_K = 0.85f
    const val MAX_K = 1.20f
    const val MIN_DENSITY = 0.1f
    const val MAX_DENSITY = 8.0f
    const val MAX_ABS_COORDINATE = 1_000_000.0

    fun kForScale(characterScale: Float): Float {
        require(characterScale.isFinite()) { "characterScale must be finite" }
        val scale = characterScale.coerceIn(MIN_CHARACTER_SCALE, MAX_CHARACTER_SCALE)
        return (scale / BASE_CHARACTER_SCALE).coerceIn(MIN_K, MAX_K)
    }

    fun resolve(request: BubblePreviewTailRequest): BubblePreviewTailLayout {
        if (request.direction == BubblePreviewTailDirection.NONE) {
            return hidden(
                direction = BubblePreviewTailDirection.NONE,
                reason = BubblePreviewTailHiddenReason.NOT_REQUESTED,
            )
        }

        if (!request.characterScale.isFinite() ||
            !request.density.isFinite() ||
            request.density < MIN_DENSITY ||
            request.density > MAX_DENSITY ||
            !inBounds(request.anchorX.toDouble()) ||
            !inBounds(request.anchorY.toDouble()) ||
            !isBodyUsable(request.characterBody)
        ) {
            return hidden(request.direction, BubblePreviewTailHiddenReason.INVALID_INPUT)
        }

        val metrics = metrics(request.characterScale, request.density)
            ?: return hidden(request.direction, BubblePreviewTailHiddenReason.INVALID_INPUT)

        if (request.direction == BubblePreviewTailDirection.UP && request.hasUserSummary) {
            return hidden(
                direction = BubblePreviewTailDirection.UP,
                reason = BubblePreviewTailHiddenReason.USER_SUMMARY_BLOCKS_UP,
                metrics = metrics,
            )
        }

        val body = request.characterBody
        val bodyWidth = body.right.toDouble() - body.left.toDouble()
        val bodyHeight = body.bottom.toDouble() - body.top.toDouble()

        return when (request.direction) {
            BubblePreviewTailDirection.DOWN,
            BubblePreviewTailDirection.UP,
            -> verticalTail(request, bodyWidth, bodyHeight, metrics)

            BubblePreviewTailDirection.LEFT,
            BubblePreviewTailDirection.RIGHT,
            -> horizontalTail(request, bodyWidth, bodyHeight, metrics)

            BubblePreviewTailDirection.NONE -> hidden(
                BubblePreviewTailDirection.NONE,
                BubblePreviewTailHiddenReason.NOT_REQUESTED,
            )
        }
    }

    private fun verticalTail(
        request: BubblePreviewTailRequest,
        bodyWidth: Double,
        bodyHeight: Double,
        metrics: Metrics,
    ): BubblePreviewTailLayout {
        val along = metrics.longPx
        val margin = metrics.marginPx
        if (bodyWidth < 2.0 * margin + along) {
            return hidden(request.direction, BubblePreviewTailHiddenReason.NO_FIT, metrics)
        }

        val anchor = request.anchorX.toDouble() - request.characterBody.left.toDouble()
        val center = anchor.coerceIn(margin + along / 2.0, bodyWidth - margin - along / 2.0)
        val left = center - along / 2.0
        val right = left + along
        val (top, bottom) = when (request.direction) {
            BubblePreviewTailDirection.DOWN -> bodyHeight - metrics.seamPx to
                bodyHeight - metrics.seamPx + metrics.shortPx

            BubblePreviewTailDirection.UP -> metrics.seamPx - metrics.shortPx to metrics.seamPx
            else -> return hidden(request.direction, BubblePreviewTailHiddenReason.INVALID_INPUT, metrics)
        }
        val rect = tailRect(left, top, right, bottom)
            ?: return hidden(request.direction, BubblePreviewTailHiddenReason.NUMERIC_OVERFLOW, metrics)
        return visible(request.direction, rect, metrics, tailWidth = along, tailHeight = metrics.shortPx)
    }

    private fun horizontalTail(
        request: BubblePreviewTailRequest,
        bodyWidth: Double,
        bodyHeight: Double,
        metrics: Metrics,
    ): BubblePreviewTailLayout {
        val along = metrics.longPx
        val margin = metrics.marginPx
        if (bodyHeight < 2.0 * margin + along) {
            return hidden(request.direction, BubblePreviewTailHiddenReason.NO_FIT, metrics)
        }

        val anchor = request.anchorY.toDouble() - request.characterBody.top.toDouble()
        val center = anchor.coerceIn(margin + along / 2.0, bodyHeight - margin - along / 2.0)
        val top = center - along / 2.0
        val bottom = top + along
        val (left, right) = when (request.direction) {
            BubblePreviewTailDirection.LEFT -> metrics.seamPx - metrics.shortPx to metrics.seamPx
            BubblePreviewTailDirection.RIGHT -> bodyWidth - metrics.seamPx to
                bodyWidth - metrics.seamPx + metrics.shortPx

            else -> return hidden(request.direction, BubblePreviewTailHiddenReason.INVALID_INPUT, metrics)
        }
        val rect = tailRect(left, top, right, bottom)
            ?: return hidden(request.direction, BubblePreviewTailHiddenReason.NUMERIC_OVERFLOW, metrics)
        return visible(request.direction, rect, metrics, tailWidth = metrics.shortPx, tailHeight = along)
    }

    private fun metrics(characterScale: Float, density: Float): Metrics? {
        val k = kForScale(characterScale)
        val scale = k.toDouble() * density.toDouble()
        val longPx = TAIL_LONG_DP.toDouble() * scale
        val shortPx = TAIL_SHORT_DP.toDouble() * scale
        val seamPx = SEAM_DP.toDouble() * density.toDouble()
        val marginPx = CORNER_MARGIN_DP.toDouble() * scale
        if (!inBounds(longPx) || !inBounds(shortPx) || !inBounds(seamPx) || !inBounds(marginPx)) {
            return null
        }
        return Metrics(
            longPx = longPx,
            shortPx = shortPx,
            seamPx = seamPx,
            marginPx = marginPx,
        )
    }

    private fun isBodyUsable(body: BubblePreviewTailRect): Boolean {
        if (!body.isValid()) return false
        if (!inBounds(body.left.toDouble()) || !inBounds(body.top.toDouble()) ||
            !inBounds(body.right.toDouble()) || !inBounds(body.bottom.toDouble())
        ) {
            return false
        }
        return inBounds(body.right.toDouble() - body.left.toDouble()) &&
            inBounds(body.bottom.toDouble() - body.top.toDouble())
    }

    private fun tailRect(left: Double, top: Double, right: Double, bottom: Double): BubblePreviewTailRect? {
        if (!inBounds(left) || !inBounds(top) || !inBounds(right) || !inBounds(bottom)) return null
        if (!inBounds(right - left) || !inBounds(bottom - top)) return null
        val rect = BubblePreviewTailRect(
            left = left.toFloat(),
            top = top.toFloat(),
            right = right.toFloat(),
            bottom = bottom.toFloat(),
        )
        return rect.takeIf { it.isValid() }
    }

    private fun inBounds(value: Double): Boolean =
        value.isFinite() && value >= -MAX_ABS_COORDINATE && value <= MAX_ABS_COORDINATE

    private fun visible(
        direction: BubblePreviewTailDirection,
        rect: BubblePreviewTailRect,
        metrics: Metrics,
        tailWidth: Double,
        tailHeight: Double,
    ): BubblePreviewTailLayout = BubblePreviewTailLayout(
        direction = direction,
        rect = rect,
        seamPx = metrics.seamPx.toFloat(),
        tailWidthPx = tailWidth.toFloat(),
        tailHeightPx = tailHeight.toFloat(),
        cornerMarginPx = metrics.marginPx.toFloat(),
        hiddenReason = BubblePreviewTailHiddenReason.NONE,
    )

    private fun hidden(
        direction: BubblePreviewTailDirection,
        reason: BubblePreviewTailHiddenReason,
        metrics: Metrics? = null,
    ): BubblePreviewTailLayout {
        val horizontal = direction == BubblePreviewTailDirection.LEFT ||
            direction == BubblePreviewTailDirection.RIGHT
        return BubblePreviewTailLayout(
            direction = direction,
            rect = null,
            seamPx = metrics?.seamPx?.toFloat() ?: 0f,
            tailWidthPx = metrics?.let { if (horizontal) it.shortPx else it.longPx }?.toFloat() ?: 0f,
            tailHeightPx = metrics?.let { if (horizontal) it.longPx else it.shortPx }?.toFloat() ?: 0f,
            cornerMarginPx = metrics?.marginPx?.toFloat() ?: 0f,
            hiddenReason = reason,
        )
    }

    private data class Metrics(
        val longPx: Double,
        val shortPx: Double,
        val seamPx: Double,
        val marginPx: Double,
    )
}
