package com.wwkhub.bluefin.bubblepreview

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure JVM sizing for the compact read-only preview.
 *
 * All inputs are immutable. The platform host measures real font metrics and
 * passes them in; this policy never asks the host for a screen size and never
 * touches a View. dp/sp are converted here with the supplied density and the
 * platform's scaledDensity so fontScale is honoured without hard-coded px.
 */
internal enum class BubblePreviewAction { STOP, VIEW_ALL, RECORDS, CLOSE }

internal data class BubblePreviewScaleRequest(
    val characterScale: Float,
    val density: Float,
    val scaledDensity: Float,
    val safeWidthPx: Int,
    val safeHeightPx: Int,
    val hasUserSummary: Boolean,
    val realBodyLinePx: Float? = null,
    val realNameLinePx: Float? = null,
    val actionCount: Int = 2,
    /** Paint-measured touch widths (text + padding, already >= 44 dp). */
    val realActionWidthsPx: List<Float> = emptyList(),
)

internal data class BubblePreviewLayoutPlan(
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val bodyLines: Int,
    val includeUserSummary: Boolean,
    val bodyTextPx: Float,
    val labelTextPx: Float,
    val padHorizontalPx: Float,
    val padVerticalPx: Float,
    val zoneGapPx: Float,
    val actionGapPx: Float,
    val actionRowPx: Int,
    val cornerRadiusPx: Float,
    val bodyLineHeightPx: Float,
    val nameLineHeightPx: Float,
    val userLineHeightPx: Float,
    val contentWidthPx: Float,
    /**
     * Worst-case tail extension inside [windowWidthPx]/[windowHeightPx].
     *
     * The layout always reserves one seam-overlapped tail on one edge. When
     * placement hides the tail or puts it on the other axis, the character body
     * grows into this reserve instead of leaving a transparent margin.
     */
    val tailBudgetPx: Float = 0f,
    val actionLabels: List<BubblePreviewAction> = listOf(
        BubblePreviewAction.RECORDS,
        BubblePreviewAction.CLOSE,
    ),
    /** 0.8 speech bubble mode; null keeps the legacy 0.7 preview plan. */
    val speechMode: SpeechBubbleMode? = null,
    val showReplyBody: Boolean = true,
    val showActions: Boolean = false,
    val chipWidthPx: Int = 0,
    val chipHeightPx: Int = 0,
    val speechScale: Float = 0.85f,
) {
    val totalTextLines: Int get() = bodyLines + if (includeUserSummary) 1 else 0
}

internal object BubblePreviewScalePolicy {
    const val BODY_BASE_SP = 11f
    const val BODY_SPAN_SP = 5f
    const val LABEL_MIN_SP = 11f
    const val PREFERRED_WIDTH_BASE_DP = 160f
    const val PREFERRED_WIDTH_SPAN_DP = 120f
    const val WIDTH_SAFE_FRACTION = 0.72f
    const val HEIGHT_CAP_DP = 220f
    const val HEIGHT_SAFE_FRACTION = 0.28f
    const val PAD_H_DP = 12f
    const val PAD_V_DP = 10f
    const val ZONE_GAP_DP = 2f
    const val ACTION_GAP_DP = 8f
    const val ACTION_TARGET_DP = 44f
    const val CORNER_DP = 12f
    const val LINE_HEIGHT_REFERENCE = 1.30f
    const val TAIL_SHORT_DP = 6f
    const val TAIL_SEAM_DP = 1f

    /** Seam-overlapped tail extension: `6k - 1` dp on either axis. */
    fun tailBudgetDp(characterScale: Float): Float =
        (TAIL_SHORT_DP * kForScale(characterScale)).coerceAtLeast(TAIL_SEAM_DP) - TAIL_SEAM_DP

    fun tForScale(characterScale: Float): Float =
        ((characterScale.coerceIn(0.50f, 1.30f) - 0.50f) / 0.80f).coerceIn(0f, 1f)

    fun kForScale(characterScale: Float): Float =
        (characterScale.coerceIn(0.50f, 1.30f) / 0.85f).coerceIn(0.85f, 1.20f)

    fun bodyTextSp(characterScale: Float): Float =
        BODY_BASE_SP + BODY_SPAN_SP * tForScale(characterScale)

    fun labelTextSp(characterScale: Float): Float =
        max(LABEL_MIN_SP, bodyTextSp(characterScale) - 1f)

    fun preferredWidthDp(characterScale: Float): Float =
        PREFERRED_WIDTH_BASE_DP + PREFERRED_WIDTH_SPAN_DP * tForScale(characterScale)

    /**
     * Returns the first plan that fits the fixed height cap: 4 body lines with
     * the user summary, 2 lines with it, then 2 lines without it. There is no
     * unbounded retry and no window scaling: no plan means "record panel".
     */
    fun plan(request: BubblePreviewScaleRequest): BubblePreviewLayoutPlan? {
        if (!request.characterScale.isFinite()) return null
        if (!request.density.isFinite() || request.density <= 0f) return null
        if (!request.scaledDensity.isFinite() || request.scaledDensity <= 0f) return null
        if (request.safeWidthPx <= 0 || request.safeHeightPx <= 0) return null

        val s = request.characterScale.coerceIn(0.50f, 1.30f)
        val density = request.density
        val bodySp = bodyTextSp(s)
        val labelSp = labelTextSp(s)
        val k = kForScale(s)
        val bodyTextPx = bodySp * request.scaledDensity
        val labelTextPx = labelSp * request.scaledDensity
        val bodyLine = max(
            request.realBodyLinePx?.takeIf { it.isFinite() && it > 0f } ?: 0f,
            bodySp * LINE_HEIGHT_REFERENCE * request.scaledDensity,
        )
        val nameLine = max(
            request.realNameLinePx?.takeIf { it.isFinite() && it > 0f } ?: 0f,
            labelSp * LINE_HEIGHT_REFERENCE * request.scaledDensity,
        )

        val padH = PAD_H_DP * k * density
        val padV = PAD_V_DP * k * density
        val zoneGap = ZONE_GAP_DP * k * density
        val actionGap = ACTION_GAP_DP * k * density
        val actionRow = ceil(ACTION_TARGET_DP * density).toInt().coerceAtLeast(1)
        val measuredActions = request.realActionWidthsPx
            .filter { it.isFinite() && it > 0f }
            .take(3)
            .map { max(ceil(it.toDouble()).toInt(), actionRow) }
        val actionWidths = if (measuredActions.size == request.actionCount.coerceIn(0, 3) &&
            measuredActions.isNotEmpty()
        ) {
            measuredActions
        } else {
            List(request.actionCount.coerceIn(0, 3)) { actionRow }
        }
        val actionTotal = actionWidths.sum().toFloat() +
            (actionWidths.size - 1).coerceAtLeast(0) * (2f * density)
        val tailBudget = tailBudgetDp(s) * density
        val preferredWidth = preferredWidthDp(s) * density
        // Integer floor keeps every returned pixel dimension inside the safe
        // fraction; the tail reserve is part of the final window width.
        val maxWidth = floor(request.safeWidthPx * WIDTH_SAFE_FRACTION.toDouble()).toInt()
            .coerceAtLeast(1)
        // Grow just enough for the measured action row (still capped by the
        // safe fraction) instead of truncating labels or shrinking their text.
        val neededWidth = ceil((actionTotal + 2f * padH + tailBudget).toDouble()).toInt()
        val width = min(maxWidth, max(ceil(preferredWidth.toDouble()).toInt(), neededWidth))
            .coerceAtLeast(1)
        // A side tail consumes part of the body width. Actions are measured
        // against the remaining content width, never against a stale plan that
        // ignored the tail.
        val contentWidth = width - 2f * padH - tailBudget
        if (contentWidth < max(actionTotal, 1f)) return null
        val heightCapPx = floor(
            min(HEIGHT_CAP_DP * density, request.safeHeightPx * HEIGHT_SAFE_FRACTION)
                .toDouble(),
        ).toInt()
        if (heightCapPx <= 0) return null

        val candidates = buildList {
            if (request.hasUserSummary) {
                add(4 to true)
                add(2 to true)
                add(2 to false)
            } else {
                add(4 to false)
                add(2 to false)
            }
        }
        for ((lines, withUser) in candidates) {
            val userHeight = if (withUser) 2f * padV + bodyLine else 0f
            val userGap = if (withUser) zoneGap else 0f
            val charHeight = 2f * padV + nameLine + lines * bodyLine + actionGap + actionRow
            val height = userHeight + userGap + charHeight + tailBudget
            val heightPx = ceil(height.toDouble()).toInt().coerceAtLeast(1)
            // Compare the *final integer* window height with the floored cap.
            // Checking the pre-rasterised float allows ceil() to cross the cap.
            if (heightPx <= heightCapPx) {
                return BubblePreviewLayoutPlan(
                    windowWidthPx = width,
                    windowHeightPx = heightPx,
                    bodyLines = lines,
                    includeUserSummary = withUser,
                    bodyTextPx = bodyTextPx,
                    labelTextPx = labelTextPx,
                    padHorizontalPx = padH,
                    padVerticalPx = padV,
                    zoneGapPx = zoneGap,
                    actionGapPx = actionGap,
                    actionRowPx = actionRow,
                    cornerRadiusPx = CORNER_DP * k * density,
                    bodyLineHeightPx = bodyLine,
                    nameLineHeightPx = nameLine,
                    userLineHeightPx = bodyLine,
                    contentWidthPx = contentWidth,
                    tailBudgetPx = tailBudget,
                )
            }
        }
        return null
    }

    /**
     * Ordered fallback plans for placement. At most two plans are returned: the
     * preferred full plan and, when it differs, the compact two-line retry.
     */
    fun fallbackPlans(request: BubblePreviewScaleRequest): List<BubblePreviewLayoutPlan> {
        val first = plan(request) ?: return emptyList()
        val compactRequest = request.copy(
            // Force the compact retry even when the first plan already used
            // fewer than four lines (for example a short safe height).
            hasUserSummary = request.hasUserSummary,
        )
        val compact = compactPlan(compactRequest, first) ?: return listOf(first)
        return if (compact.windowHeightPx == first.windowHeightPx &&
            compact.bodyLines == first.bodyLines &&
            compact.includeUserSummary == first.includeUserSummary
        ) {
            listOf(first)
        } else {
            listOf(first, compact)
        }
    }

    private fun compactPlan(
        request: BubblePreviewScaleRequest,
        first: BubblePreviewLayoutPlan,
    ): BubblePreviewLayoutPlan? {
        // A compact retry is only meaningful when the first plan used 4 body
        // lines or included the user summary. It deliberately repeats the same
        // width and metrics but drops one or both optional rows.
        if (first.bodyLines <= 2 && !first.includeUserSummary) return null
        val s = request.characterScale.coerceIn(0.50f, 1.30f)
        val density = request.density
        val k = kForScale(s)
        val padH = first.padHorizontalPx
        val padV = first.padVerticalPx
        val zoneGap = first.zoneGapPx
        val actionGap = first.actionGapPx
        val actionRow = first.actionRowPx
        val width = first.windowWidthPx
        val heightCapPx = floor(
            min(HEIGHT_CAP_DP * density, request.safeHeightPx * HEIGHT_SAFE_FRACTION)
                .toDouble(),
        ).toInt()
        val tailBudget = first.tailBudgetPx.takeIf { it.isFinite() && it >= 0f }
            ?: (tailBudgetDp(s) * density)
        val variants = if (first.includeUserSummary) {
            listOf(2 to true, 2 to false)
        } else {
            listOf(2 to false)
        }
        variants.forEach { (lines, withUser) ->
            val userHeight = if (withUser) 2f * padV + first.userLineHeightPx else 0f
            val userGap = if (withUser) zoneGap else 0f
            val charHeight = 2f * padV + first.nameLineHeightPx +
                lines * first.bodyLineHeightPx + actionGap + actionRow
            val height = userHeight + userGap + charHeight + tailBudget
            val heightPx = ceil(height.toDouble()).toInt().coerceAtLeast(1)
            if (heightPx <= heightCapPx) {
                return first.copy(
                    windowHeightPx = heightPx,
                    bodyLines = lines,
                    includeUserSummary = withUser,
                    contentWidthPx = width - 2f * padH - tailBudget,
                    tailBudgetPx = tailBudget,
                )
            }
        }
        return null
    }
}