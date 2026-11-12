package com.wwkhub.bluefin.bubblepreview

/**
 * Result of an optional platform measurement. A real `Exception` is a failed
 * optional attempt; a missing value is only ordinary unavailable geometry and
 * must never be counted as a failure.
 */
internal sealed class BubblePreviewMeasureResult<out T> {
    data class Value<T>(val value: T) : BubblePreviewMeasureResult<T>()
    data object Missing : BubblePreviewMeasureResult<Nothing>()
    data class Failed(val exceptionType: String) : BubblePreviewMeasureResult<Nothing>()
}

internal object BubblePreviewMeasure {
    fun <T> capture(read: () -> T?): BubblePreviewMeasureResult<T> = try {
        val value = read()
        if (value == null) {
            BubblePreviewMeasureResult.Missing
        } else {
            BubblePreviewMeasureResult.Value(value)
        }
    } catch (error: Exception) {
        BubblePreviewMeasureResult.Failed(error.javaClass.name)
    }
}

/**
 * One preference notification captured at the moment the callback fired.
 * The posted cleanup must use this captured value, never re-read the preference:
 * an OFF immediately followed by ON must still suppress the old round, while a
 * later ON notification never revives it.
 */
internal data class BubblePreviewPreferenceChange(
    val roundGeneration: Long,
    val enabledAtNotification: Boolean,
)

internal object BubblePreviewPreferencePolicy {
    fun shouldDismiss(
        change: BubblePreviewPreferenceChange,
        currentRoundGeneration: Long,
    ): Boolean = !change.enabledAtNotification &&
        change.roundGeneration == currentRoundGeneration
}

/**
 * Child-only animations are gated by both the platform animator enable flag and
 * the system animator duration scale. A disabled system must never load an
 * enter/crossfade/exit child animation.
 */
internal object BubblePreviewAnimationPolicy {
    fun enabled(animatorsEnabled: Boolean, durationScale: Float): Boolean =
        animatorsEnabled && durationScale.isFinite() && durationScale > 0f
}

/**
 * A view's local visible rectangle is root/window-relative, not necessarily
 * screen-relative. Convert explicitly through the root's on-screen origin:
 * `windowOrigin = locationOnScreen - locationInWindow`.
 */
internal object BubblePreviewScreenCoordinates {
    fun visibleBounds(
        localVisible: BubblePreviewRect,
        locationOnScreen: BubblePreviewPoint,
        locationInWindow: BubblePreviewPoint,
    ): BubblePreviewRect? {
        if (!localVisible.isValid()) return null
        if (!locationOnScreen.isValid() || !locationInWindow.isValid()) return null
        val originX = locationOnScreen.x - locationInWindow.x
        val originY = locationOnScreen.y - locationInWindow.y
        if (!originX.isFinite() || !originY.isFinite()) return null
        val left = localVisible.left.toDouble() + originX
        val top = localVisible.top.toDouble() + originY
        val right = localVisible.right.toDouble() + originX
        val bottom = localVisible.bottom.toDouble() + originY
        if (left < Int.MIN_VALUE || top < Int.MIN_VALUE) return null
        if (right > Int.MAX_VALUE || bottom > Int.MAX_VALUE) return null
        return BubblePreviewRect(
            left.toInt(),
            top.toInt(),
            right.toInt(),
            bottom.toInt(),
        ).takeIf { it.isValid() }
    }

    fun compactBounds(
        locationOnScreen: BubblePreviewPoint,
        widthPx: Int,
        heightPx: Int,
    ): BubblePreviewRect? {
        if (!locationOnScreen.isValid() || widthPx <= 0 || heightPx <= 0) return null
        val left = locationOnScreen.x.toDouble()
        val top = locationOnScreen.y.toDouble()
        val right = left + widthPx
        val bottom = top + heightPx
        if (left < Int.MIN_VALUE || top < Int.MIN_VALUE) return null
        if (right > Int.MAX_VALUE || bottom > Int.MAX_VALUE) return null
        return BubblePreviewRect(
            left.toInt(),
            top.toInt(),
            right.toInt(),
            bottom.toInt(),
        ).takeIf { it.isValid() }
    }
}
