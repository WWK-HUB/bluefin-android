package com.wwkhub.bluefin.bubblepreview

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Which face of the single content-sized speech window is active. */
internal enum class SpeechBubbleMode { INPUT, REPLY }

/** The bounded input face state. Never contains history, keys or a response. */
internal data class SpeechInputSnapshot(
    val text: String = "",
    val sendEnabled: Boolean = false,
)

/** Immutable sizing input for the 0.8 head-top speech bubble. */
internal data class SpeechBubbleScaleRequest(
    val mode: SpeechBubbleMode,
    val visibleState: BubbleVisibleState? = null,
    val characterScale: Float,
    val density: Float,
    val scaledDensity: Float,
    val safeWidthPx: Int,
    val safeHeightPx: Int,
    val landscape: Boolean = false,
    val hasUserChip: Boolean = true,
    val inputLines: Int = 1,
    val realBodyLinePx: Float? = null,
    val realChipLinePx: Float? = null,
)

/**
 * Pure 0.8 speech-bubble sizing, deliberately independent of Android views and
 * of any host screen callback. The values mirror bf_speech_dimens/config and
 *方案 4.4: portrait input <= min(280dp, 72%), 1..3 lines; reply <= 4 lines;
 * stack <= min(220dp, 36%); landscape input 1 line, reply 2 lines, stack
 * <= min(160dp, 50%). Touch targets stay at least 48dp.
 */
internal object SpeechBubbleLayoutPolicy {
    const val BODY_BASE_SP = 11f
    const val BODY_SPAN_SP = 5f
    const val CHIP_BASE_SP = 11f
    const val CHIP_SPAN_SP = 4f
    const val WIDTH_BASE_DP = 180.625f
    const val WIDTH_SPAN_DP = 74.375f
    const val INPUT_WIDTH_CAP_DP = 280f
    const val CHIP_WIDTH_CAP_DP = 200f
    const val WIDTH_SAFE_FRACTION = 0.72f
    const val CHIP_WIDTH_SAFE_FRACTION = 0.50f
    const val PORTRAIT_STACK_CAP_DP = 220f
    const val LANDSCAPE_STACK_CAP_DP = 160f
    const val PORTRAIT_SAFE_FRACTION = 0.36f
    const val LANDSCAPE_SAFE_FRACTION = 0.50f
    const val INPUT_MAX_LINES_PORTRAIT = 3
    const val INPUT_MAX_LINES_LANDSCAPE = 1
    const val REPLY_MAX_LINES_PORTRAIT = 4
    const val REPLY_MAX_LINES_LANDSCAPE = 2
    const val ACTION_TARGET_DP = 48f
    const val INPUT_PAD_H_DP = 10f
    const val INPUT_PAD_V_DP = 4f
    const val REPLY_PAD_H_DP = 12f
    const val REPLY_PAD_V_DP = 10f
    const val CHIP_PAD_H_DP = 12f
    const val CHIP_PAD_V_DP = 6f
    const val STACK_GAP_DP = 6f
    const val CORNER_INPUT_DP = 22f
    const val CORNER_REPLY_DP = 20f
    const val CORNER_CHIP_DP = 14f
    const val TAIL_SHORT_DP = 8f
    const val TAIL_LONG_DP = 14f
    const val LINE_HEIGHT_REFERENCE = 1.30f

    fun tForScale(characterScale: Float): Float =
        ((characterScale.coerceIn(0.50f, 1.30f) - 0.50f) / 0.80f).coerceIn(0f, 1f)

    fun kForScale(characterScale: Float): Float =
        (characterScale.coerceIn(0.50f, 1.30f) / 0.85f).coerceIn(0.85f, 1.20f)

    fun bodyTextSp(characterScale: Float): Float =
        BODY_BASE_SP + BODY_SPAN_SP * tForScale(characterScale)

    fun chipTextSp(characterScale: Float): Float =
        CHIP_BASE_SP + CHIP_SPAN_SP * tForScale(characterScale)

    fun preferredWidthDp(characterScale: Float): Float =
        WIDTH_BASE_DP + WIDTH_SPAN_DP * tForScale(characterScale)

    fun inputPlans(request: SpeechBubbleScaleRequest): List<BubblePreviewLayoutPlan> =
        plansFor(request, SpeechBubbleMode.INPUT)

    fun replyPlans(request: SpeechBubbleScaleRequest): List<BubblePreviewLayoutPlan> =
        plansFor(request, SpeechBubbleMode.REPLY)

    fun plansFor(
        request: SpeechBubbleScaleRequest,
        mode: SpeechBubbleMode = request.mode,
    ): List<BubblePreviewLayoutPlan> {
        if (!request.characterScale.isFinite()) return emptyList()
        if (!request.density.isFinite() || request.density <= 0f) return emptyList()
        if (!request.scaledDensity.isFinite() || request.scaledDensity <= 0f) return emptyList()
        if (request.safeWidthPx <= 0 || request.safeHeightPx <= 0) return emptyList()
        val s = request.characterScale.coerceIn(0.50f, 1.30f)
        val density = request.density
        val scaledDensity = request.scaledDensity
        val k = kForScale(s)
        val bodySp = bodyTextSp(s)
        val chipSp = chipTextSp(s)
        val bodyTextPx = bodySp * scaledDensity
        val chipTextPx = chipSp * scaledDensity
        val bodyLine = max(
            request.realBodyLinePx?.takeIf { it.isFinite() && it > 0f } ?: 0f,
            bodySp * LINE_HEIGHT_REFERENCE * scaledDensity,
        )
        val chipLine = max(
            request.realChipLinePx?.takeIf { it.isFinite() && it > 0f } ?: 0f,
            chipSp * LINE_HEIGHT_REFERENCE * scaledDensity,
        )
        val padH = (if (mode == SpeechBubbleMode.INPUT) {
            INPUT_PAD_H_DP
        } else {
            REPLY_PAD_H_DP
        }) * k * density
        val padV = (if (mode == SpeechBubbleMode.INPUT) {
            INPUT_PAD_V_DP
        } else {
            REPLY_PAD_V_DP
        }) * k * density
        val chipPadH = CHIP_PAD_H_DP * k * density
        val chipPadV = CHIP_PAD_V_DP * k * density
        val gap = STACK_GAP_DP * k * density
        val actionRow = ceil(ACTION_TARGET_DP * density).toInt().coerceAtLeast(1)
        val tailShort = TAIL_SHORT_DP * k * density
        val tailLong = TAIL_LONG_DP * k * density
        val preferredWidth = preferredWidthDp(s) * density
        val widthCap = floor(
            min(
                INPUT_WIDTH_CAP_DP * density,
                request.safeWidthPx * WIDTH_SAFE_FRACTION,
            ).toDouble(),
        ).toInt().coerceAtLeast(1)
        val chipCap = floor(
            min(
                CHIP_WIDTH_CAP_DP * density,
                request.safeWidthPx * CHIP_WIDTH_SAFE_FRACTION,
            ).toDouble(),
        ).toInt().coerceAtLeast(1)
        val width = min(widthCap, ceil(preferredWidth.toDouble()).toInt()).coerceAtLeast(1)
        val contentWidth = width - 2f * padH - tailShort
        if (contentWidth < actionRow) return emptyList()
        if (mode == SpeechBubbleMode.INPUT &&
            contentWidth < actionRow + max(actionRow.toFloat(), 52f * density) + 32f * density
        ) {
            return emptyList()
        }

        val heightCap = floor(
            min(
                (if (request.landscape) LANDSCAPE_STACK_CAP_DP else PORTRAIT_STACK_CAP_DP) * density,
                request.safeHeightPx *
                    (if (request.landscape) LANDSCAPE_SAFE_FRACTION else PORTRAIT_SAFE_FRACTION),
            ).toDouble(),
        ).toInt().coerceAtLeast(1)

        val chipWidth = min(chipCap, width).coerceAtLeast(1)
        val chipHeight = ceil(
            max(actionRow.toFloat(), 2f * chipPadV + chipLine),
        ).toInt().coerceAtLeast(1)

        val result = ArrayList<BubblePreviewLayoutPlan>(3)
        if (mode == SpeechBubbleMode.INPUT) {
            val maxLines = if (request.landscape) {
                INPUT_MAX_LINES_LANDSCAPE
            } else {
                INPUT_MAX_LINES_PORTRAIT
            }
            val wanted = request.inputLines.coerceIn(1, maxLines)
            val lineCandidates = if (wanted <= 1) {
                listOf(1)
            } else {
                listOf(wanted, 1).distinct()
            }
            for (lines in lineCandidates) {
                val bodyHeight = ceil(
                    2f * padV + max(actionRow.toFloat(), lines * bodyLine),
                ).toInt().coerceAtLeast(1)
                val height = bodyHeight + ceil(tailShort.toDouble()).toInt()
                if (height <= heightCap) {
                    result.add(
                        buildPlan(
                            mode = mode,
                            width = width,
                            height = height,
                            bodyLines = lines,
                            bodyTextPx = bodyTextPx,
                            labelTextPx = chipTextPx,
                            padH = padH,
                            padV = padV,
                            gap = gap,
                            actionRow = actionRow,
                            tailShort = tailShort,
                            contentWidth = contentWidth,
                            corner = CORNER_INPUT_DP * k * density,
                            bodyLine = bodyLine,
                            chipWidth = width,
                            chipHeight = 0,
                            showReplyBody = true,
                            includeUserSummary = false,
                            characterScaleForPlan = s,
                        ),
                    )
                }
            }
            return result
        }

        val chipOnly = request.visibleState == BubbleVisibleState.CHIP_LINGER
        if (chipOnly) {
            if (chipHeight <= heightCap) {
                result.add(
                    buildPlan(
                        mode = mode,
                        width = min(width, chipWidth).coerceAtLeast(1),
                        height = chipHeight,
                        bodyLines = 0,
                        bodyTextPx = bodyTextPx,
                        labelTextPx = chipTextPx,
                        padH = padH,
                        padV = padV,
                        gap = gap,
                        actionRow = actionRow,
                        tailShort = 0f,
                        contentWidth = (min(width, chipWidth) - 2f * padH)
                            .coerceAtLeast(1f),
                        corner = CORNER_CHIP_DP * k * density,
                        bodyLine = bodyLine,
                        chipWidth = min(width, chipWidth).coerceAtLeast(1),
                        chipHeight = chipHeight,
                        showReplyBody = false,
                        includeUserSummary = true,
                        characterScaleForPlan = s,
                    ),
                )
            }
            return result
        }

        val maxLines = if (request.landscape) {
            REPLY_MAX_LINES_LANDSCAPE
        } else {
            REPLY_MAX_LINES_PORTRAIT
        }
        val actions = width >= actionRow * 3
        val canStop = request.visibleState == BubbleVisibleState.THINKING ||
            request.visibleState == BubbleVisibleState.TYPING
        val lineCandidates = buildList {
            add(maxLines)
            if (maxLines > 2) add(2)
            add(1)
        }.distinct()
        for (lines in lineCandidates) {
            val bodyHeight = ceil(2f * padV + lines * bodyLine).toInt().coerceAtLeast(1)
            val height = chipHeight + ceil(gap.toDouble()).toInt() +
                bodyHeight + ceil(tailShort.toDouble()).toInt()
            if (height <= heightCap) {
                result.add(
                    buildPlan(
                        mode = mode,
                        width = width,
                        height = height,
                        bodyLines = lines,
                        bodyTextPx = bodyTextPx,
                        labelTextPx = chipTextPx,
                        padH = padH,
                        padV = padV,
                        gap = gap,
                        actionRow = actionRow,
                        tailShort = tailShort,
                        contentWidth = contentWidth,
                        corner = CORNER_REPLY_DP * k * density,
                        bodyLine = bodyLine,
                        chipWidth = chipWidth,
                        chipHeight = chipHeight,
                        actions = if (actions) {
                            listOf(
                                if (canStop) BubblePreviewAction.STOP else BubblePreviewAction.RECORDS,
                                BubblePreviewAction.CLOSE,
                            )
                        } else {
                            emptyList()
                        },
                        showReplyBody = true,
                        includeUserSummary = true,
                        characterScaleForPlan = s,
                    ),
                )
            }
        }
        return result
    }

    private fun buildPlan(
        mode: SpeechBubbleMode,
        width: Int,
        height: Int,
        bodyLines: Int,
        bodyTextPx: Float,
        labelTextPx: Float,
        padH: Float,
        padV: Float,
        gap: Float,
        actionRow: Int,
        tailShort: Float,
        contentWidth: Float,
        corner: Float,
        bodyLine: Float,
        chipWidth: Int,
        chipHeight: Int,
        actions: List<BubblePreviewAction> = emptyList(),
        showReplyBody: Boolean,
        includeUserSummary: Boolean,
        characterScaleForPlan: Float = 0.85f,
    ): BubblePreviewLayoutPlan = BubblePreviewLayoutPlan(
        windowWidthPx = width,
        windowHeightPx = height,
        bodyLines = bodyLines,
        includeUserSummary = includeUserSummary,
        bodyTextPx = bodyTextPx,
        labelTextPx = labelTextPx,
        padHorizontalPx = padH,
        padVerticalPx = padV,
        zoneGapPx = gap,
        actionGapPx = 0f,
        actionRowPx = actionRow,
        cornerRadiusPx = corner,
        bodyLineHeightPx = bodyLine,
        nameLineHeightPx = 0f,
        userLineHeightPx = bodyLine,
        contentWidthPx = contentWidth,
        tailBudgetPx = tailShort,
        actionLabels = actions,
        speechMode = mode,
        showReplyBody = showReplyBody,
        showActions = actions.isNotEmpty(),
        chipWidthPx = chipWidth,
        chipHeightPx = chipHeight,
        speechScale = characterScaleForPlan,
    )
}
