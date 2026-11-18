package com.wwkhub.bluefin.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cache keys must change with anything that changes the audio, and be stable. */
class TtsCacheKeyTest {

    @Test
    fun keyIsStableAcrossCalls() {
        val a = TtsCache.key("你好呀。", "timbre1", 1.0f, "happy")
        val b = TtsCache.key("你好呀。", "timbre1", 1.0f, "happy")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun everyAudioShapingInputChangesTheKey() {
        val base = TtsCache.key("你好呀。", "timbre1", 1.0f, "happy")
        assertNotEquals(base, TtsCache.key("你好呀！", "timbre1", 1.0f, "happy"))
        assertNotEquals(base, TtsCache.key("你好呀。", "timbre2", 1.0f, "happy"))
        assertNotEquals(base, TtsCache.key("你好呀。", "timbre1", 1.5f, "happy"))
        assertNotEquals(base, TtsCache.key("你好呀。", "timbre1", 1.0f, "sad"))
    }

    @Test
    fun speedRoundingMatchesTheSliderGranularity() {
        assertEquals(
            TtsCache.key("x", "v", 1.0f, "neutral"),
            TtsCache.key("x", "v", 1.001f, "neutral"),
        )
        assertNotEquals(
            TtsCache.key("x", "v", 1.0f, "neutral"),
            TtsCache.key("x", "v", 1.01f, "neutral"),
        )
    }
}