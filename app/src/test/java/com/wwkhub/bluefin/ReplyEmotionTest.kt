package com.wwkhub.bluefin

import com.wwkhub.bluefin.voice.tts.TtsEmotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emotion marker must never leak into displayed/persisted text, must
 * survive arbitrary streaming chunk boundaries, and must degrade safely.
 */
class ReplyEmotionTest {

    private fun feedAll(vararg deltas: String): Pair<ReplyEmotionFeed, String> {
        val feed = ReplyEmotionFeed()
        val out = StringBuilder()
        deltas.forEach { out.append(feed.feed(it)) }
        out.append(feed.flush())
        return feed to out.toString()
    }

    @Test
    fun parsesAWholeMarkerInOneDelta() {
        val (feed, text) = feedAll("[[emotion:happy]] 你好呀！")
        assertEquals(TtsEmotion.HAPPY, feed.emotion)
        assertEquals("你好呀！", text)
    }

    @Test
    fun parsesAcrossEveryChunkBoundary() {
        val full = "[[emotion:tsundere]] 哼，才不是特意等你的。"
        for (split in 1 until full.length) {
            val (feed, text) = feedAll(full.substring(0, split), full.substring(split))
            assertEquals("split=$split", TtsEmotion.TSUNDERE, feed.emotion)
            assertEquals("split=$split", "哼，才不是特意等你的。", text)
        }
    }

    @Test
    fun holdsPartialMarkerWithoutFlashingBrackets() {
        val feed = ReplyEmotionFeed()
        assertEquals("", feed.feed("[[emo"))
        assertEquals("", feed.feed("tion:hap"))
        val rest = feed.feed("py]] 早呀")
        assertEquals("早呀", rest)
        assertEquals(TtsEmotion.HAPPY, feed.emotion)
    }

    @Test
    fun plainTextPassesThroughImmediately() {
        val (feed, text) = feedAll("今天天气不错。")
        assertEquals(TtsEmotion.NEUTRAL, feed.emotion)
        assertEquals("今天天气不错。", text)
    }

    @Test
    fun unknownEmotionDegradesToNeutralAndHidesMarker() {
        val (feed, text) = feedAll("[[emotion:furious]] 别惹我。")
        assertEquals(TtsEmotion.NEUTRAL, feed.emotion)
        assertEquals("别惹我。", text)
    }

    @Test
    fun brokenMarkerBecomesOrdinaryText() {
        val (feed, text) = feedAll("[[emotion:happy] 好像少了个括号")
        assertEquals(TtsEmotion.NEUTRAL, feed.emotion)
        assertTrue(text.contains("[[emotion:happy]"))
    }

    @Test
    fun overlongPrefixIsReleasedAsText() {
        val long = "[" + "x".repeat(60)
        val (feed, text) = feedAll(long)
        assertEquals(TtsEmotion.NEUTRAL, feed.emotion)
        assertEquals(long, text)
    }

    @Test
    fun flushReleasesHeldWhitespaceOnlyPrefix() {
        val feed = ReplyEmotionFeed()
        feed.feed("   ")
        assertEquals("   ", feed.flush())
    }

    @Test
    fun markerOnlyReplyLeavesNothingToDisplay() {
        val (feed, text) = feedAll("[[emotion:sad]]")
        assertEquals(TtsEmotion.SAD, feed.emotion)
        assertEquals("", text)
    }

    @Test
    fun allEightEmotionsParse() {
        for (emotion in TtsEmotion.entries) {
            val (feed, _) = feedAll("[[emotion:${emotion.wire}]] 测试。")
            assertEquals(emotion, feed.emotion)
        }
    }
}