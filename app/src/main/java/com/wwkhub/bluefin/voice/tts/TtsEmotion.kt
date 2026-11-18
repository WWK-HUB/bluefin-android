package com.wwkhub.bluefin.voice.tts

/**
 * The eight character emotions. The cloud worker maps them to restrained
 * Fish Audio S2.1 [bracket] delivery cues server-side; the local ZipVoice
 * engine ignores emotion (it has no such control) without changing timbre.
 * Unknown values always degrade to neutral.
 */
internal enum class TtsEmotion(val wire: String) {
    NEUTRAL("neutral"),
    HAPPY("happy"),
    EXCITED("excited"),
    SHY("shy"),
    TSUNDERE("tsundere"),
    SAD("sad"),
    ANGRY("angry"),
    GENTLE("gentle");

    companion object {
        fun fromWire(raw: String?): TtsEmotion =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: NEUTRAL
    }
}