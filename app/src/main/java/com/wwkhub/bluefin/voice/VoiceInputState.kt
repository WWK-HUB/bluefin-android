package com.wwkhub.bluefin.voice

internal enum class VoicePhase {
    IDLE,
    RECORDING,
    RECOGNIZING,
}

/**
 * Pure phase/generation state for voice input rounds, in the spirit of
 * [com.wwkhub.bluefin.ScreenCaptureScope]: a generation is issued when
 * recording starts, and stale recorder/recognizer callbacks carrying an old
 * generation are ignored after cancel, hide, or a newer round.
 *
 * The host calls every method from the UI thread.
 */
internal class VoiceInputState {
    var phase: VoicePhase = VoicePhase.IDLE
        private set

    private var generation = 0L

    /** Starts a round only from IDLE; returns the generation to carry through callbacks. */
    fun beginRecording(): Long? {
        if (phase != VoicePhase.IDLE) return null
        phase = VoicePhase.RECORDING
        generation += 1
        return generation
    }

    /** Samples arrived for the current round; move to RECOGNIZING. */
    fun beginRecognizing(token: Long): Boolean {
        if (phase != VoicePhase.RECORDING || token != generation) return false
        phase = VoicePhase.RECOGNIZING
        return true
    }

    /** A round completed (text, empty result or error). Late duplicates are rejected. */
    fun finish(token: Long): Boolean {
        if (token != generation || phase == VoicePhase.IDLE) return false
        phase = VoicePhase.IDLE
        return true
    }

    /** Cancels any active round and invalidates its in-flight callbacks. */
    fun cancel(): Boolean {
        if (phase == VoicePhase.IDLE) return false
        phase = VoicePhase.IDLE
        generation += 1
        return true
    }

    fun isCurrent(token: Long): Boolean = token == generation && phase != VoicePhase.IDLE
}