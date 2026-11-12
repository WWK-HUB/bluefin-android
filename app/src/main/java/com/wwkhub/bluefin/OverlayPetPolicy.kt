package com.wwkhub.bluefin

/**
 * Decisions for the full-screen overlay pet. The stage is the screen, the
 * floor is 40px above the bottom, and scale stays inside the store's live band.
 * There are no wall-climb frames: an edge is a turn back across the floor.
 */
internal object OverlayPetPolicy {
    const val SCALE_MIN = BluefinStore.SCALE_MIN
    const val SCALE_MAX = BluefinStore.SCALE_MAX
    const val FLOOR_INSET_PX = 40
    const val EDGE_MARGIN_PX = 16f
    const val EDGE_ARRIVAL_TOLERANCE_PX = 1f
    const val SLEEP_AFTER_MS = 15_000L

    fun scaleFor(scale: Float): Float = BluefinStore.clampScale(scale)

    /**
     * Where to send the pet when it has reached a side, or null to leave
     * the engine's own walk/run/stop alone. Facing &lt; 0 looks left.
     */
    fun edgeTarget(
        x: Float,
        facing: Int,
        minX: Float,
        maxX: Float,
        mode: String,
    ): Float? {
        if (maxX <= minX) return null
        if (mode != "idle" && mode != "walk" && mode != "run" && mode != "look") return null
        val margin = EDGE_MARGIN_PX
        val arrivalTolerance = EDGE_ARRIVAL_TOLERANCE_PX
        val atLeft = x <= minX + margin + arrivalTolerance
        val atRight = x >= maxX - margin - arrivalTolerance
        if (atLeft && (facing <= 0 || mode == "idle")) return maxX - margin
        if (atRight && (facing >= 0 || mode == "idle")) return minX + margin
        return null
    }

    fun clampLanding(x: Float, y: Float, minX: Float, maxX: Float, floorY: Float): Pair<Float, Float> {
        return x.coerceIn(minX, maxX.coerceAtLeast(minX)) to y.coerceAtMost(floorY).coerceAtLeast(0f)
    }

    fun shouldStartSleep(idleMs: Long, mode: String, resting: Boolean): Boolean {
        if (resting || idleMs < SLEEP_AFTER_MS) return false
        return mode == "idle" || mode == "walk" || mode == "run" || mode == "look"
    }
}
