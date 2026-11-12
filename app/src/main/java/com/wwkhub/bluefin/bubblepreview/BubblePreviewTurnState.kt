package com.wwkhub.bluefin.bubblepreview

/** Lifecycle of the single overlay turn, independent of Android ChatSession. */
enum class BubbleTurnPhase { THINKING, STREAMING, COMPLETE, STOPPED, FAILED }

/** What the desktop window should draw now. */
enum class BubbleVisibleState { THINKING, TYPING, HOLD, COLLAPSED, ERROR, HIDDEN }

/** Permanent suppression reasons; all invalidate only the current token. */
enum class BubbleSuppressionReason {
    DISMISS, PREF_OFF, CLICK_THROUGH, PANEL, APP_FOREGROUND, CLEAR, TIMEOUT,
}

enum class BubbleTurnStatus { NONE, STOPPED, FAILED }

/** Complete immutable presentation snapshot; never a text delta. */
data class BubbleTurnSnapshot(
    val token: Long?,
    val phase: BubbleTurnPhase?,
    val visibleState: BubbleVisibleState,
    val userSummary: String,
    val roleName: String,
    val visibleText: String,
    val visibleGraphemes: Int,
    val targetGraphemes: Int,
    val pendingGrapheme: Boolean,
    val hasMore: Boolean,
    val overflow: Boolean,
    val status: BubbleTurnStatus,
    val textChanged: Boolean,
    val suppressed: Boolean = false,
    val suppressionReason: BubbleSuppressionReason? = null,
    val geometrySuspended: Boolean = false,
) {
    val isVisible: Boolean get() = visibleState != BubbleVisibleState.HIDDEN
    val isTerminal: Boolean
        get() = phase == BubbleTurnPhase.COMPLETE ||
            phase == BubbleTurnPhase.STOPPED || phase == BubbleTurnPhase.FAILED
    val isFailureChip: Boolean
        get() = phase == BubbleTurnPhase.FAILED && visibleState == BubbleVisibleState.COLLAPSED
    val retainedReplyGraphemes: Int
        get() = targetGraphemes + if (pendingGrapheme) 1 else 0
}

/**
 * Pure one-turn overlay state machine.
 *
 * Text input is always the current turn's cumulative assistant snapshot, never
 * a delta. The private [BubblePreviewText] keeps only its finite 240-cluster
 * buffer; this class never stores the cumulative reply, history or snapshots.
 * It has no Android, network, send, stop, callback, thread or timer dependency.
 * The caller supplies one monotonic logical clock and owns deadline scheduling.
 */
class BubblePreviewTurnState {
    private val preview = BubblePreviewText()
    private var token: Long? = null
    private var phase: BubbleTurnPhase? = null
    private var status: BubbleTurnStatus = BubbleTurnStatus.NONE
    private var userSummary: String = ""
    private var roleName: String = ""
    private var suppressed = false
    private var suppressionReason: BubbleSuppressionReason? = null
    private var geometrySuspended = false
    private var holdUntil = -1L
    private var collapsedUntil = -1L
    private var errorUntil = -1L
    private var failureChipUntil = -1L

    /** Only entry point that accepts a turn; replaces any old/suppressed token. */
    fun beginTurn(token: Long, userSummary: String, roleName: String, nowMillis: Long): BubbleTurnSnapshot {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        this.token = token
        this.userSummary = boundedLabel(userSummary, MAX_SUMMARY_GRAPHEMES)
        this.roleName = boundedLabel(roleName, MAX_ROLE_GRAPHEMES)
        phase = BubbleTurnPhase.THINKING
        status = BubbleTurnStatus.NONE
        suppressed = false
        suppressionReason = null
        geometrySuspended = false
        clearDeadlines()
        preview.resetForNewTurn()
        return snapshotAt(nowMillis, textChanged = false)
    }

    /** Feed the current turn's cumulative assistant text. */
    fun onSnapshot(
        token: Long,
        cumulativeText: String,
        isTerminal: Boolean,
        nowMillis: Long,
    ): BubbleTurnSnapshot {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        if (!isCurrent(token) || suppressed) return snapshotAt(nowMillis, false)
        if (phase == BubbleTurnPhase.STOPPED || phase == BubbleTurnPhase.FAILED) return snapshotAt(nowMillis, false)
        val frame = preview.onSnapshot(cumulativeText, isTerminal, nowMillis)
        if (frame.staleInput) return snapshotAt(nowMillis, false)
        if (isTerminal) {
            phase = BubbleTurnPhase.COMPLETE
            if (frame.isComplete) startHold(nowMillis)
        } else if (frame.targetGraphemes > 0 || frame.visibleGraphemes > 0 || frame.overflow) {
            phase = BubbleTurnPhase.STREAMING
        }
        return snapshotAt(nowMillis, frame.textChanged)
    }

    /** The single caller-driven logical tick; never sleeps or spawns work. */
    fun onTick(token: Long, nowMillis: Long): BubbleTurnSnapshot {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        if (!isCurrent(token) || suppressed) return snapshotAt(nowMillis, false)
        val frame = preview.onTick(nowMillis)
        if (phase == BubbleTurnPhase.THINKING &&
            (frame.targetGraphemes > 0 || frame.visibleGraphemes > 0 || frame.overflow)
        ) phase = BubbleTurnPhase.STREAMING
        if (phase == BubbleTurnPhase.COMPLETE && frame.isComplete) startHold(nowMillis)
        return snapshotAt(nowMillis, frame.textChanged)
    }

    /** Stop path: flush finite received text at once, then HOLD/COLLAPSE. */
    fun onStop(token: Long, cumulativeText: String, nowMillis: Long): BubbleTurnSnapshot {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        if (!isCurrent(token) || suppressed) return snapshotAt(nowMillis, false)
        if (phase == BubbleTurnPhase.COMPLETE || phase == BubbleTurnPhase.STOPPED ||
            phase == BubbleTurnPhase.FAILED) return snapshotAt(nowMillis, false)
        val frame = preview.onInterrupted(cumulativeText, nowMillis)
        phase = BubbleTurnPhase.STOPPED
        status = BubbleTurnStatus.STOPPED
        holdUntil = nowMillis + HOLD_MILLIS
        collapsedUntil = holdUntil + COLLAPSED_MILLIS
        errorUntil = -1L
        failureChipUntil = -1L
        return snapshotAt(nowMillis, frame.textChanged)
    }

    /**
     * Error path: flush received text immediately and enter ERROR for 8 s.
     * No error message, stack or raw response is accepted or stored here.
     */
    fun onError(token: Long, cumulativeText: String, nowMillis: Long): BubbleTurnSnapshot {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        if (!isCurrent(token) || suppressed) return snapshotAt(nowMillis, false)
        if (phase == BubbleTurnPhase.COMPLETE || phase == BubbleTurnPhase.STOPPED ||
            phase == BubbleTurnPhase.FAILED) return snapshotAt(nowMillis, false)
        val frame = preview.onInterrupted(cumulativeText, nowMillis)
        phase = BubbleTurnPhase.FAILED
        status = BubbleTurnStatus.FAILED
        errorUntil = nowMillis + ERROR_MILLIS
        failureChipUntil = errorUntil + FAILURE_CHIP_MILLIS
        holdUntil = -1L
        collapsedUntil = -1L
        return snapshotAt(nowMillis, frame.textChanged)
    }

    /** Expand a completed/stopped chip from cached text; no request/replay. */
    fun onCollapsedClick(token: Long, nowMillis: Long): BubbleTurnSnapshot {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        if (!isCurrent(token) || suppressed) return snapshotAt(nowMillis, false)
        if (evaluateDeadlines(nowMillis) != BubbleVisibleState.COLLAPSED ||
            (phase != BubbleTurnPhase.COMPLETE && phase != BubbleTurnPhase.STOPPED)
        ) return snapshotAt(nowMillis, false)
        holdUntil = nowMillis + HOLD_MILLIS
        collapsedUntil = holdUntil + COLLAPSED_MILLIS
        return snapshotAt(nowMillis, false)
    }

    /** ×, preference off, click-through, panel, app foreground, clear, timeout. */
    fun suppress(reason: BubbleSuppressionReason, nowMillis: Long): BubbleTurnSnapshot {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        if (token != null && !suppressed) {
            suppressed = true
            suppressionReason = reason
            clearDeadlines()
            preview.resetForNewTurn()
        }
        return snapshotAt(nowMillis, false)
    }

    /**
     * Hide presentation while geometry is unavailable, preserving absolute
     * deadlines. Re-enabling reveals only remaining time, never a fresh hold.
     */
    fun setGeometryAvailable(available: Boolean, nowMillis: Long): BubbleTurnSnapshot {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        geometrySuspended = !available
        return snapshotAt(nowMillis, false)
    }

    /** Evaluate deadlines/presentation without advancing text. */
    fun snapshot(nowMillis: Long): BubbleTurnSnapshot {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        return snapshotAt(nowMillis, false)
    }

    /**
     * Absolute logical deadline the host must schedule exactly once, or null
     * when only text ticks (or nothing) remain. This deliberately exposes the
     * HOLD/error/chip truth instead of running a 33 ms idle loop after the
     * preview has caught up.
     */
    fun nextDeadlineMillis(nowMillis: Long): Long? {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        if (suppressed || token == null || phase == null) return null
        val candidates = when (phase) {
            BubbleTurnPhase.FAILED -> listOf(errorUntil, failureChipUntil)
            BubbleTurnPhase.COMPLETE, BubbleTurnPhase.STOPPED -> listOf(holdUntil, collapsedUntil)
            else -> emptyList()
        }
        return candidates.filter { it > nowMillis }.minOrNull()
    }

    private fun isCurrent(candidate: Long): Boolean = token == candidate && phase != null

    private fun startHold(nowMillis: Long) {
        if (holdUntil < 0L) {
            holdUntil = nowMillis + HOLD_MILLIS
            collapsedUntil = holdUntil + COLLAPSED_MILLIS
        }
    }

    private fun evaluateDeadlines(nowMillis: Long): BubbleVisibleState {
        if (suppressed) return BubbleVisibleState.HIDDEN
        when (phase) {
            null -> return BubbleVisibleState.HIDDEN
            BubbleTurnPhase.THINKING -> return BubbleVisibleState.THINKING
            BubbleTurnPhase.STREAMING -> return BubbleVisibleState.TYPING
            BubbleTurnPhase.FAILED -> {
                if (failureChipUntil >= 0L && nowMillis >= failureChipUntil) {
                    suppressAtTimeout()
                    return BubbleVisibleState.HIDDEN
                }
                return if (errorUntil >= 0L && nowMillis >= errorUntil) {
                    BubbleVisibleState.COLLAPSED
                } else {
                    BubbleVisibleState.ERROR
                }
            }
            BubbleTurnPhase.COMPLETE,
            BubbleTurnPhase.STOPPED,
            -> {
                if (holdUntil < 0L) {
                    return if (phase == BubbleTurnPhase.STOPPED) {
                        BubbleVisibleState.HOLD
                    } else {
                        BubbleVisibleState.TYPING
                    }
                }
                if (collapsedUntil >= 0L && nowMillis >= collapsedUntil) {
                    suppressAtTimeout()
                    return BubbleVisibleState.HIDDEN
                }
                return if (nowMillis >= holdUntil) {
                    BubbleVisibleState.COLLAPSED
                } else {
                    BubbleVisibleState.HOLD
                }
            }
        }
    }

    private fun suppressAtTimeout() {
        suppressed = true
        suppressionReason = BubbleSuppressionReason.TIMEOUT
        clearDeadlines()
        preview.resetForNewTurn()
    }

    private fun snapshotAt(nowMillis: Long, textChanged: Boolean): BubbleTurnSnapshot {
        val state = evaluateDeadlines(nowMillis)
        val frame = preview.frame()
        return BubbleTurnSnapshot(
            token = token,
            phase = phase,
            visibleState = if (geometrySuspended) BubbleVisibleState.HIDDEN else state,
            userSummary = userSummary,
            roleName = roleName,
            visibleText = frame.visibleText,
            visibleGraphemes = frame.visibleGraphemes,
            targetGraphemes = frame.targetGraphemes,
            pendingGrapheme = frame.pendingGrapheme,
            hasMore = frame.hasMore,
            overflow = frame.overflow,
            status = status,
            textChanged = textChanged,
            suppressed = suppressed,
            suppressionReason = suppressionReason,
            geometrySuspended = geometrySuspended,
        )
    }

    private fun clearDeadlines() {
        holdUntil = -1L
        collapsedUntil = -1L
        errorUntil = -1L
        failureChipUntil = -1L
    }

    private fun boundedLabel(value: String, maxGraphemes: Int): String {
        if (value.isEmpty()) return ""
        return BubblePreviewText(maxGraphemes = maxGraphemes)
            .onSnapshot(value, isTerminal = true, nowMillis = 0L, flushImmediately = true)
            .visibleText
    }

    companion object {
        const val HOLD_MILLIS: Long = 8_000L
        const val COLLAPSED_MILLIS: Long = 30_000L
        const val ERROR_MILLIS: Long = 8_000L
        const val FAILURE_CHIP_MILLIS: Long = 30_000L
        const val MAX_SUMMARY_GRAPHEMES: Int = 80
        const val MAX_ROLE_GRAPHEMES: Int = 40
        const val MAX_REPLY_GRAPHEMES: Int = BubblePreviewText.MAX_VISIBLE_GRAPHEMES
    }
}
