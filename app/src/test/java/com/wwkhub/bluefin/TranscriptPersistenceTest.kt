package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun incompleteStagingFileDoesNotReplaceExistingHistory() {
        val transcript = folder.newFile("bluefin_chat_transcript.json")
        transcript.writeText("""[{"role":"user","text":"旧对话"},{"role":"assistant","text":"","stopped":true}]""")
        File(transcript.parentFile, "${transcript.name}.new").writeText("[{")
        assertEquals(
            listOf(ChatLine(ChatRole.USER, "旧对话"), ChatLine(ChatRole.ASSISTANT, "", stopped = true)),
            TranscriptFile.read(transcript),
        )
        val newHistory = listOf(ChatLine(ChatRole.USER, "新对话"))
        TranscriptFile.write(transcript, newHistory)
        assertEquals(newHistory, TranscriptFile.read(transcript))
    }

    @Test
    fun clearingDiscardsQueuedWritesAndPreservesNewTurns() {
        val pending = mutableListOf<Runnable>()
        val writes = TranscriptWrites { pending.add(it) }
        val saved = mutableListOf<String>()
        writes.write { saved.add("old turn") }
        writes.write { saved.add("old delta") }
        writes.clear { saved.add("cleared") }
        writes.write { saved.add("new turn") }
        pending.forEach(Runnable::run)
        assertEquals(listOf("cleared", "new turn"), saved)
    }
}
