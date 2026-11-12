package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C2 source contract for the process draft, token handoff and request metadata.
 *
 * This is deliberately a wiring/ownership lock, not a second request model. The
 * only request identity remains ChatSession.requestGeneration; workerBusy is a
 * read of the actual worker thread, not a new UI phase or persistence field.
 */
class ChatDraftIntegrationContractTest {
    private fun source(path: String): String = File(path).readText()

    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("missing body for $signature", open >= 0)
        var depth = 0
        var index = open
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        while (index < source.length) {
            val char = source[index]
            val next = if (index + 1 < source.length) source[index + 1] else '\u0000'
            when {
                inLineComment -> if (char == '\n') inLineComment = false
                inBlockComment -> if (char == '*' && next == '/') {
                    inBlockComment = false
                    index += 1
                }
                inString -> when {
                    char == '\\' -> index += 1
                    char == '"' -> inString = false
                }
                inChar -> when {
                    char == '\\' -> index += 1
                    char == '\'' -> inChar = false
                }
                char == '/' && next == '/' -> {
                    inLineComment = true
                    index += 1
                }
                char == '/' && next == '*' -> {
                    inBlockComment = true
                    index += 1
                }
                char == '"' -> inString = true
                char == '\'' -> inChar = true
                char == '{' -> depth += 1
                char == '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1).trimEnd()
                }
            }
            index += 1
        }
        throw AssertionError("unbalanced body for $signature")
    }

    @Test
    fun chatEntryCarriesOnlyAOneUseTokenAndHasAnUnconfiguredRecordsEntry() {
        val entry = source("src/main/java/com/wwkhub/bluefin/ChatEntry.kt")

        assertTrue(entry.contains("const val EXTRA_DRAFT_TOKEN"))
        assertTrue(entry.contains("ChatDraft.state.issueHandoff().token"))
        assertTrue(entry.contains("putExtra(EXTRA_DRAFT_TOKEN, token)"))
        assertFalse(entry.contains("\"com.wwkhub.bluefin.extra.CHAT_DRAFT\""))
        assertFalse(entry.contains("putExtra(EXTRA_DRAFT,"))

        val records = methodBody(entry, "    fun openRecords(context: Context) {")
        assertTrue(records.contains("issueHandoff"))
        assertTrue(records.contains("openScreen(context"))
        assertFalse(records.contains("ModelEndpointStore"))
        assertFalse(records.contains("isReady"))
        assertFalse(records.contains("showUnconfigured"))
        assertFalse(records.contains("MaterialAlertDialogBuilder"))
    }

    @Test
    fun chatScreenReadsTheLatestProcessDraftAndGuardsSavedViewState() {
        val screen = source("src/main/java/com/wwkhub/bluefin/ChatScreen.kt")

        assertTrue(screen.contains("getLongExtra(ChatEntry.EXTRA_DRAFT_TOKEN, 0L)"))
        assertFalse(screen.contains("getStringExtra(ChatEntry.EXTRA_DRAFT"))
        assertTrue(screen.contains("input.isSaveEnabled = false"))
        assertTrue(screen.contains("ChatDraft.state.rebind(token)"))
        assertTrue(screen.contains("ChatDraft.state.edit(text)"))
        assertTrue(screen.contains("applyingDraft"))

        val send = methodBody(screen, "    private fun sendMessage() {")
        assertTrue(send.contains("captureSend()"))
        assertTrue(send.contains("onSendOutcome(capture"))
        assertTrue(send.contains("ChatSendResult.NOT_CONFIGURED"))

        val render = methodBody(screen, "    private fun render(snapshot: ChatSnapshot) {")
        assertTrue(render.contains("snapshot.workerBusy"))
        assertTrue(render.contains("input.isEnabled = true"))
        assertTrue(render.contains("updateSendEnabled()"))

        val update = methodBody(screen, "    private fun updateSendEnabled() {")
        assertTrue(update.contains("!busy && configured"))
        assertTrue(update.contains("isNotBlank()"))
    }

    @Test
    fun chatScreenSignalsExistingBubbleSuppressionWithoutConstructingOne() {
        val screen = source("src/main/java/com/wwkhub/bluefin/ChatScreen.kt")

        val start = methodBody(screen, "    override fun onStart() {")
        assertTrue(start.contains("OverlayAppVisibility.setForeground(true)"))
        assertTrue(start.contains("bindLatestDraft()"))

        val stop = methodBody(screen, "    override fun onStop() {")
        assertTrue(stop.contains("OverlayAppVisibility.setForeground(false)"))

        assertFalse(screen.contains("BubblePreviewController("))
        assertFalse(screen.contains("BubblePreviewWindow("))
        assertFalse(screen.contains("BubblePreviewModule"))
    }

    @Test
    fun chatSessionExposesExistingGenerationAndActualWorkerAlive() {
        val session = source("src/main/java/com/wwkhub/bluefin/ChatSession.kt")

        assertTrue(session.contains("val requestGeneration: Int"))
        assertTrue(session.contains("val workerBusy: Boolean"))
        assertTrue(session.contains("requestGeneration = requestGeneration"))
        assertTrue(session.contains("workerBusy = worker?.isAlive == true"))
        assertTrue(session.contains("val generation = ++requestGeneration"))
        assertTrue(session.contains("requestGeneration += 1"))

        // No parallel request identity, persisted generation or UI-phase guess.
        assertFalse(session.contains("workerBusy = phase"))
        assertFalse(session.contains("requestToken"))
        assertFalse(session.contains("requestId"))
        assertFalse(session.contains("SharedPreferences"))
        assertEquals(1, Regex("ChatCompletionsClient\\(\\)").findAll(session).count())
    }

    @Test
    fun onlyChatEntryAndChatScreenOwnTheProcessDraft() {
        val root = File("src/main/java")
        val owners = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("ChatDraft.") }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(
            listOf("ChatEntry.kt", "ChatScreen.kt", "OverlayWindow.kt"),
            owners,
        )

        val overlayWindow = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        // Stage C wires the process draft into the existing overlay input, but
        // only through the single holder and with saveEnabled disabled.
        assertTrue(overlayWindow.contains("ChatDraft.state.captureSend()"))
        assertTrue(overlayWindow.contains("ChatDraft.state.onSendOutcome("))
        assertTrue(overlayWindow.contains("ChatDraft.state.rebind()"))
        assertTrue(overlayWindow.contains("chatInput.isSaveEnabled = false"))
        assertTrue(overlayWindow.contains("if (applyingDraft) return"))
    }
}
