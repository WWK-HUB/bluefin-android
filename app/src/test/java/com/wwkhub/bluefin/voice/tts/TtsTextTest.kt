package com.wwkhub.bluefin.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speech is only as good as the text handed to the engine: Markdown read aloud
 * is noise, and a sentence too short makes ZipVoice return empty audio instead
 * of speaking. Both rules are enforced here because they are hard to notice on a
 * device until a reply sounds wrong.
 */
class TtsTextTest {

    @Test
    fun stripsFencedCodeEntirely() {
        val text = TtsText.stripMarkdown("看这里：\n```kotlin\nval x = 1\n```\n好了。")
        assertFalse(text.contains("val x"))
        assertTrue(text.startsWith("看这里"))
    }

    @Test
    fun keepsLinkTextAndDropsTheUrl() {
        val text = TtsText.stripMarkdown("详见[官方文档](https://example.com/a/b)。")
        assertTrue(text.contains("官方文档"))
        assertFalse(text.contains("example.com"))
    }

    @Test
    fun dropsBareUrls() {
        val text = TtsText.stripMarkdown("打开 https://example.com/x 就行。")
        assertFalse(text.contains("http"))
    }

    @Test
    fun removesEmphasisAndHeadingMarks() {
        val text = TtsText.stripMarkdown("## 标题\n**重点**和_斜体_还有~~删除~~")
        assertEquals("标题 重点和斜体还有删除", text)
    }

    @Test
    fun removesListMarkers() {
        val text = TtsText.stripMarkdown("- 第一项\n- 第二项\n1. 第三项")
        assertEquals("第一项 第二项 第三项", text.replace("\n", " ").trim())
    }

    @Test
    fun splitsOnChineseSentenceEndings() {
        val sentences = TtsText.sentences("主人，你回来啦！我才没有等你呢。真的吗？")
        assertTrue(sentences.isNotEmpty())
        assertTrue(sentences.first().contains("主人"))
    }

    @Test
    fun mergesSentencesTooShortToSynthesise() {
        // A lone "嗯" is exactly what ZipVoice refuses to speak.
        val sentences = TtsText.sentences("嗯。我在呢。")
        assertTrue("expected the short fragment to be merged: $sentences", sentences.size == 1)
        assertTrue(sentences.first().contains("我在呢"))
    }

    @Test
    fun keepsALoneShortReplyRatherThanDroppingIt() {
        val sentences = TtsText.sentences("嗯。")
        assertEquals(1, sentences.size)
        assertEquals("嗯。", sentences.first())
    }

    @Test
    fun returnsNothingForAnEmptyReply() {
        assertTrue(TtsText.sentences("").isEmpty())
        assertTrue(TtsText.sentences("   ").isEmpty())
        assertFalse(TtsText.hasSpeakableText("*"))
    }

    @Test
    fun capsTheSpokenLength() {
        val long = "这是一句很长的话。".repeat(80)
        val sentences = TtsText.sentences(long)
        val total = sentences.sumOf { it.length }
        assertTrue("spoken $total chars", total <= TtsText.MAX_SPEAK_CHARS + 16)
    }

    @Test
    fun countsOnlySpeakableCharacters() {
        assertEquals(2, TtsText.speakableLength("嗯，好！"))
        assertEquals(0, TtsText.speakableLength("，。！？"))
        assertTrue(TtsText.hasSpeakableText("我在呢。"))
    }

    @Test
    fun splitsOnCommasForRunawaySentences() {
        val pieces = TtsText.splitOnCommas("第一段，第二段，第三段")
        assertEquals(3, pieces.size)
    }

    @Test
    fun sentenceTextIsNotAListOfEmptyStrings() {
        val sentences = TtsText.sentences("好。\n\n\n")
        assertTrue(sentences.none { it.isBlank() })
    }

    @Test
    fun stripsEmotionMarkersAndDeliveryCues() {
        val text = TtsText.stripMarkdown("[[emotion:happy]] 你好呀 [whisper] 悄悄话")
        assertFalse(text.contains("["))
        assertFalse(text.contains("emotion"))
        assertTrue(text.contains("你好呀"))
        assertTrue(text.contains("悄悄话"))
    }

    @Test
    fun keepsNormalSentencesWholeIncludingCommas() {
        val sentences = TtsText.sentences("主人，你回来啦，我才没有等你呢。")
        assertEquals(1, sentences.size)
        assertTrue(sentences.first().contains("，"))
    }

    @Test
    fun minCharsParameterRaisesTheMergeFloor() {
        val merged = TtsText.sentences("你好呀。我在呢。今天开心吗？", minChars = 40)
        assertEquals(1, merged.size)
        // The default path keeps one sentence per native ZipVoice call.
        val perSentence = TtsText.sentences("你好呀。我在呢。今天开心吗？")
        assertEquals(3, perSentence.size)
    }

    @Test
    fun splitsOnlyOverlongSentencesOnCommas() {
        val clause = "这是一段比较长的内容，"
        val long = clause.repeat(12) + "结尾。"
        val sentences = TtsText.sentences(long, limit = 1000)
        assertTrue("expected comma split for overlong sentence: $sentences", sentences.size > 1)
        assertTrue(sentences.all { it.length <= TtsText.MAX_SENTENCE_CHARS || it == long })
    }
}
