package com.wwkhub.bluefin.bubblepreview

import kotlin.math.roundToInt

internal enum class BubblePreviewSide {
    ABOVE,
    BELOW,
    RIGHT,
    LEFT,
}

internal data class BubblePreviewPlacement(
    val rect: BubblePreviewRect,
    val side: BubblePreviewSide,
    val slackPx: Int,
    val plan: BubblePreviewLayoutPlan,
)

internal data class BubblePreviewPendingSide(
    val side: BubblePreviewSide,
    val sinceElapsedMs: Long,
)

internal data class BubblePreviewPlacementDecision(
    val placement: BubblePreviewPlacement?,
    val pendingSide: BubblePreviewPendingSide?,
)

/**
 * Bounded placement: at most two plans x four ordered candidates, one clamp and
 * one collision check per candidate. It never loops until something fits and it
 * never moves the pet, orb or stage.
 */
internal object BubblePreviewPlacementPolicy {
    const val MAX_CANDIDATES_PER_PLAN = 4
    const val MAX_PLANS = 2
    const val SIDE_HYSTERESIS_DP = 16
    const val SIDE_HYSTERESIS_MS = 200L

    fun place(
        safeBounds: BubblePreviewRect,
        characterBounds: BubblePreviewRect,
        anchor: BubblePreviewPoint,
        orbBounds: BubblePreviewRect?,
        plans: List<BubblePreviewLayoutPlan>,
        previous: BubblePreviewPlacement?,
        pendingSide: BubblePreviewPendingSide?,
        nowElapsedMs: Long,
        density: Float,
        petGapDp: Float = 6f,
        orbGapDp: Float = 8f,
    ): BubblePreviewPlacementDecision {
        if (!safeBounds.isValid() || !characterBounds.isValid() || !anchor.isValid()) {
            return BubblePreviewPlacementDecision(null, null)
        }
        if (!density.isFinite() || density <= 0f || nowElapsedMs < 0L) {
            return BubblePreviewPlacementDecision(null, null)
        }
        val petGap = (petGapDp.coerceAtLeast(0f) * density).roundToInt()
        val orbGap = (orbGapDp.coerceAtLeast(0f) * density).roundToInt()
        val petExclusion = characterBounds.expand(petGap) ?: return BubblePreviewPlacementDecision(null, null)
        val orbExclusion = orbBounds?.expand(orbGap)

        var compactFallback: BubblePreviewPlacement? = null
        plans.take(MAX_PLANS).forEachIndexed { planIndex, plan ->
            if (plan.windowWidthPx <= 0 || plan.windowHeightPx <= 0) return@forEachIndexed
            val best = firstFit(
                safeBounds = safeBounds,
                characterBounds = characterBounds,
                anchor = anchor,
                petExclusion = petExclusion,
                orbExclusion = orbExclusion,
                plan = plan,
                petGap = petGap,
            ) ?: return@forEachIndexed
            if (planIndex == 0) {
                compactFallback = best
            } else {
                // A compact plan is only used when the preferred plan had no
                // candidate at all; caller passes the full plan first.
                compactFallback = best
            }
            return finishWithHysteresis(
                candidate = compactFallback!!,
                previous = previous,
                pendingSide = pendingSide,
                nowElapsedMs = nowElapsedMs,
                safeBounds = safeBounds,
                characterBounds = characterBounds,
                anchor = anchor,
                petExclusion = petExclusion,
                orbExclusion = orbExclusion,
                petGap = petGap,
                hysteresisPx = (SIDE_HYSTERESIS_DP * density).roundToInt(),
            )
        }
        return BubblePreviewPlacementDecision(null, null)
    }

    private fun finishWithHysteresis(
        candidate: BubblePreviewPlacement,
        previous: BubblePreviewPlacement?,
        pendingSide: BubblePreviewPendingSide?,
        nowElapsedMs: Long,
        safeBounds: BubblePreviewRect,
        characterBounds: BubblePreviewRect,
        anchor: BubblePreviewPoint,
        petExclusion: BubblePreviewRect,
        orbExclusion: BubblePreviewRect?,
        petGap: Int,
        hysteresisPx: Int,
    ): BubblePreviewPlacementDecision {
        if (previous == null) return BubblePreviewPlacementDecision(candidate, null)
        if (previous.side == candidate.side) return BubblePreviewPlacementDecision(candidate, null)

        // Retain the SIDE for hysteresis, but recompute its position against the
        // current anchor/character bounds. Returning the old absolute rect would
        // freeze a stale location while the pet keeps moving.
        val retainedRect = candidate(
            side = previous.side,
            safeBounds = safeBounds,
            characterBounds = characterBounds,
            anchor = anchor,
            plan = candidate.plan,
            petGap = petGap,
        )
        val retained = retainedRect?.takeIf {
            placementFits(it, safeBounds, petExclusion, orbExclusion)
        }?.let {
            BubblePreviewPlacement(
                rect = it,
                side = previous.side,
                slackPx = slack(it, safeBounds),
                plan = candidate.plan,
            )
        }
        if (retained == null) {
            return BubblePreviewPlacementDecision(candidate, null)
        }
        if (candidate.slackPx < retained.slackPx + hysteresisPx) {
            return BubblePreviewPlacementDecision(retained, null)
        }
        if (pendingSide?.side == candidate.side &&
            nowElapsedMs - pendingSide.sinceElapsedMs >= SIDE_HYSTERESIS_MS
        ) {
            return BubblePreviewPlacementDecision(candidate, null)
        }
        val pending = if (pendingSide?.side == candidate.side) {
            pendingSide
        } else {
            BubblePreviewPendingSide(candidate.side, nowElapsedMs)
        }
        return BubblePreviewPlacementDecision(retained, pending)
    }

    private fun firstFit(
        safeBounds: BubblePreviewRect,
        characterBounds: BubblePreviewRect,
        anchor: BubblePreviewPoint,
        petExclusion: BubblePreviewRect,
        orbExclusion: BubblePreviewRect?,
        plan: BubblePreviewLayoutPlan,
        petGap: Int,
    ): BubblePreviewPlacement? {
        val sideOrder = sideOrder(characterBounds, safeBounds)
        for (side in sideOrder) {
            val placed = candidate(side, safeBounds, characterBounds, anchor, plan, petGap) ?: continue
            if (!placementFits(placed, safeBounds, petExclusion, orbExclusion)) continue
            return BubblePreviewPlacement(
                rect = placed,
                side = side,
                slackPx = slack(
                    placed,
                    safeBounds,
                ),
                plan = plan,
            )
        }
        return null
    }

    private fun sideOrder(
        characterBounds: BubblePreviewRect,
        safeBounds: BubblePreviewRect,
    ): List<BubblePreviewSide> {
        val characterCenterX = characterBounds.left.toLong() + characterBounds.right.toLong()
        val safeCenterX = safeBounds.left.toLong() + safeBounds.right.toLong()
        return if (characterCenterX < safeCenterX) {
            listOf(
                BubblePreviewSide.ABOVE,
                BubblePreviewSide.BELOW,
                BubblePreviewSide.RIGHT,
                BubblePreviewSide.LEFT,
            )
        } else {
            listOf(
                BubblePreviewSide.ABOVE,
                BubblePreviewSide.BELOW,
                BubblePreviewSide.LEFT,
                BubblePreviewSide.RIGHT,
            )
        }
    }

    private fun candidate(
        side: BubblePreviewSide,
        safeBounds: BubblePreviewRect,
        characterBounds: BubblePreviewRect,
        anchor: BubblePreviewPoint,
        plan: BubblePreviewLayoutPlan,
        petGap: Int,
    ): BubblePreviewRect? {
        val width = plan.windowWidthPx
        val height = plan.windowHeightPx
        if (width > safeBounds.width || height > safeBounds.height) return null
        val maxX = safeBounds.right - width
        val maxY = safeBounds.bottom - height
        val rawX: Int
        val rawY: Int
        when (side) {
            BubblePreviewSide.ABOVE -> {
                rawX = (anchor.x - width / 2f).roundToInt()
                rawY = characterBounds.top - petGap - height
            }
            BubblePreviewSide.BELOW -> {
                rawX = (anchor.x - width / 2f).roundToInt()
                rawY = characterBounds.bottom + petGap
            }
            BubblePreviewSide.RIGHT -> {
                rawX = characterBounds.right + petGap
                rawY = (characterBounds.top + characterBounds.bottom) / 2 - height / 2
            }
            BubblePreviewSide.LEFT -> {
                rawX = characterBounds.left - petGap - width
                rawY = (characterBounds.top + characterBounds.bottom) / 2 - height / 2
            }
        }
        // Exactly one clamp, then the caller checks collisions. A clamp that
        // creates a collision rejects that candidate instead of recursively
        // searching for another position.
        val x = rawX.coerceIn(safeBounds.left, maxX)
        val y = rawY.coerceIn(safeBounds.top, maxY)
        return BubblePreviewRect(x, y, x + width, y + height).takeIf { it.isValid() }
    }

    private fun placementFits(
        placement: BubblePreviewRect,
        safeBounds: BubblePreviewRect,
        petExclusion: BubblePreviewRect,
        orbExclusion: BubblePreviewRect?,
    ): Boolean {
        if (!placement.isValid()) return false
        if (placement.left < safeBounds.left || placement.top < safeBounds.top) return false
        if (placement.right > safeBounds.right || placement.bottom > safeBounds.bottom) return false
        if (placement.intersect(petExclusion) != null) return false
        if (orbExclusion != null && placement.intersect(orbExclusion) != null) return false
        return true
    }

    private fun slack(rect: BubblePreviewRect, safe: BubblePreviewRect): Int =
        minOf(
            rect.left - safe.left,
            safe.right - rect.right,
            rect.top - safe.top,
            safe.bottom - rect.bottom,
        ).coerceAtLeast(0)
}