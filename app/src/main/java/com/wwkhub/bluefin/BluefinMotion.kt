package com.wwkhub.bluefin

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

internal object BluefinMotion {
    private val settle = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    fun enter(view: View, x: Float = 0f, y: Float = 0f, scale: Float = 1f) {
        view.animate().cancel()
        val animate = ValueAnimator.areAnimatorsEnabled()
        view.translationX = if (animate) x else 0f
        view.translationY = if (animate) y else 0f
        view.scaleX = if (animate) scale else 1f
        view.scaleY = if (animate) scale else 1f
        view.alpha = if (animate) 0.45f else 1f
        if (animate) {
            view.animate()
                .translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(settle).setDuration(220L).start()
        }
    }

    fun orbTouch(view: View, event: MotionEvent) {
        val pressed = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
            else -> return
        }
        view.isPressed = pressed
        view.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        val scale = if (pressed) 0.91f else 1f
        view.animate().scaleX(scale).scaleY(scale).setInterpolator(settle)
            .setDuration(if (pressed) 100L else 190L).start()
    }
}
