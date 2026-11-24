package com.wwkhub.bluefin.voice.audio

import com.wwkhub.bluefin.PackPaths
import com.wwkhub.bluefin.VoicePackFormat
import com.wwkhub.bluefin.VoicePackReject
import com.wwkhub.bluefin.VoicePackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The manifest this feature writes must pass the importer that already exists,
 * or a created pack would only fail later when the engine loads it.
 */
class VoicePackDraftTest {

    @Test
    fun generatedIdsAreAllValidAndDistinct() {
        val ids = HashSet<String>()
        for (index in 0 until 1000) {
            val id = VoicePackDraft.newId(1_700_000_000_000L + index, index * 7919)
            assertTrue("invalid id: $id", PackPaths.validId(id))
            assertTrue("reserved id: $id", id !in VoicePackFormat.RESERVED_IDS)
            assertTrue("built-in id: $id", id !in VoicePackFormat.BUILT_IN_IDS)
            assertTrue("duplicate id: $id", ids.add(id))
        }
    }

    @Test
    fun idShapeIsStableAtTheEpochAndAtLargeTimestamps() {
        val early = VoicePackDraft.newId(0L, 0)
        val late = VoicePackDraft.newId(Long.MAX_VALUE / 2, Int.MAX_VALUE)
        assertTrue(PackPaths.validId(early))
        assertTrue(PackPaths.validId(late))
        assertTrue(late.length <= 32)
    }

    @Test
    fun negativeSaltAndTimeStillProduceAValidId() {
        val id = VoicePackDraft.newId(-1L, -1)
        assertTrue("invalid id: $id", PackPaths.validId(id))
    }

    @Test
    fun duplicateNamesGetAFullWidthSuffix() {
        assertEquals("阿七", VoicePackDraft.uniqueName("阿七", emptyList()))
        assertEquals("阿七", VoicePackDraft.uniqueName("  阿七  ", listOf("别人")))
        assertEquals("阿七（2）", VoicePackDraft.uniqueName("阿七", listOf("阿七")))
        assertEquals("阿七（3）", VoicePackDraft.uniqueName("阿七", listOf("阿七", "阿七（2）")))
    }

    @Test
    fun suffixedNamesStayInsideTheLengthLimit() {
        val long = "音".repeat(VoicePackDraft.MAX_NAME_CHARS + 20)
        val first = VoicePackDraft.uniqueName(long, emptyList())
        assertEquals(VoicePackDraft.MAX_NAME_CHARS, first.length)

        val second = VoicePackDraft.uniqueName(long, listOf(first))
        assertTrue("too long: ${second.length}", second.length <= VoicePackDraft.MAX_NAME_CHARS)
        assertTrue(second.endsWith("（2）"))
        assertNotEquals(first, second)
    }

    @Test
    fun blankNameFallsBackToTheDefault() {
        assertEquals(VoicePackDraft.DEFAULT_NAME, VoicePackDraft.uniqueName("   ", emptyList()))
    }

    @Test
    fun manifestIsAcceptedByTheExistingParser() {
        val id = VoicePackDraft.newId(1_700_000_000_000L, 42)
        val body = VoicePackDraft.manifest(
            id = id,
            name = "小深的音色",
            about = "录自手机麦克风",
            referenceText = "今天也要开开心心的哦。",
            sampleRate = 24_000,
        )

        val pack = VoicePackFormat.parse(body, rejectBuiltInIds = true)
        assertEquals(id, pack.id)
        assertEquals("小深的音色", pack.name)
        assertEquals("录自手机麦克风", pack.about)
        assertEquals("今天也要开开心心的哦。", pack.referenceText)
        assertEquals(24_000, pack.sampleRate)
        assertEquals(VoicePackFormat.KIND_FISH, pack.kind)
        assertEquals(VoicePackFormat.REFERENCE_FILE, pack.reference)
        assertEquals(1.0f, pack.speed, 1e-6f)
        assertEquals(4, pack.numSteps)
        assertTrue(!pack.builtIn)
        assertEquals(null, pack.fishReferenceId)
    }

    @Test
    fun quotesAndControlCharactersSurviveTheRoundTrip() {
        val tricky = "他说：\"你好\"\n然后\t离开了\\ \u0001"
        val body = VoicePackDraft.manifest(
            id = VoicePackDraft.newId(1L, 1),
            name = "带\"引号\"的名字",
            about = tricky,
            referenceText = tricky,
            sampleRate = 24_000,
        )

        val pack = VoicePackFormat.parse(body, rejectBuiltInIds = true)
        assertEquals("带\"引号\"的名字", pack.name)
        assertEquals(tricky, pack.referenceText)
    }

    @Test
    fun emojiAndCjkArePassedThroughUnescaped() {
        val text = "你好呀🙂，我是小深～"
        val body = VoicePackDraft.manifest(
            id = VoicePackDraft.newId(1L, 2),
            name = "小深🙂",
            about = "描述",
            referenceText = text,
            sampleRate = 24_000,
        )
        assertEquals(text, VoicePackFormat.parse(body, rejectBuiltInIds = true).referenceText)
    }

    @Test
    fun aboutIsTruncatedByTheParserNotRejected() {
        val body = VoicePackDraft.manifest(
            id = VoicePackDraft.newId(1L, 3),
            name = "名字",
            about = "长".repeat(VoicePackDraft.MAX_ABOUT_CHARS + 40),
            referenceText = "参考文本",
            sampleRate = 24_000,
        )
        assertEquals(
            VoicePackDraft.MAX_ABOUT_CHARS,
            VoicePackFormat.parse(body, rejectBuiltInIds = true).about.length,
        )
    }

    @Test
    fun anEmptyReferenceTextIsStillRejectedByTheParser() {
        val body = VoicePackDraft.manifest(
            id = VoicePackDraft.newId(1L, 4),
            name = "名字",
            about = "描述",
            referenceText = "",
            sampleRate = 24_000,
        )
        try {
            VoicePackFormat.parse(body, rejectBuiltInIds = true)
            fail("an empty transcript must not import")
        } catch (error: VoicePackException) {
            assertEquals(VoicePackReject.INVALID_TEXT, error.reason)
        }
    }
}
