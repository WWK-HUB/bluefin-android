package com.wwkhub.bluefin.bubblepreview

/**
 * Immutable view of the process circuit for a future settings/records hint.
 */
internal data class BubblePreviewCircuitSnapshot(
    val consecutiveFailures: Int,
    val tripped: Boolean,
    val failureThreshold: Int,
    val sustainedSuccessMs: Long,
)

/**
 * Lightweight, process-owned consecutive local-failure circuit.
 *
 * The circuit is pure JVM state: no Android types, no listeners, no timers and
 * no window or metrics reads. A future preview caller owns one
 * [BubblePreviewDisplayAttempt] per explicit display attempt. Reporting the
 * same attempt's failure more than once cannot count as multiple attempts.
 *
 * Successful continuity is explicit. The caller starts visibility with
 * [BubblePreviewDisplayAttempt.markVisible] and must end that visibility, at
 * the moment it actually ends, with [BubblePreviewDisplayAttempt.endVisibility]
 * (or [BubblePreviewDisplayAttempt.endAttempt] when the whole attempt closes).
 * A hidden interval therefore cannot be mistaken for continued visibility: a
 * display hidden after one second and reopened ten seconds later does not
 * accumulate a two-second success window.
 *
 * Three independent failures disable the preview for the rest of the process.
 * A display that stays healthy for two seconds resets the consecutive count.
 * There is no reset method: hiding/reopening and host object recreation cannot
 * clear a trip.
 */
internal class BubblePreviewFailureCircuit(
    private val failureThreshold: Int = BubblePreviewModule.FAILURE_THRESHOLD,
    private val sustainedSuccessMs: Long = BubblePreviewModule.SUSTAINED_SUCCESS_MS,
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(sustainedSuccessMs >= 0) { "sustainedSuccessMs must not be negative" }
    }

    private var consecutiveFailures = 0
    private var tripped = false
    private var nextAttemptId = 0L
    private var activeAttempt: BubblePreviewDisplayAttempt? = null

    fun snapshot(): BubblePreviewCircuitSnapshot = BubblePreviewCircuitSnapshot(
        consecutiveFailures = consecutiveFailures,
        tripped = tripped,
        failureThreshold = failureThreshold,
        sustainedSuccessMs = sustainedSuccessMs,
    )

    fun isTripped(): Boolean = tripped

    fun consecutiveFailures(): Int = consecutiveFailures

    /**
     * Starts one independent display attempt. The returned token must be used
     * for that attempt's visibility/failure reports so duplicate callbacks are
     * collapsed into one count. A previous still-visible attempt may settle its
     * healthy window here; an attempt whose visibility was explicitly ended
     * cannot accumulate hidden time.
     */
    fun beginDisplayAttempt(nowElapsedMs: Long): BubblePreviewDisplayAttempt? {
        if (tripped) return null
        require(nowElapsedMs >= 0L) { "nowElapsedMs must not be negative" }
        activeAttempt?.let { settleIfSustained(it, nowElapsedMs) }
        val attempt = BubblePreviewDisplayAttempt(++nextAttemptId, this)
        activeAttempt = attempt
        return attempt
    }

    /** True only while [attempt] is the one current, untripped display token. */
    internal fun isCurrent(attempt: BubblePreviewDisplayAttempt): Boolean =
        !tripped && activeAttempt === attempt

    /**
     * Marks [attempt] visible. Repeating this call keeps the first visible
     * timestamp for the current visibility interval.
     */
    internal fun onVisible(attempt: BubblePreviewDisplayAttempt, nowElapsedMs: Long): Boolean {
        if (!isCurrent(attempt)) return false
        require(nowElapsedMs >= 0L) { "nowElapsedMs must not be negative" }
        if (!attempt.beginVisibility(nowElapsedMs)) return false
        settleIfSustained(attempt, nowElapsedMs)
        return true
    }

    /**
     * Ends the current visibility interval. A full healthy window settles as a
     * success; an early interval is discarded and starts from a fresh timestamp
     * if the same current attempt becomes visible again.
     */
    internal fun onVisibilityEnded(
        attempt: BubblePreviewDisplayAttempt,
        nowElapsedMs: Long,
    ): Boolean {
        if (!isCurrent(attempt)) return false
        require(nowElapsedMs >= 0L) { "nowElapsedMs must not be negative" }
        settleIfSustained(attempt, nowElapsedMs)
        attempt.endVisibilityMarker()
        return true
    }

    /**
     * Closes [attempt] permanently. A full visible window settles first; an
     * early window is discarded without becoming a failure. Later calls through
     * this token cannot affect a newer attempt or clear a trip.
     */
    internal fun onAttemptEnded(
        attempt: BubblePreviewDisplayAttempt,
        nowElapsedMs: Long,
    ): Boolean {
        if (!isCurrent(attempt)) return false
        require(nowElapsedMs >= 0L) { "nowElapsedMs must not be negative" }
        settleIfSustained(attempt, nowElapsedMs)
        attempt.markEnded()
        activeAttempt = null
        return true
    }

    /**
     * Returns true once the current display has been healthy for the full
     * sustained-success window. A caller can also leave it to the next event to
     * settle the same window; failure reporting settles before counting.
     */
    internal fun onConfirmSustained(
        attempt: BubblePreviewDisplayAttempt,
        nowElapsedMs: Long,
    ): Boolean {
        if (!isCurrent(attempt)) return false
        require(nowElapsedMs >= 0L) { "nowElapsedMs must not be negative" }
        settleIfSustained(attempt, nowElapsedMs)
        return attempt.isSustained()
    }

    /**
     * Counts this attempt once. A successful two-second window is settled
     * first, so a later failure becomes the first failure of a fresh streak.
     */
    internal fun onFailure(attempt: BubblePreviewDisplayAttempt): Boolean {
        if (!isCurrent(attempt)) return false
        consecutiveFailures += 1
        if (consecutiveFailures >= failureThreshold) {
            tripped = true
            activeAttempt = null
        }
        return true
    }

    private fun settleIfSustained(
        attempt: BubblePreviewDisplayAttempt,
        nowElapsedMs: Long,
    ) {
        if (!attempt.isVisible()) return
        val visibleSince = attempt.visibleSinceMs ?: return
        if (nowElapsedMs < visibleSince) return
        if (nowElapsedMs - visibleSince < sustainedSuccessMs) return
        attempt.markSustained()
        consecutiveFailures = 0
    }
}

/**
 * One explicit display attempt: create -> visible -> hidden/ended. It is a
 * one-shot failure token so noisy update callbacks cannot inflate the circuit
 * count. Every method is ignored by a stale token belonging to an older attempt.
 */
internal class BubblePreviewDisplayAttempt internal constructor(
    val id: Long,
    private val circuit: BubblePreviewFailureCircuit,
) {
    private enum class State {
        OPEN,
        VISIBLE,
        SUSTAINED,
        ENDED,
        FAILED,
    }

    private var state = State.OPEN
    internal var visibleSinceMs: Long? = null
        private set

    /**
     * Records visible evidence without creating a timer. Repeating the call
     * keeps the current interval's first timestamp.
     */
    fun markVisible(nowElapsedMs: Long): Boolean =
        circuit.onVisible(this, nowElapsedMs)

    /**
     * Ends the current visibility interval at the instant it actually ended.
     * Call this on hide; without it, a later reopen could mistake hidden time
     * for continuous visible time.
     */
    fun endVisibility(nowElapsedMs: Long): Boolean =
        circuit.onVisibilityEnded(this, nowElapsedMs)

    /**
     * Permanently ends this attempt. After this returns true, further reports
     * through the token are ignored and cannot affect a newer attempt.
     */
    fun endAttempt(nowElapsedMs: Long): Boolean =
        circuit.onAttemptEnded(this, nowElapsedMs)

    /** Returns true only when the two-second healthy window has elapsed. */
    fun confirmSustainedSuccess(nowElapsedMs: Long): Boolean =
        circuit.onConfirmSustained(this, nowElapsedMs)

    /**
     * Counts this attempt at most once. Calling it again, or after this token
     * has already failed or ended, has no effect on the circuit. Returns true
     * only for the call that actually incremented the circuit.
     */
    fun recordFailure(nowElapsedMs: Long): Boolean {
        if (!circuit.isCurrent(this)) return false
        if (state == State.FAILED || state == State.ENDED) return false
        require(nowElapsedMs >= 0L) { "nowElapsedMs must not be negative" }
        circuit.onConfirmSustained(this, nowElapsedMs)
        if (state == State.FAILED || state == State.ENDED) return false
        state = State.FAILED
        return circuit.onFailure(this)
    }

    /** True once this attempt has failed and can no longer attach. */
    fun isFailed(): Boolean = state == State.FAILED

    internal fun beginVisibility(nowElapsedMs: Long): Boolean = when (state) {
        State.OPEN -> {
            visibleSinceMs = nowElapsedMs
            state = State.VISIBLE
            true
        }
        State.VISIBLE -> {
            if (visibleSinceMs == null) visibleSinceMs = nowElapsedMs
            true
        }
        State.SUSTAINED -> true
        State.ENDED, State.FAILED -> false
    }

    internal fun endVisibilityMarker() {
        visibleSinceMs = null
        if (state == State.VISIBLE) state = State.OPEN
    }

    internal fun isVisible(): Boolean = state == State.VISIBLE

    internal fun isSustained(): Boolean = state == State.SUSTAINED

    internal fun markSustained() {
        if (state == State.VISIBLE) state = State.SUSTAINED
    }

    internal fun markEnded() {
        if (state != State.FAILED) state = State.ENDED
        visibleSinceMs = null
    }
}
