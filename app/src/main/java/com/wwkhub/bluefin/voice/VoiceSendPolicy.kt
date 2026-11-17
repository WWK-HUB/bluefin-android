package com.wwkhub.bluefin.voice

internal enum class VoiceSendDecision {
    SEND,
    LOOK_COMMAND,
    EMPTY,
    BUSY,
    NOT_CONFIGURED,
}

/**
 * Pure routing for a recognized utterance. Mirrors the typed-text send path:
 * the look-command intercept runs first (as in ChatScreen.sendMessage and
 * OverlayWindow.sendFromOverlay), then the same gates ChatSession.send applies.
 */
internal object VoiceSendPolicy {
    fun decide(
        text: String,
        configured: Boolean,
        busy: Boolean,
        isLookCommand: (String) -> Boolean,
    ): VoiceSendDecision {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return VoiceSendDecision.EMPTY
        if (isLookCommand(trimmed)) return VoiceSendDecision.LOOK_COMMAND
        if (!configured) return VoiceSendDecision.NOT_CONFIGURED
        if (busy) return VoiceSendDecision.BUSY
        return VoiceSendDecision.SEND
    }
}