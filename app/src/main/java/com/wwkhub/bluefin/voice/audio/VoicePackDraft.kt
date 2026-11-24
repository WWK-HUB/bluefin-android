package com.wwkhub.bluefin.voice.audio

import com.wwkhub.bluefin.PackPaths
import com.wwkhub.bluefin.VoicePackFormat

/**
 * Builds the `voice.json` body for a pack the user creates in the app.
 *
 * The manifest is written by hand because [com.wwkhub.bluefin.MiniJson] only
 * parses; [jsonEscape] therefore carries the whole escaping burden and is the
 * one thing here that must never be skipped — a name with a quote would
 * otherwise produce a manifest that fails its own import validation.
 *
 * Ids and names are pure functions so the collision and de-duplication rules
 * run as JVM tests instead of needing a device.
 */
internal object VoicePackDraft {

    /** Same shape [PackPaths.validId] enforces. */
    private const val MAX_ID_CHARS = 32

    /**
     * `v` + base36 millis + 3 base36 salt characters: always lowercase,
     * always starts with a letter, always short enough, and never collides
     * with a built-in or reserved id because none of those start with `v`.
     */
    fun newId(nowMs: Long, salt: Int): String {
        val time = nowMs.coerceAtLeast(0L).toString(36)
        val spread = (salt and 0x7FFFFFFF) % SALT_SPACE
        val suffix = spread.toString(36).padStart(3, '0')
        return ("v$time-$suffix").take(MAX_ID_CHARS)
    }

    /**
     * Appends the first free `（n）` when [desired] is already taken. The
     * suffix is full-width to match how the rest of the UI writes parentheses,
     * and the result honours [VoicePackFormat] name limits.
     */
    fun uniqueName(desired: String, existing: List<String>): String {
        val base = desired.trim().ifEmpty { DEFAULT_NAME }.take(MAX_NAME_CHARS)
        val taken = existing.mapTo(HashSet()) { it.trim() }
        if (base !in taken) return base
        var index = 2
        while (index < 1000) {
            val suffix = "（$index）"
            val room = MAX_NAME_CHARS - suffix.length
            val candidate = base.take(room.coerceAtLeast(1)).trimEnd() + suffix
            if (candidate !in taken) return candidate
            index++
        }
        return base
    }

    /** Serialises a manifest that [VoicePackFormat.parse] accepts as-is. */
    fun manifest(
        id: String,
        name: String,
        about: String,
        referenceText: String,
        sampleRate: Int,
    ): String = buildString {
        append("{\n")
        append("  \"format\": ").append(VoicePackFormat.FORMAT_VERSION).append(",\n")
        append("  \"id\": \"").append(jsonEscape(id)).append("\",\n")
        append("  \"name\": \"").append(jsonEscape(name)).append("\",\n")
        append("  \"kind\": \"").append(VoicePackFormat.KIND_FISH).append("\",\n")
        append("  \"reference\": \"").append(VoicePackFormat.REFERENCE_FILE).append("\",\n")
        append("  \"referenceText\": \"").append(jsonEscape(referenceText)).append("\",\n")
        append("  \"sampleRate\": ").append(sampleRate).append(",\n")
        append("  \"speed\": 1.0,\n")
        append("  \"numSteps\": 4,\n")
        append("  \"about\": \"").append(jsonEscape(about)).append("\"\n")
        append("}\n")
    }

    /**
     * Escapes the characters `MiniJson` treats as structural. Control codes go
     * out as `\u00XX` because the parser rejects a raw one, and it understands
     * exactly those six named escapes.
     */
    fun jsonEscape(raw: String): String {
        val out = StringBuilder(raw.length + 8)
        for (char in raw) {
            when (char) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (char.code < 0x20) {
                    out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
        return out.toString()
    }

    /** Mirrors the parser's limit so the UI and the writer cannot disagree. */
    const val MAX_NAME_CHARS = 32
    const val MAX_ABOUT_CHARS = 120
    const val MAX_TEXT_CHARS = VoicePackFormat.MAX_TEXT_CHARS

    const val DEFAULT_NAME = "我的音色"

    private const val SALT_SPACE = 36 * 36 * 36
}
