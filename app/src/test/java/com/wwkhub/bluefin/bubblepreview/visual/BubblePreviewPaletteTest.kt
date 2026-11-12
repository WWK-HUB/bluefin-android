package com.wwkhub.bluefin.bubblepreview.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePreviewPaletteTest {
    private val validFill = "#FFFFFF"
    private val validText = "#000000"
    private val validLine = "#666666"

    @Test
    fun dayDefaultsMatchSpecExactly() {
        val palette = BubblePreviewPalette.resolve(night = false)

        assertEquals(0xFFFFF1D6.toInt(), palette.user.fill.argb)
        assertEquals(0xFF4B3217.toInt(), palette.user.text.argb)
        assertEquals(0xFFAD6A1D.toInt(), palette.user.line.argb)
        assertEquals(0xFFE8F4FF.toInt(), palette.character.fill.argb)
        assertEquals(0xFF173B54.toInt(), palette.character.text.argb)
        assertEquals(0xFF3A7799.toInt(), palette.character.line.argb)
    }

    @Test
    fun nightDefaultsMatchSpecExactly() {
        val palette = BubblePreviewPalette.resolve(night = true)

        assertEquals(0xFF3D3020.toInt(), palette.user.fill.argb)
        assertEquals(0xFFFFE5BC.toInt(), palette.user.text.argb)
        assertEquals(0xFFD8AD6B.toInt(), palette.user.line.argb)
        assertEquals(0xFF183447.toInt(), palette.character.fill.argb)
        assertEquals(0xFFE2F3FF.toInt(), palette.character.text.argb)
        assertEquals(0xFF78B4D4.toInt(), palette.character.line.argb)
    }

    @Test
    fun defaultsAreOpaqueInBothModes() {
        assertTrue(BubblePreviewPalette.resolve(night = false).allZonesOpaque)
        assertTrue(BubblePreviewPalette.resolve(night = true).allZonesOpaque)
        assertTrue(BubblePreviewPalette.defaultUser(false).isOpaque)
        assertTrue(BubblePreviewPalette.defaultUser(true).isOpaque)
        assertTrue(BubblePreviewPalette.defaultCharacter(false).isOpaque)
        assertTrue(BubblePreviewPalette.defaultCharacter(true).isOpaque)
    }

    @Test
    fun defaultContrastMeetsWcagInDayMode() {
        val palette = BubblePreviewPalette.resolve(night = false)

        assertTrue(palette.user.textContrastRatio >= BubblePreviewPalette.MIN_TEXT_CONTRAST)
        assertTrue(palette.user.lineContrastRatio >= BubblePreviewPalette.MIN_LINE_CONTRAST)
        assertTrue(palette.character.textContrastRatio >= BubblePreviewPalette.MIN_TEXT_CONTRAST)
        assertTrue(palette.character.lineContrastRatio >= BubblePreviewPalette.MIN_LINE_CONTRAST)
    }

    @Test
    fun defaultContrastMeetsWcagInNightMode() {
        val palette = BubblePreviewPalette.resolve(night = true)

        assertTrue(palette.user.textContrastRatio >= BubblePreviewPalette.MIN_TEXT_CONTRAST)
        assertTrue(palette.user.lineContrastRatio >= BubblePreviewPalette.MIN_LINE_CONTRAST)
        assertTrue(palette.character.textContrastRatio >= BubblePreviewPalette.MIN_TEXT_CONTRAST)
        assertTrue(palette.character.lineContrastRatio >= BubblePreviewPalette.MIN_LINE_CONTRAST)
    }

    @Test
    fun noPackInputKeepsDefaultsAndReportsAbsent() {
        val palette = BubblePreviewPalette.resolveHex(false, null, null, null)

        assertEquals(BubblePreviewPaletteSource.DEFAULT, palette.characterSource)
        assertEquals(BubblePreviewPaletteFallbackReason.PACK_ABSENT, palette.characterFallbackReason)
        assertEquals(BubblePreviewPalette.defaultCharacter(false), palette.character)
        assertFalse(palette.usesPackCharacterColors)
    }

    @Test
    fun allBlankPackInputIsTreatedAsAbsent() {
        val palette = BubblePreviewPalette.resolveHex(false, " ", "\t", "")

        assertEquals(BubblePreviewPaletteFallbackReason.PACK_ABSENT, palette.characterFallbackReason)
        assertEquals(BubblePreviewPalette.defaultCharacter(false), palette.character)
    }

    @Test
    fun validOpaquePackReplacesOnlyCharacterZone() {
        val palette = BubblePreviewPalette.resolveHex(false, validFill, validText, validLine)

        assertEquals(BubblePreviewPaletteSource.PACK, palette.characterSource)
        assertEquals(BubblePreviewPaletteFallbackReason.NONE, palette.characterFallbackReason)
        assertTrue(palette.usesPackCharacterColors)
        assertEquals(BubblePreviewColor.parse(validFill), palette.character.fill)
        assertEquals(BubblePreviewColor.parse(validText), palette.character.text)
        assertEquals(BubblePreviewColor.parse(validLine), palette.character.line)
        assertEquals(BubblePreviewPalette.defaultUser(false), palette.user)
    }

    @Test
    fun validPackIsAcceptedInNightModeAndUserStaysDefault() {
        val palette = BubblePreviewPalette.resolveHex(true, validFill, validText, validLine)

        assertEquals(BubblePreviewPaletteSource.PACK, palette.characterSource)
        assertEquals(BubblePreviewPalette.defaultUser(true), palette.user)
    }

    @Test
    fun missingFillFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, null, validText, validLine)

        assertFallback(palette, BubblePreviewPaletteFallbackReason.INCOMPLETE)
    }

    @Test
    fun missingTextFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, validFill, null, validLine)

        assertFallback(palette, BubblePreviewPaletteFallbackReason.INCOMPLETE)
    }

    @Test
    fun missingLineFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, validFill, validText, null)

        assertFallback(palette, BubblePreviewPaletteFallbackReason.INCOMPLETE)
    }

    @Test
    fun blankFieldCountsAsIncompleteWhenOthersArePresent() {
        val palette = BubblePreviewPalette.resolveHex(false, validFill, "   ", validLine)

        assertFallback(palette, BubblePreviewPaletteFallbackReason.INCOMPLETE)
    }

    @Test
    fun unparseableFillFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, "#GGGGGG", validText, validLine)

        assertFallback(palette, BubblePreviewPaletteFallbackReason.UNPARSEABLE)
    }

    @Test
    fun unparseableTextFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, validFill, "000000", validLine)

        assertFallback(palette, BubblePreviewPaletteFallbackReason.UNPARSEABLE)
    }

    @Test
    fun unparseableLineFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, validFill, validText, "#12345")

        assertFallback(palette, BubblePreviewPaletteFallbackReason.UNPARSEABLE)
    }

    @Test
    fun tooTransparentFillFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, "#7FFF0000", validText, validLine)

        assertFallback(palette, BubblePreviewPaletteFallbackReason.TOO_TRANSPARENT)
    }

    @Test
    fun tooTransparentTextFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, validFill, "#7F000000", validLine)

        assertFallback(palette, BubblePreviewPaletteFallbackReason.TOO_TRANSPARENT)
    }

    @Test
    fun tooTransparentLineFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, validFill, validText, "#7F000000")

        assertFallback(palette, BubblePreviewPaletteFallbackReason.TOO_TRANSPARENT)
    }

    @Test
    fun fullyTransparentColorIsRejected() {
        val palette = BubblePreviewPalette.resolveHex(false, "#00FFFFFF", validText, validLine)

        assertFallback(palette, BubblePreviewPaletteFallbackReason.TOO_TRANSPARENT)
    }

    @Test
    fun halfAlphaFillIsCompositedOverDefaultFillBeforeValidation() {
        val palette = BubblePreviewPalette.resolveHex(false, "#80FF0000", "#000000", "#000000")

        assertEquals(BubblePreviewPaletteSource.PACK, palette.characterSource)
        assertEquals("#F47A7F", palette.character.fill.toHexString())
        assertTrue(palette.character.fill.isOpaque)
        assertTrue(palette.character.textContrastRatio >= BubblePreviewPalette.MIN_TEXT_CONTRAST)
        assertTrue(palette.character.lineContrastRatio >= BubblePreviewPalette.MIN_LINE_CONTRAST)
    }

    @Test
    fun packAlphaCompositeUsesTheCurrentNightDefaultBase() {
        val day = BubblePreviewPalette.resolveHex(false, "#80FFFFFF", "#000000", "#000000")
        val night = BubblePreviewPalette.resolveHex(true, "#80FFFFFF", "#000000", "#000000")

        assertEquals("#F4FAFF", day.character.fill.toHexString())
        assertEquals("#8C9AA3", night.character.fill.toHexString())
        assertTrue(day.character.fill != night.character.fill)
    }

    @Test
    fun translucentTextAndLineAreCompositedOverEffectiveFill() {
        val palette = BubblePreviewPalette.resolveHex(false, "#FFFFFF", "#CC000000", "#80000000")

        assertEquals(BubblePreviewPaletteSource.PACK, palette.characterSource)
        assertEquals("#333333", palette.character.text.toHexString())
        assertEquals("#7F7F7F", palette.character.line.toHexString())
        assertTrue(palette.character.textContrastRatio >= BubblePreviewPalette.MIN_TEXT_CONTRAST)
        assertTrue(palette.character.lineContrastRatio >= BubblePreviewPalette.MIN_LINE_CONTRAST)
    }

    @Test
    fun poorTextContrastFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, "#FFFFFF", "#FFFFFF", "#000000")

        assertFallback(palette, BubblePreviewPaletteFallbackReason.TEXT_CONTRAST)
    }

    @Test
    fun poorLineContrastFallsBackWholeCharacterPalette() {
        val palette = BubblePreviewPalette.resolveHex(false, "#FFFFFF", "#000000", "#FFFFFF")

        assertFallback(palette, BubblePreviewPaletteFallbackReason.LINE_CONTRAST)
    }

    @Test
    fun lowContrastPackNeverLeaksIntoUserZoneOrCharacter() {
        val palette = BubblePreviewPalette.resolveHex(false, "#FFFFFF", "#FFFFFF", "#FFFFFF")

        assertFallback(palette, BubblePreviewPaletteFallbackReason.TEXT_CONTRAST)
        assertEquals(BubblePreviewPalette.defaultUser(false), palette.user)
        assertEquals(BubblePreviewPalette.defaultCharacter(false), palette.character)
    }

    @Test
    fun packInputRecordCanBeCachedAndResolvedRepeatedly() {
        val input = BubblePreviewPackPaletteInput(validFill, validText, validLine)

        val first = BubblePreviewPalette.resolve(false, input)
        val second = BubblePreviewPalette.resolve(false, input)

        assertEquals(first, second)
        assertEquals(BubblePreviewPaletteSource.PACK, first.characterSource)
    }

    @Test
    fun opaqueFilterReturnsInputUnchanged() {
        val color = BubblePreviewColor.parse("#123456")!!

        assertEquals(color, color.compositeOver(BubblePreviewColor.parse("#FFFFFF")!!))
    }

    @Test
    fun contrastRatioIsSymmetricAndBounded() {
        val black = BubblePreviewColor.parse("#000000")!!
        val white = BubblePreviewColor.parse("#FFFFFF")!!

        assertEquals(21.0, black.contrastRatio(white), 0.000001)
        assertEquals(black.contrastRatio(white), white.contrastRatio(black), 0.000001)
        assertEquals(1.0, black.contrastRatio(black), 0.000001)
    }

    @Test
    fun colorParserAcceptsAllFourStrictAndroidForms() {
        assertEquals(0xFFFFFFFF.toInt(), BubblePreviewColor.parse("#fff")!!.argb)
        assertEquals(0xFFA1B2C3.toInt(), BubblePreviewColor.parse("#A1B2C3")!!.argb)
        assertEquals(0xFFAABBCC.toInt(), BubblePreviewColor.parse("#abc")!!.argb)
        assertEquals(0x88AABBCC.toInt(), BubblePreviewColor.parse("#8abc")!!.argb)
        assertEquals(0x80A1B2C3.toInt(), BubblePreviewColor.parse("#80A1B2C3")!!.argb)
    }

    @Test
    fun colorParserIsStrictAboutShapeAndCharacters() {
        assertNull(BubblePreviewColor.parse(null))
        assertNull(BubblePreviewColor.parse(""))
        assertNull(BubblePreviewColor.parse("#"))
        assertNull(BubblePreviewColor.parse("#12"))
        assertNull(BubblePreviewColor.parse("#12345"))
        assertNull(BubblePreviewColor.parse("#1234567"))
        assertNull(BubblePreviewColor.parse("#123456789"))
        assertNull(BubblePreviewColor.parse("FFF1D6"))
        assertNull(BubblePreviewColor.parse(" #FFF1D6"))
        assertNull(BubblePreviewColor.parse("#FFF1D6 "))
        assertNull(BubblePreviewColor.parse("0xFFF1D6"))
        assertNull(BubblePreviewColor.parse("#GGGGGG"))
        assertNull(BubblePreviewColor.parse("#12-456"))
        assertNull(BubblePreviewColor.parse("#\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16"))
    }

    @Test
    fun hexRoundTripKeepsOpaqueAndAlphaColors() {
        listOf("#FFF1D6", "#3D3020", "#80FF0000", "#00000000").forEach { hex ->
            val parsed = BubblePreviewColor.parse(hex)
            assertNotNull(parsed)
            val roundTrip = BubblePreviewColor.parse(parsed!!.toHexString())
            assertEquals(parsed, roundTrip)
        }
    }

    @Test
    fun helperOfRejectsOutOfRangeChannels() {
        try {
            BubblePreviewColor.of(256, 0, 0, 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun packFillCannotChangeUserWarmColors() {
        val palette = BubblePreviewPalette.resolveHex(false, "#000000", "#FFFFFF", "#FFFFFF")

        assertEquals(BubblePreviewPalette.defaultUser(false), palette.user)
    }

    private fun assertFallback(
        palette: BubblePreviewPalette,
        reason: BubblePreviewPaletteFallbackReason,
    ) {
        assertEquals(BubblePreviewPaletteSource.DEFAULT, palette.characterSource)
        assertEquals(reason, palette.characterFallbackReason)
        assertEquals(BubblePreviewPalette.defaultCharacter(palette.night), palette.character)
        assertFalse(palette.usesPackCharacterColors)
        assertTrue(palette.character.isOpaque)
    }
}
