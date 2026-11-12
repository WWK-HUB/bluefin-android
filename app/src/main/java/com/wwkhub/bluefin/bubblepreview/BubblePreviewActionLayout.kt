package com.wwkhub.bluefin.bubblepreview

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure Paint-measurement fit for the fixed action row.
 *
 * Every action keeps at least a 44 dp touch target. Text is never truncated or
 * shrunk: if a state's required labels (including 查看全部 for a long overflow)
 * cannot share the measured content width, the whole preview falls back to the
 * record panel instead of silently hiding the required label.
 */
internal object BubblePreviewActionLayout {
    const val ACTION_TARGET_DP: Float = 44f
    const val HORIZONTAL_PADDING_DP: Float = 8f
    const val ACTION_GAP_DP: Float = 2f
    const val ICON_DP: Float = 16f
    const val ICON_GAP_DP: Float = 4f

    fun iconWidthPx(action: BubblePreviewAction, density: Float): Float = when (action) {
        BubblePreviewAction.STOP,
        BubblePreviewAction.RECORDS,
        BubblePreviewAction.CLOSE,
        -> (ICON_DP + ICON_GAP_DP) * density.coerceAtLeast(0.5f)

        BubblePreviewAction.VIEW_ALL -> 0f
    }

    fun touchWidthPx(
        textWidthPx: Float,
        density: Float,
        minimumTouchPx: Int,
        iconWidthPx: Float = 0f,
    ): Int {
        if (!textWidthPx.isFinite() || textWidthPx <= 0f) return minimumTouchPx
        val padding = 2f * HORIZONTAL_PADDING_DP * density.coerceAtLeast(0.5f)
        val icon = iconWidthPx.takeIf { it.isFinite() && it > 0f } ?: 0f
        return max(minimumTouchPx, (textWidthPx + padding + icon).roundToInt())
    }

    /**
     * Returns the actions to draw at the given content width, or null when even
     * the minimal set does not fit. [measured] is a text-width lookup keyed by
     * action, produced by the real Paint.
     */
    fun choose(
        state: BubbleVisibleState,
        contentWidthPx: Float,
        density: Float,
        minimumTouchPx: Int,
        measured: Map<BubblePreviewAction, Float>,
    ): List<BubblePreviewAction>? {
        if (!contentWidthPx.isFinite() || contentWidthPx <= 0f) return null
        if (!density.isFinite() || density <= 0f) return null
        if (minimumTouchPx <= 0) return null
        val gap = ACTION_GAP_DP * density
        fun widths(actions: List<BubblePreviewAction>): List<Int> = actions.map { action ->
            touchWidthPx(
                measured[action] ?: 0f,
                density,
                minimumTouchPx,
                iconWidthPx(action, density),
            )
        }
        fun fits(actions: List<BubblePreviewAction>): Boolean {
            val total = widths(actions).sum().toFloat() + gap * (actions.size - 1)
            return total <= contentWidthPx
        }
        val primary = when (state) {
            BubbleVisibleState.THINKING,
            BubbleVisibleState.TYPING,
            -> listOf(
                BubblePreviewAction.STOP,
                BubblePreviewAction.RECORDS,
                BubblePreviewAction.CLOSE,
            )
            BubbleVisibleState.HOLD -> listOf(
                BubblePreviewAction.VIEW_ALL,
                BubblePreviewAction.RECORDS,
                BubblePreviewAction.CLOSE,
            )
            BubbleVisibleState.ERROR -> listOf(
                BubblePreviewAction.RECORDS,
                BubblePreviewAction.CLOSE,
            )
            BubbleVisibleState.COLLAPSED -> listOf(BubblePreviewAction.CLOSE)
            BubbleVisibleState.HIDDEN -> return null
        }
        if (fits(primary)) return primary
        return null
    }
}
