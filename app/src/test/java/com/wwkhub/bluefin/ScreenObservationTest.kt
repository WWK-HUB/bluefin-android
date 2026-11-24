package com.wwkhub.bluefin

import com.wwkhub.bluefin.voice.tts.TtsEmotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenObservationTest {
    @Test
    fun readsDistinctSummaryAndPetRemarkFromVisionModel() {
        val response = """
            ```json
            {"summary":"用户正在浏览山景照片。","remark":"云都快碰到山尖了！","emotion":"happy"}
            ```
        """.trimIndent()
        assertEquals(
            ScreenObservation("用户正在浏览山景照片。", "云都快碰到山尖了！", TtsEmotion.HAPPY),
            ScreenObservation.parse(response),
        )
    }

    @Test
    fun returnsOnlyTheSummaryWhenTheModelStaysQuiet() {
        val summary = "画面仍是同一张照片。"
        assertEquals(ScreenObservation(summary), ScreenObservation.parse("""{"summary":"$summary","remark":null}"""))
        assertEquals(ScreenObservation(summary), ScreenObservation.parse("""{"summary":"$summary","remark":"  "}"""))
        assertEquals(ScreenObservation(summary), ScreenObservation.parse(summary))
    }

    @Test
    fun ignoresMalformedRepliesOrObjectsWithoutASummary() {
        assertNull(ScreenObservation.parse(""))
        assertNull(ScreenObservation.parse("""{"summary":null,"remark":"不应播放"}"""))
        assertNull(ScreenObservation.parse("""{"summary":"没写完"""))
    }
}
