package com.wwkhub.bluefin.bubblepreview

/**
 * Trusted IME state for the first attach and later geometry epochs.
 *
 * `UNKNOWN` is not "keyboard height 0": before the lazily-installed insets
 * callback has delivered one real value, the first attach must wait and then
 * restore the record panel if no trustworthy value arrives. There is no fixed
 * 300 dp guess and no polling loop.
 */
internal enum class BubblePreviewImeState { UNKNOWN, VISIBLE, HIDDEN }

internal class BubblePreviewImeGate {
    var state: BubblePreviewImeState = BubblePreviewImeState.UNKNOWN
        private set

    var bottomPx: Int = 0
        private set

    fun onInsets(bottomPx: Int, visible: Boolean) {
        val safeBottom = bottomPx.coerceAtLeast(0)
        this.bottomPx = safeBottom
        state = when {
            visible || safeBottom > 0 -> BubblePreviewImeState.VISIBLE
            else -> BubblePreviewImeState.HIDDEN
        }
    }

    fun isHiddenAndKnown(): Boolean = state == BubblePreviewImeState.HIDDEN

    fun isTrustworthy(): Boolean = state != BubblePreviewImeState.UNKNOWN

    fun imeBottomInsets(): BubblePreviewInsets =
        BubblePreviewInsets(0, 0, 0, if (state == BubblePreviewImeState.VISIBLE) bottomPx else 0)

    fun reset() {
        state = BubblePreviewImeState.UNKNOWN
        bottomPx = 0
    }
}

/**
 * Bounded first-attach flow. It proves that an accepted STARTED from an expanded
 * full-screen orb panel can reach an attached compact bubble only after:
 *
 * 1. preflight using the projected collapsed button bounds;
 * 2. explicit host collapse + IME release;
 * 3. a post-collapse frame with trustworthy screen/IME geometry;
 * 4. a final eligibility re-check.
 *
 * Waiting for geometry is finite (a callback count and a deadline). A canceled
 * generation can never later attach. Invalid geometry is a visibility wait, not
 * a local failure count.
 */
internal enum class BubblePreviewAttachDecision {
    PRECHECK_FAILED,
    AWAIT_COLLAPSE,
    AWAIT_GEOMETRY,
    READY,
    TIMED_OUT,
    CANCELLED,
}

internal class BubblePreviewFirstAttachFlow(
    // Roughly one second of 80 ms layout frames: enough for a real IME hide /
    // insets callback, still a hard finite budget.
    private val maxGeometryAttempts: Int = 16,
    private val timeoutMs: Long = 1_500L,
) {
    init {
        require(maxGeometryAttempts >= 1) { "maxGeometryAttempts must be positive" }
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
    }

    private var active = false
    private var preflightPassed = false
    private var collapsed = false
    private var startedAtMs = -1L
    private var geometryAttempts = 0

    fun begin(nowMs: Long, preflightFits: Boolean): BubblePreviewAttachDecision {
        require(nowMs >= 0L) { "nowMs must not be negative" }
        active = true
        preflightPassed = preflightFits
        collapsed = false
        startedAtMs = nowMs
        geometryAttempts = 0
        return if (preflightFits) {
            BubblePreviewAttachDecision.AWAIT_COLLAPSE
        } else {
            active = false
            BubblePreviewAttachDecision.PRECHECK_FAILED
        }
    }

    fun onPanelCollapsed(nowMs: Long): BubblePreviewAttachDecision {
        require(nowMs >= 0L) { "nowMs must not be negative" }
        if (!active) return BubblePreviewAttachDecision.CANCELLED
        if (expired(nowMs)) return timeout()
        if (!preflightPassed) return BubblePreviewAttachDecision.PRECHECK_FAILED
        collapsed = true
        return BubblePreviewAttachDecision.AWAIT_GEOMETRY
    }

    /**
     * One post-collapse layout/frame callback. Only a trustworthy geometry and
     * IME snapshot can reach [BubblePreviewAttachDecision.READY]; the finite
     * attempt budget prevents an endless wait for a callback that never comes.
     */
    fun onGeometry(
        nowMs: Long,
        trustworthy: Boolean,
    ): BubblePreviewAttachDecision {
        require(nowMs >= 0L) { "nowMs must not be negative" }
        if (!active) return BubblePreviewAttachDecision.CANCELLED
        if (expired(nowMs)) return timeout()
        if (!preflightPassed) return BubblePreviewAttachDecision.PRECHECK_FAILED
        if (!collapsed) return BubblePreviewAttachDecision.AWAIT_COLLAPSE
        if (trustworthy) {
            active = false
            return BubblePreviewAttachDecision.READY
        }
        geometryAttempts += 1
        if (geometryAttempts >= maxGeometryAttempts) {
            active = false
            return BubblePreviewAttachDecision.TIMED_OUT
        }
        return BubblePreviewAttachDecision.AWAIT_GEOMETRY
    }

    fun onDeadline(nowMs: Long): BubblePreviewAttachDecision {
        require(nowMs >= 0L) { "nowMs must not be negative" }
        if (!active) return BubblePreviewAttachDecision.CANCELLED
        return if (expired(nowMs)) timeout() else BubblePreviewAttachDecision.AWAIT_GEOMETRY
    }

    fun cancel(): BubblePreviewAttachDecision {
        active = false
        return BubblePreviewAttachDecision.CANCELLED
    }

    private fun expired(nowMs: Long): Boolean =
        startedAtMs >= 0L && nowMs - startedAtMs >= timeoutMs

    private fun timeout(): BubblePreviewAttachDecision {
        active = false
        return BubblePreviewAttachDecision.TIMED_OUT
    }
}

/**
 * Pure chat-panel placement. The existing baseline panel keeps its own
 * top/left layout; only the chat panel's top/height is adapted to the actually
 * visible root frame above the IME/system bars. There is no hard-coded 300 dp
 * IME.
 *
 * When the requested top would leave the controls below the visible bottom,
 * the panel is first repositioned upward. If the full panel still cannot fit,
 * [compact] is returned so the host can hide optional header/status/history
 * while keeping the input, send/stop and full-app/collapse controls.
 */
internal data class BubblePreviewChatPanelFrame(
    val topPx: Int,
    val heightPx: Int,
    val compact: Boolean,
)

internal object BubblePreviewPanelGeometry {
    fun chatPanelHeightPx(
        panelTopPx: Int,
        baselineHeightPx: Int,
        visibleFrameBottomPx: Int,
        bottomMarginPx: Int,
        minimumControlsHeightPx: Int,
    ): Int? {
        if (panelTopPx < 0 || baselineHeightPx <= 0 || bottomMarginPx < 0) return null
        if (minimumControlsHeightPx <= 0) return null
        if (visibleFrameBottomPx <= 0) return null
        val available = visibleFrameBottomPx - bottomMarginPx - panelTopPx
        if (available < minimumControlsHeightPx) return null
        return minOf(baselineHeightPx, available)
    }

    fun chatPanelFrame(
        requestedTopPx: Int,
        baselineHeightPx: Int,
        visibleTopPx: Int,
        visibleFrameBottomPx: Int,
        bottomMarginPx: Int,
        compactMinimumHeightPx: Int,
    ): BubblePreviewChatPanelFrame? {
        if (baselineHeightPx <= 0 || bottomMarginPx < 0) return null
        if (compactMinimumHeightPx <= 0) return null
        if (visibleFrameBottomPx <= visibleTopPx) return null
        val visibleHeight = visibleFrameBottomPx - visibleTopPx
        if (visibleHeight <= 0) return null
        val available = visibleFrameBottomPx - bottomMarginPx - visibleTopPx
        if (available <= 0) return null

        // Never leave even the compact controls below the keyboard. If the
        // physical visible height is smaller than the minimum control stack,
        // use the whole frame (still anchored on screen) instead of returning
        // an empty limbo that leaves the baseline panel behind the IME.
        if (available < compactMinimumHeightPx) {
            return BubblePreviewChatPanelFrame(
                topPx = visibleTopPx,
                heightPx = visibleHeight.coerceAtLeast(1),
                compact = true,
            )
        }

        val maxTopForFull = visibleFrameBottomPx - bottomMarginPx - baselineHeightPx
        if (requestedTopPx in visibleTopPx..maxTopForFull) {
            return BubblePreviewChatPanelFrame(
                topPx = requestedTopPx,
                heightPx = baselineHeightPx,
                compact = false,
            )
        }
        if (baselineHeightPx <= available) {
            // Enough room overall, just not at the requested Y (for example a
            // high orb position or 1.5x font metrics). Reposition instead of
            // returning null and leaving half the controls under the keyboard.
            val top = requestedTopPx.coerceIn(visibleTopPx, maxTopForFull)
            return BubblePreviewChatPanelFrame(
                topPx = top,
                heightPx = baselineHeightPx,
                compact = false,
            )
        }

        val maxTopForCompact = visibleFrameBottomPx - bottomMarginPx - compactMinimumHeightPx
        val top = requestedTopPx.coerceIn(visibleTopPx, maxTopForCompact)
        val height = (visibleFrameBottomPx - bottomMarginPx - top)
            .coerceAtLeast(compactMinimumHeightPx)
            .coerceAtMost(visibleHeight)
        return BubblePreviewChatPanelFrame(
            topPx = top,
            heightPx = height,
            compact = true,
        )
    }
}
