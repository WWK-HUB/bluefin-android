package com.wwkhub.bluefin.bubblepreview

/**
 * Optional display/window-bar basis. It is read once per configuration epoch,
 * lazily, only after an eligible explicit STARTED, and is reused by the
 * preflight projection and the later screen snapshot. It deliberately does not
 * contain IME state: the keyboard edge comes from the live trusted IME gate.
 */
internal data class BubblePreviewDisplayBasis(
    val displayBounds: BubblePreviewRect,
    val systemInsets: BubblePreviewInsets,
) {
    fun isUsable(): Boolean = displayBounds.isValid() && systemInsets.isValid()
}

/**
 * Immutable screen data captured by the host only after an eligible explicit
 * chat action. The module never calls back for a size, density or measurement.
 *
 * [systemInsets] is the system-bar/cutout inset; [imeInsets] is the trusted IME
 * edge read from the attached root/orb. They are combined with `max`, not by
 * adding both, so a keyboard above the navigation bar cannot subtract twice.
 */
internal data class BubblePreviewScreenSnapshot(
    val displayBounds: BubblePreviewRect,
    val systemInsets: BubblePreviewInsets,
    val imeInsets: BubblePreviewInsets,
    val rootVisibleBounds: BubblePreviewRect,
) {
    fun isUsable(): Boolean =
        displayBounds.isValid() && systemInsets.isValid() && imeInsets.isValid() &&
            rootVisibleBounds.isValid()
}

/**
 * The only geometry payload the host hands to the optional preview. Every field
 * is a value; [geometry] is already converted to screen pixels with the same
 * web-location+density transform as the rc3 hit window, and contains no 12 dp
 * hit padding.
 */
internal data class BubblePreviewHostFrame(
    val hostGeneration: Long,
    val geometryGeneration: Long,
    val sampledAtElapsedMs: Long,
    val stageAttached: Boolean,
    val pageReady: Boolean,
    val clickThrough: Boolean,
    val hidden: Boolean,
    val opacity: Float,
    val geometry: BubblePreviewGeometrySnapshot?,
)

/**
 * The current round's static placeholder content for stage B. Stage C replaces
 * the static status text with the streaming/state machine; no history is ever
 * replayed from this object.
 */
internal data class BubblePreviewContent(
    val userSummary: String,
    val characterName: String,
    val statusText: String,
) {
    fun isValid(): Boolean = characterName.isNotBlank() && statusText.isNotBlank()
}

internal const val BUBBLE_PREVIEW_SAFETY_MARGIN_DP = 12
internal const val BUBBLE_PREVIEW_PET_GAP_DP = 6
internal const val BUBBLE_PREVIEW_ORB_GAP_DP = 8
/** One actual character hit circle in screen pixels. */
internal data class BubblePreviewHit(
    val x: Float,
    val y: Float,
    val radius: Float,
) {
    fun isValid(): Boolean =
        x.isFinite() && y.isFinite() && radius.isFinite() && radius > 0f &&
            x > -MAX_COORDINATE && x < MAX_COORDINATE &&
            y > -MAX_COORDINATE && y < MAX_COORDINATE &&
            radius < MAX_RADIUS

    private companion object {
        const val MAX_COORDINATE = 1_000_000f
        const val MAX_RADIUS = 100_000f
    }
}

/**
 * Pure conversion of actual hit circles to the character union and head anchor:
 * no 12 dp hit-window padding, no image scan and no host callback.
 */
internal object BubblePreviewHitGeometry {
    fun characterBounds(hits: List<BubblePreviewHit>): BubblePreviewRect? {
        if (hits.isEmpty() || hits.any { !it.isValid() }) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        hits.forEach { hit ->
            minX = minOf(minX, hit.x - hit.radius)
            minY = minOf(minY, hit.y - hit.radius)
            maxX = maxOf(maxX, hit.x + hit.radius)
            maxY = maxOf(maxY, hit.y + hit.radius)
        }
        val left = minX.toInt()
        val top = minY.toInt()
        val right = maxX.toInt()
        val bottom = maxY.toInt()
        return BubblePreviewRect(left, top, right, bottom).takeIf { it.isValid() }
    }

    fun headAnchor(hits: List<BubblePreviewHit>): BubblePreviewPoint? {
        val head = hits.firstOrNull()?.takeIf { it.isValid() } ?: return null
        val point = BubblePreviewPoint(head.x, head.y - head.radius)
        return point.takeIf { it.isValid() }
    }
}
