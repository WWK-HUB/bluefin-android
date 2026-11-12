package com.wwkhub.bluefin.bubblepreview.visual

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Immutable ARGB color for the read-only preview.
 *
 * Everything here is pure JVM arithmetic. The host parses and caches pack
 * strings, passes them in, and applies the resulting ARGB values to drawables;
 * this file never reads a package, a resource, the filesystem or the network.
 *
 * Pack strings use Android's hex order:
 * `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB`. Parsing is strict: the leading `#`
 * is mandatory, lowercase/uppercase hex digits are accepted, whitespace,
 * `0x`, CSS-style `#RRGGBBAA`, Unicode digits and other lengths are rejected.
 */
data class BubblePreviewColor(val argb: Int) {
    val alpha: Int get() = (argb ushr 24) and 0xFF

    val red: Int get() = (argb ushr 16) and 0xFF

    val green: Int get() = (argb ushr 8) and 0xFF

    val blue: Int get() = argb and 0xFF

    val alphaFraction: Double get() = alpha / 255.0

    val isOpaque: Boolean get() = alpha == 0xFF

    /**
     * Straight-alpha source-over compositing. The preview uses an opaque
     * default fill as the bottom layer, so the result is always opaque there.
     */
    fun compositeOver(background: BubblePreviewColor): BubblePreviewColor {
        val sourceAlpha = alphaFraction
        val backgroundAlpha = background.alphaFraction
        val outAlpha = sourceAlpha + backgroundAlpha * (1.0 - sourceAlpha)
        if (outAlpha <= 0.0) return BubblePreviewColor(0)

        fun channel(source: Int, destination: Int): Int {
            val value =
                (source * sourceAlpha + destination * backgroundAlpha * (1.0 - sourceAlpha)) / outAlpha
            return value.roundToInt().coerceIn(0, 255)
        }

        val outAlphaByte = (outAlpha * 255.0).roundToInt().coerceIn(0, 255)
        return BubblePreviewColor(
            (outAlphaByte shl 24) or
                (channel(red, background.red) shl 16) or
                (channel(green, background.green) shl 8) or
                channel(blue, background.blue),
        )
    }

    /** WCAG 2.x relative luminance of the RGB channels; alpha is not applied. */
    fun relativeLuminance(): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) {
                value / 12.92
            } else {
                ((value + 0.055) / 1.055).pow(2.4)
            }
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    /**
     * WCAG 2.x contrast ratio. Callers must composite translucent colors over
     * their final background first; the palette resolution below does that.
     */
    fun contrastRatio(other: BubblePreviewColor): Double {
        val first = relativeLuminance()
        val second = other.relativeLuminance()
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    /** `#RRGGBB` when opaque, `#AARRGGBB` otherwise. */
    fun toHexString(): String {
        val builder = StringBuilder(9)
        builder.append('#')
        if (!isOpaque) appendByte(builder, alpha)
        appendByte(builder, red)
        appendByte(builder, green)
        appendByte(builder, blue)
        return builder.toString()
    }

    companion object {
        /**
         * Strict parse of an optional cached pack string. Returns null for
         * missing, blank, malformed or non-Android-order input. It does not
         * validate contrast or transparency; [BubblePreviewPalette] does that.
         */
        fun parse(hex: String?): BubblePreviewColor? {
            if (hex == null || hex.isEmpty() || hex[0] != '#') return null
            val digits = hex.substring(1)
            if (digits.length !in VALID_LENGTHS) return null
            if (digits.any { !isHexDigit(it) }) return null
            return when (digits.length) {
                3 -> opaque(
                    expand(digits[0]),
                    expand(digits[1]),
                    expand(digits[2]),
                )

                4 -> argb(
                    expand(digits[0]),
                    expand(digits[1]),
                    expand(digits[2]),
                    expand(digits[3]),
                )

                6 -> opaque(
                    byteAt(digits, 0),
                    byteAt(digits, 2),
                    byteAt(digits, 4),
                )

                8 -> argb(
                    byteAt(digits, 0),
                    byteAt(digits, 2),
                    byteAt(digits, 4),
                    byteAt(digits, 6),
                )

                else -> null
            }
        }

        fun of(alpha: Int, red: Int, green: Int, blue: Int): BubblePreviewColor {
            require(alpha in 0..255 && red in 0..255 && green in 0..255 && blue in 0..255)
            return BubblePreviewColor(
                (alpha shl 24) or (red shl 16) or (green shl 8) or blue,
            )
        }

        private val VALID_LENGTHS = setOf(3, 4, 6, 8)

        private fun isHexDigit(value: Char): Boolean =
            value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

        private fun hexValue(value: Char): Int = when (value) {
            in '0'..'9' -> value - '0'
            in 'a'..'f' -> value - 'a' + 10
            else -> value - 'A' + 10
        }

        private fun expand(value: Char): Int = hexValue(value) * 17

        private fun byteAt(digits: String, index: Int): Int =
            (hexValue(digits[index]) shl 4) or hexValue(digits[index + 1])

        private fun opaque(red: Int, green: Int, blue: Int): BubblePreviewColor =
            BubblePreviewColor((0xFF shl 24) or (red shl 16) or (green shl 8) or blue)

        private fun argb(alpha: Int, red: Int, green: Int, blue: Int): BubblePreviewColor =
            BubblePreviewColor((alpha shl 24) or (red shl 16) or (green shl 8) or blue)
    }
}

private fun appendByte(builder: StringBuilder, value: Int) {
    val clamped = value.coerceIn(0, 255)
    builder.append(HEX_DIGITS[clamped ushr 4])
    builder.append(HEX_DIGITS[clamped and 0x0F])
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/** User or character zone colors. */
data class BubblePreviewZonePalette(
    val fill: BubblePreviewColor,
    val text: BubblePreviewColor,
    val line: BubblePreviewColor,
) {
    val isOpaque: Boolean get() = fill.isOpaque && text.isOpaque && line.isOpaque

    val textContrastRatio: Double get() = text.contrastRatio(fill)

    val lineContrastRatio: Double get() = line.contrastRatio(fill)
}

enum class BubblePreviewPaletteSource {
    DEFAULT,
    PACK,
}

/**
 * Why the whole character palette fell back to the current mode's default.
 * `NONE` means the cached pack palette was accepted.
 */
enum class BubblePreviewPaletteFallbackReason {
    NONE,
    PACK_ABSENT,
    INCOMPLETE,
    UNPARSEABLE,
    TOO_TRANSPARENT,
    TEXT_CONTRAST,
    LINE_CONTRAST,
}

/**
 * Optional cached character-pack palette input. Strings are the values already
 * parsed by the pack layer (`bubble.light/dark.fill/text/line`) or the same
 * values cached from disk. No read happens here.
 */
data class BubblePreviewPackPaletteInput(
    val fillHex: String?,
    val textHex: String?,
    val lineHex: String?,
)

/**
 * Resolved visual palette. Defaults are the exact spec tokens and are always
 * opaque. A pack can override only the character zone. Any incomplete,
 * unparseable, too-transparent or low-contrast pack input rejects the entire
 * character pack palette; the user zone always keeps its default tokens.
 */
data class BubblePreviewPalette(
    val night: Boolean,
    val user: BubblePreviewZonePalette,
    val character: BubblePreviewZonePalette,
    val characterSource: BubblePreviewPaletteSource,
    val characterFallbackReason: BubblePreviewPaletteFallbackReason,
) {
    val usesPackCharacterColors: Boolean
        get() = characterSource == BubblePreviewPaletteSource.PACK

    val allZonesOpaque: Boolean get() = user.isOpaque && character.isOpaque

    companion object {
        const val MIN_TEXT_CONTRAST = 4.5
        const val MIN_LINE_CONTRAST = 3.0

        /**
         * A cached pack color below half alpha is treated as an unusable tint,
         * even though compositing would make the displayed fill opaque.
         */
        const val MIN_PACK_ALPHA = 0.5

        fun resolve(night: Boolean): BubblePreviewPalette = resolve(night, null)

        fun resolveHex(
            night: Boolean,
            fillHex: String?,
            textHex: String?,
            lineHex: String?,
        ): BubblePreviewPalette = resolve(
            night = night,
            input = BubblePreviewPackPaletteInput(fillHex, textHex, lineHex),
        )

        /**
         * Resolve the day/night defaults plus an optional cached pack palette.
         *
         * Translucent pack fill is composited over the current mode's default
         * character fill first. Text and line are then composited over that
         * effective fill. Only after compositing are the WCAG ratios checked.
         */
        fun resolve(
            night: Boolean,
            input: BubblePreviewPackPaletteInput?,
        ): BubblePreviewPalette {
            val user = defaultUser(night)
            val defaultCharacter = defaultCharacter(night)
            if (input == null) {
                return fallback(night, user, defaultCharacter, BubblePreviewPaletteFallbackReason.PACK_ABSENT)
            }

            val fillHex = input.fillHex?.takeUnless { it.isBlank() }
            val textHex = input.textHex?.takeUnless { it.isBlank() }
            val lineHex = input.lineHex?.takeUnless { it.isBlank() }
            if (fillHex == null && textHex == null && lineHex == null) {
                return fallback(night, user, defaultCharacter, BubblePreviewPaletteFallbackReason.PACK_ABSENT)
            }
            if (fillHex == null || textHex == null || lineHex == null) {
                return fallback(night, user, defaultCharacter, BubblePreviewPaletteFallbackReason.INCOMPLETE)
            }

            val parsedFill = BubblePreviewColor.parse(fillHex)
            val parsedText = BubblePreviewColor.parse(textHex)
            val parsedLine = BubblePreviewColor.parse(lineHex)
            if (parsedFill == null || parsedText == null || parsedLine == null) {
                return fallback(night, user, defaultCharacter, BubblePreviewPaletteFallbackReason.UNPARSEABLE)
            }

            if (parsedFill.alphaFraction < MIN_PACK_ALPHA ||
                parsedText.alphaFraction < MIN_PACK_ALPHA ||
                parsedLine.alphaFraction < MIN_PACK_ALPHA
            ) {
                return fallback(night, user, defaultCharacter, BubblePreviewPaletteFallbackReason.TOO_TRANSPARENT)
            }

            val fill = parsedFill.compositeOver(defaultCharacter.fill)
            val text = parsedText.compositeOver(fill)
            val line = parsedLine.compositeOver(fill)
            val character = BubblePreviewZonePalette(fill = fill, text = text, line = line)

            if (character.textContrastRatio < MIN_TEXT_CONTRAST) {
                return fallback(night, user, defaultCharacter, BubblePreviewPaletteFallbackReason.TEXT_CONTRAST)
            }
            if (character.lineContrastRatio < MIN_LINE_CONTRAST) {
                return fallback(night, user, defaultCharacter, BubblePreviewPaletteFallbackReason.LINE_CONTRAST)
            }

            return BubblePreviewPalette(
                night = night,
                user = user,
                character = character,
                characterSource = BubblePreviewPaletteSource.PACK,
                characterFallbackReason = BubblePreviewPaletteFallbackReason.NONE,
            )
        }

        fun defaultUser(night: Boolean): BubblePreviewZonePalette = if (night) {
            zone("#3D3020", "#FFE5BC", "#D8AD6B")
        } else {
            zone("#FFF1D6", "#4B3217", "#AD6A1D")
        }

        fun defaultCharacter(night: Boolean): BubblePreviewZonePalette = if (night) {
            zone("#183447", "#E2F3FF", "#78B4D4")
        } else {
            zone("#E8F4FF", "#173B54", "#3A7799")
        }

        private fun zone(fill: String, text: String, line: String): BubblePreviewZonePalette =
            BubblePreviewZonePalette(
                fill = requireNotNull(BubblePreviewColor.parse(fill)),
                text = requireNotNull(BubblePreviewColor.parse(text)),
                line = requireNotNull(BubblePreviewColor.parse(line)),
            )

        private fun fallback(
            night: Boolean,
            user: BubblePreviewZonePalette,
            character: BubblePreviewZonePalette,
            reason: BubblePreviewPaletteFallbackReason,
        ): BubblePreviewPalette = BubblePreviewPalette(
            night = night,
            user = user,
            character = character,
            characterSource = BubblePreviewPaletteSource.DEFAULT,
            characterFallbackReason = reason,
        )
    }
}
