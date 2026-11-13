package com.wwkhub.bluefin

import android.os.Handler
import android.os.Looper

/**
 * Process-local foreground signal for optional overlay surfaces.
 *
 * It is intentionally not persisted and does not construct any overlay object.
 * The bubble controller subscribes only after an explicit chat action created
 * it, so opening the app or toggling settings never loads the optional module.
 */
internal object OverlayAppVisibility {
    fun interface Listener {
        fun onAppForegroundChanged(foreground: Boolean)
    }

    @Volatile
    var foreground: Boolean = false
        private set
    var chatVisible: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) {
            listeners.toList().forEach { listener ->
                handler.post { listener.onAppForegroundChanged(true) }
            }
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    private val chatVisibilityListeners = LinkedHashSet<Listener>()

    fun setChatVisible(value: Boolean) {
        if (chatVisible == value) return
        chatVisible = value
        chatVisibilityListeners.toList().forEach { it.onAppForegroundChanged(value) }
    }

    fun addChatVisibilityListener(listener: Listener) {
        chatVisibilityListeners.add(listener)
    }

    fun removeChatVisibilityListener(listener: Listener) {
        chatVisibilityListeners.remove(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
}
