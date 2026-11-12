package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonaRulesTest {
    private val expected =
        "你是蓝鳍，住在用户手机里的桌宠，蓝发的鲸鱼少年，陪在屏幕边上。" +
            "说话像朋友，简短自然，不必一次说很长。不知道就说不知道，不要编造。" +
            "你现在只能文字对话，不能操作手机、不能看屏幕、也不能出声。" +
            "用户戳、拖、摸你是在跟你玩，不必每次都当成要办事。" +
            "用用户使用的语言回答，默认中文。"

    @Test
    fun defaultPersonaIsTheExactProductCopy() {
        assertEquals(expected, PersonaText.DEFAULT)
        assertEquals(expected, PersonaText.restoreDefault())
        assertTrue(PersonaText.isDefault("  $expected  "))
    }

    @Test
    fun blankPersonaFallsBackToTheDefault() {
        assertEquals(expected, PersonaText.normalize(null))
        assertEquals(expected, PersonaText.normalize(""))
        assertEquals(expected, PersonaText.normalize("   "))
        assertEquals("自定义设定", PersonaText.normalize(" 自定义设定 "))
    }

    @Test
    fun clearingHistoryDoesNotTouchPersonaStorage() {
        val transcript = File("src/main/java/com/wwkhub/bluefin/TranscriptFile.kt").readText()
        val clearStart = transcript.indexOf("fun clear(")
        val clearEnd = transcript.indexOf("private fun file(", clearStart)
        assertTrue(clearStart >= 0 && clearEnd > clearStart)
        val clearBody = transcript.substring(clearStart, clearEnd)
        assertFalse(clearBody.contains("Persona"))
        assertFalse(clearBody.contains("persona"))
        val session = File("src/main/java/com/wwkhub/bluefin/ChatSession.kt").readText()
        val start = session.indexOf("fun clearHistory()")
        val end = session.indexOf("private fun runRequest(", start)
        assertTrue(start >= 0 && end > start)
        val sessionClearBody = session.substring(start, end)
        assertFalse(sessionClearBody.contains("PersonaRepository"))
        assertFalse(sessionClearBody.contains("PersonaText"))
        assertTrue(sessionClearBody.contains("TranscriptFile.clear"))
    }

    @Test
    fun personaIsStoredSeparatelyFromTheTranscript() {
        val repo = File("src/main/java/com/wwkhub/bluefin/PersonaRepository.kt").readText()
        assertTrue(repo.contains("bluefin_persona"))
        assertFalse(repo.contains("bluefin_chat_transcript"))
        val transcript = File("src/main/java/com/wwkhub/bluefin/TranscriptFile.kt").readText()
        assertTrue(transcript.contains("bluefin_chat_transcript"))
        assertFalse(transcript.contains("bluefin_persona"))
    }
}
