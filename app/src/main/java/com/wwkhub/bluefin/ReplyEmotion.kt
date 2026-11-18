package com.wwkhub.bluefin

import com.wwkhub.bluefin.voice.tts.TtsEmotion

/**
 * Streaming-safe parser for the optional leading emotion marker
 * `[[emotion:happy]]` the model may emit when speech is enabled.
 *
 * Contract:
 *  - the marker is hidden from the transcript, the UI and the synthesizer;
 *  - while a partial marker is still forming at the very start of a reply,
 *    display is held (a few characters at most) so no bracket ever flashes;
 *  - anything that cannot become a marker is released as ordinary text;
 *  - an unknown emotion name resolves to neutral, never to a crash.
 */
internal class ReplyEmotionFeed {
    private val pending = StringBuilder()
    private var resolved = false
    private var trimNextLeading = false

    var emotion: TtsEmotion = TtsEmotion.NEUTRAL
        private set

    /** Feeds one streaming delta; returns the text that is safe to display now. */
    fun feed(delta: String): String {
        if (resolved) {
            if (trimNextLeading) {
                trimNextLeading = false
                return delta.trimStart()
            }
            return delta
        }
        pending.append(delta)
        val text = pending.toString()
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith("[")) return release()
        val match = MARKER.find(trimmed)
        if (match != null && match.range.first == 0) {
            emotion = TtsEmotion.fromWire(match.groupValues[1])
            resolved = true
            val rest = trimmed.substring(match.value.length)
            pending.setLength(0)
            // The separator space/newline may arrive in the next delta.
            trimNextLeading = rest.isEmpty()
            return rest.trimStart()
        }
        if (trimmed.length <= MAX_MARKER_CHARS && couldBeMarkerPrefix(trimmed)) return ""
        return release()
    }

    /** End of reply: whatever is still held is ordinary text. */
    fun flush(): String {
        if (resolved) return ""
        return release()
    }

    private fun release(): String {
        resolved = true
        emotion = TtsEmotion.NEUTRAL
        val out = pending.toString()
        pending.setLength(0)
        return out
    }

    companion object {
        private const val MAX_MARKER_CHARS = 48
        private const val SKELETON = "[[emotion:"
        private val MARKER = Regex("^\\[\\[emotion:([a-z]{1,20})\\]\\]")
        private val PARTIAL_TAIL = Regex("[a-z]{0,20}\\]{0,2}")

        /** True while [s] could still grow into a complete leading marker. */
        fun couldBeMarkerPrefix(s: String): Boolean {
            if (s.length <= SKELETON.length) return SKELETON.startsWith(s)
            if (!s.startsWith(SKELETON)) return false
            return PARTIAL_TAIL.matches(s.substring(SKELETON.length))
        }
    }
}