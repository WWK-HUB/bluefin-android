package com.wwkhub.bluefin

/** A permit belongs to one projection and one uninterrupted visible period. */
internal class ScreenCaptureScope {
    private var active = true
    private var visible = true
    private var generation = 0L

    @Synchronized
    fun permit(): Long? = generation.takeIf { active && visible }

    @Synchronized
    fun allows(permit: Long): Boolean = active && visible && permit == generation

    @Synchronized
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        generation++
    }

    @Synchronized
    fun stop() {
        active = false
    }
}

internal data class CapturedScreen(val jpeg: ByteArray, val isCurrent: () -> Boolean)
