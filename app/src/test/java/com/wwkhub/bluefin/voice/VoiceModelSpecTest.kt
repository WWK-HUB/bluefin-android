package com.wwkhub.bluefin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelSpecTest {
    @Test
    fun downloadSourcesPreferTheMainlandMirror() {
        for (file in VoiceModelSpec.FILES) {
            val urls = VoiceModelSpec.urlsFor(file)
            assertTrue(urls.first().startsWith("https://hf-mirror.com/"))
            assertTrue(urls.any { it.startsWith("https://huggingface.co/") })
            assertTrue(urls.all { it.endsWith("/$file") })
            assertTrue(urls.all { it.startsWith("https://") })
            assertTrue(urls.all { it.contains(VoiceModelSpec.ID) })
        }
    }

    @Test
    fun completenessRejectsTruncatedFiles() {
        assertFalse(VoiceModelSpec.isComplete(0, 0))
        assertFalse(VoiceModelSpec.isComplete(VoiceModelSpec.MIN_MODEL_BYTES - 1, VoiceModelSpec.MIN_TOKENS_BYTES))
        assertFalse(VoiceModelSpec.isComplete(VoiceModelSpec.MIN_MODEL_BYTES, VoiceModelSpec.MIN_TOKENS_BYTES - 1))
        assertTrue(VoiceModelSpec.isComplete(VoiceModelSpec.MIN_MODEL_BYTES, VoiceModelSpec.MIN_TOKENS_BYTES))
        assertTrue(VoiceModelSpec.isComplete(VoiceModelSpec.MODEL_BYTES, VoiceModelSpec.TOKENS_BYTES))
    }

    @Test
    fun pinnedSizesMatchTheUpstreamRevision() {
        assertEquals(81_828_675L, VoiceModelSpec.MODEL_BYTES)
        assertEquals(75_352L, VoiceModelSpec.TOKENS_BYTES)
        assertEquals(VoiceModelSpec.MODEL_BYTES + VoiceModelSpec.TOKENS_BYTES, VoiceModelSpec.TOTAL_BYTES)
        assertEquals(VoiceModelSpec.MODEL_BYTES, VoiceModelSpec.expectedBytes(VoiceModelSpec.MODEL_FILE))
        assertEquals(VoiceModelSpec.TOKENS_BYTES, VoiceModelSpec.expectedBytes(VoiceModelSpec.TOKENS_FILE))
        assertEquals(0L, VoiceModelSpec.expectedBytes("other.bin"))
    }

    @Test
    fun modelFileNamesStayConsistent() {
        assertEquals("model.int8.onnx", VoiceModelSpec.MODEL_FILE)
        assertEquals("tokens.txt", VoiceModelSpec.TOKENS_FILE)
        assertEquals(listOf("model.int8.onnx", "tokens.txt"), VoiceModelSpec.FILES)
    }
}