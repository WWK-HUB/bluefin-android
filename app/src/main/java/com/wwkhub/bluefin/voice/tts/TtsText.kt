package com.wwkhub.bluefin.voice.tts

/**
 * Turns an assistant reply into the list of sentences the speaker reads.
 *
 * Two things matter here and both are pure string work so they can be covered
 * by JVM tests:
 *
 * - Markdown has to be stripped. ZipVoice reads every character it can tokenise,
 *   so `**加粗**` comes out as audible stars and `[文字](https://…)` reads the
 *   URL out loud.
 * - Sentences have to be short. Synthesis is slower than real time on a phone,
 *   and a 500-character paragraph would be read for minutes; the reply is capped
 *   so off-the-cuff replies stay listenable.
 */
internal object TtsText {
    /** Longest reply prefix that gets read aloud. */
    const val MAX_SPEAK_CHARS = 300

    /**
     * Shortest sentence worth a synthesis call. Below this the encoder output
     * can be fewer frames than the reference prompt and ZipVoice refuses to
     * synthesise (it returns empty audio rather than throwing).
     */
    const val MIN_SENTENCE_CHARS = 2

    private val sentenceEnds = charArrayOf('。', '！', '？', '；', '…', '!', '?', ';', '\n', '，', ',')
    private val hardEnds = charArrayOf('。', '！', '？', '；', '…', '!', '?', ';', '\n')

    /** Code blocks are dropped entirely: reading source code aloud is useless. */
    private val fencedCode = Regex("```[\\s\\S]*?(```|$)")
    private val inlineCode = Regex("`([^`]*)`")
    private val imageLink = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
    private val bareUrl = Regex("https?://\\S+")
    private val htmlTag = Regex("</?[A-Za-z][^>]*>")
    private val emphasis = Regex("[*_~#>|]+")
    private val bullets = Regex("(?m)^\\s*([-+*]|\\d+[.)])\\s+")

    fun stripMarkdown(text: String): String {
        var out = text
        out = fencedCode.replace(out, " ")
        out = imageLink.replace(out, "$1")
        out = link.replace(out, "$1")
        out = bareUrl.replace(out, " ")
        out = inlineCode.replace(out, "$1")
        out = htmlTag.replace(out, " ")
        out = bullets.replace(out, "")
        out = emphasis.replace(out, "")
        // Line breaks become spaces: a heading or list item read aloud should
        // not pause as if a sentence had ended.
        out = out.replace(Regex("\\s*\\r?\\n\\s*"), " ")
        return out.replace(Regex("[ \\t\\u00A0]+"), " ").trim()
    }

    /**
     * Splits [text] into sentences to synthesise, in order. Returns an empty list
     * when nothing is speakable (empty reply, only punctuation, only emoji).
     */
    fun sentences(text: String, limit: Int = MAX_SPEAK_CHARS): List<String> {
        val clean = stripMarkdown(text).take(limit)
        if (clean.isEmpty()) return emptyList()

        val out = ArrayList<String>()
        val buffer = StringBuilder()
        for (ch in clean) {
            if (ch == '\n') {
                flush(out, buffer)
                continue
            }
            buffer.append(ch)
            if (ch in hardEnds) flush(out, buffer)
        }
        flush(out, buffer)

        // A trailing fragment without terminal punctuation still gets read.
        return mergeShort(out)
    }

    private fun flush(out: MutableList<String>, buffer: StringBuilder) {
        val piece = buffer.toString().trim()
        buffer.setLength(0)
        if (piece.isNotEmpty()) out.add(piece)
    }

    /**
     * Merges very short bites into their neighbour: `嗯。` alone is exactly the
     * case ZipVoice synthesises to nothing, while `嗯。我在呢。` works.
     */
    private fun mergeShort(parts: List<String>): List<String> {
        if (parts.isEmpty()) return parts
        val merged = ArrayList<String>()
        var carry = ""
        for (part in parts) {
            val candidate = if (carry.isEmpty()) part else carry + part
            if (speakableLength(candidate) < MIN_SENTENCE_CHARS) {
                carry = candidate
                continue
            }
            merged.add(candidate)
            carry = ""
        }
        if (carry.isNotEmpty()) {
            if (merged.isEmpty()) {
                merged.add(carry)
            } else {
                merged[merged.lastIndex] = merged.last() + carry
            }
        }
        return merged.filter { speakableLength(it) > 0 }
    }

    /** Characters that survive tokenisation, used for the length checks. */
    fun speakableLength(text: String): Int =
        text.count { ch ->
            ch.isLetterOrDigit() ||
                ch.code in 0x3400..0x4DBF ||
                ch.code in 0x4E00..0x9FFF ||
                ch.code in 0xF900..0xFAFF
        }

    /** True when at least one sentence is worth speaking. */
    fun hasSpeakableText(text: String): Boolean =
        sentences(text).any { speakableLength(it) > 0 }

    /** Splits on commas too; used only when a single sentence is still too long. */
    fun splitOnCommas(text: String): List<String> =
        text.split(*sentenceEnds.map { it }.toCharArray())
            .map { it.trim() }
            .filter { speakableLength(it) > 0 }
}
