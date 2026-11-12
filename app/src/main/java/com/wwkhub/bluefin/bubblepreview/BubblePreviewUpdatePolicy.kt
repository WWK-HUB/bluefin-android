package com.wwkhub.bluefin.bubblepreview

import kotlin.math.abs

internal sealed class BubblePreviewUpdateDecision {
    data object Apply : BubblePreviewUpdateDecision()

    data class Schedule(val delayMs: Long) : BubblePreviewUpdateDecision()

    data object Skip : BubblePreviewUpdateDecision()
}

/**
 * Position/update budget: at most one update per 50 ms, unchanged positions
 * under 2 dp are suppressed, and a changed width/height or side bypasses only
 * the small-delta suppression (not the 20 Hz limit).
 */
internal class BubblePreviewUpdateGate(
    private val minimumIntervalMs: Long = 50L,
    private val minimumDeltaPx: Int = 2,
) {
    private var lastAppliedAtMs: Long = Long.MIN_VALUE
    private var lastPlacement: BubblePreviewPlacement? = null

    fun decide(
        nowElapsedMs: Long,
        candidate: BubblePreviewPlacement,
    ): BubblePreviewUpdateDecision {
        if (nowElapsedMs < 0L) return BubblePreviewUpdateDecision.Skip
        val previous = lastPlacement
        if (previous != null && !changedEnough(previous, candidate)) {
            return BubblePreviewUpdateDecision.Skip
        }
        if (lastAppliedAtMs == Long.MIN_VALUE) return BubblePreviewUpdateDecision.Apply
        val elapsed = nowElapsedMs - lastAppliedAtMs
        if (elapsed < 0L) return BubblePreviewUpdateDecision.Schedule(minimumIntervalMs)
        if (elapsed < minimumIntervalMs) {
            return BubblePreviewUpdateDecision.Schedule(minimumIntervalMs - elapsed)
        }
        return BubblePreviewUpdateDecision.Apply
    }

    fun markApplied(nowElapsedMs: Long, placement: BubblePreviewPlacement) {
        lastAppliedAtMs = nowElapsedMs
        lastPlacement = placement
    }

    fun reset() {
        lastAppliedAtMs = Long.MIN_VALUE
        lastPlacement = null
    }

    fun lastAppliedPlacement(): BubblePreviewPlacement? = lastPlacement

    private fun changedEnough(
        previous: BubblePreviewPlacement,
        candidate: BubblePreviewPlacement,
    ): Boolean {
        if (previous.rect.width != candidate.rect.width) return true
        if (previous.rect.height != candidate.rect.height) return true
        if (previous.side != candidate.side) return true
        if (previous.plan.bodyLines != candidate.plan.bodyLines) return true
        if (previous.plan.includeUserSummary != candidate.plan.includeUserSummary) return true
        return abs(previous.rect.left - candidate.rect.left) >= minimumDeltaPx ||
            abs(previous.rect.top - candidate.rect.top) >= minimumDeltaPx
    }
}

/**
 * Counters for the audit trail. They intentionally contain no chat body, key,
 * URL or model response: only add/update/remove/callback counts and the
 * geometry/configuration epochs.
 */
internal data class BubblePreviewCounterSnapshot(
    val add: Int,
    val update: Int,
    val remove: Int,
    val callbacks: Int,
    val scheduled: Int,
    val metricsReads: Int,
    val failures: Int,
)

internal class BubblePreviewCounters {
    private var add = 0
    private var update = 0
    private var remove = 0
    private var callbacks = 0
    private var scheduled = 0
    private var metricsReads = 0
    private var failures = 0

    fun added() {
        add += 1
    }

    fun updated() {
        update += 1
    }

    fun removed() {
        remove += 1
    }

    fun callbackDelivered() {
        callbacks += 1
    }

    fun taskScheduled() {
        scheduled += 1
    }

    fun metricsRead() {
        metricsReads += 1
    }

    fun failure() {
        failures += 1
    }

    fun snapshot(): BubblePreviewCounterSnapshot = BubblePreviewCounterSnapshot(
        add = add,
        update = update,
        remove = remove,
        callbacks = callbacks,
        scheduled = scheduled,
        metricsReads = metricsReads,
        failures = failures,
    )
}