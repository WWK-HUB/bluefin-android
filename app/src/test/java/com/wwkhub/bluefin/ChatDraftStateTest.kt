package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDraftStateTest {

    @Test
    fun processOwnerIsLazyAndStable() {
        assertTrue(ChatDraft.state === ChatDraft.state)
    }

    @Test
    fun everyEditAdvancesTheRevisionAndTextIsCappedAt4000CodeUnits() {
        val state = ChatDraftState()

        val first = state.edit("a")
        val second = state.edit("a")
        val third = state.edit("ab")

        assertEquals(1L, first.revision)
        assertEquals(2L, second.revision)
        assertEquals(3L, third.revision)
        assertEquals("ab", state.text)

        state.edit("a".repeat(5_000))
        assertEquals(ChatDraftState.MAX_DRAFT_CHARS, state.text.length)

        val emoji = "\uD83D\uDE00"
        state.edit("a".repeat(3_999) + emoji)
        assertEquals(3_999, state.text.length)
        assertFalse(Character.isHighSurrogate(state.text.last()))
    }

    @Test
    fun oldEmptyIntentCannotOverrideANewerEdit() {
        val state = ChatDraftState()
        state.edit("keep me")
        val oldHandoff = state.issueHandoff()

        state.edit("")

        val resumed = state.consumeHandoff(oldHandoff.token)
        assertEquals("", resumed.text)
        assertTrue(resumed.isEmpty)
        assertEquals(state.revision, resumed.revision)
    }

    @Test
    fun oldNonEmptyIntentCannotOverrideANewerEdit() {
        val state = ChatDraftState()
        state.edit("old text")
        val oldHandoff = state.issueHandoff()

        state.edit("new text")
        val resumed = state.rebind(oldHandoff.token)

        assertEquals("new text", resumed.text)
    }

    @Test
    fun handoffRotationReplayUsesTheLatestDraftAndDoesNotEditIt() {
        val state = ChatDraftState()
        state.edit("draft")
        val handoff = state.issueHandoff()
        val revision = state.revision

        val firstOpen = state.consumeHandoff(handoff.token)
        assertEquals("draft", firstOpen.text)
        assertEquals(revision, firstOpen.revision)

        state.edit("newer draft")
        val rotatedReplay = state.consumeHandoff(handoff.token)
        assertEquals("newer draft", rotatedReplay.text)
        assertEquals(state.revision, rotatedReplay.revision)
    }

    @Test
    fun rebindIsIdempotentAndNeverCreatesAnEdit() {
        val state = ChatDraftState()
        state.edit("hello")
        val before = state.snapshot()
        val handoff = state.issueHandoff()

        val first = state.rebind(handoff.token)
        val second = state.rebind(handoff.token)

        assertEquals(before, first)
        assertEquals(before, second)
        assertEquals(before.revision, state.revision)
    }

    @Test
    fun startedClearsOnlyTheCapturedRevision() {
        val state = ChatDraftState()
        state.edit("hello")
        val captured = state.captureSend()

        state.edit("hello world")
        val afterNewerEdit = state.onSendOutcome(captured, ChatDraftSendOutcome.STARTED)
        assertEquals("hello world", afterNewerEdit.text)

        val freshCapture = state.captureSend()
        val cleared = state.onSendOutcome(freshCapture, ChatDraftSendOutcome.STARTED)
        assertEquals("", cleared.text)
        assertTrue(cleared.isEmpty)
        assertTrue(cleared.revision > freshCapture.revision)

        val staleReplay = state.onSendOutcome(freshCapture, ChatDraftSendOutcome.STARTED)
        assertEquals("", staleReplay.text)
        assertEquals(cleared.revision, staleReplay.revision)
    }

    @Test
    fun emptyBusyAndNotConfiguredOutcomesLeaveTheDraftUntouched() {
        val state = ChatDraftState()
        state.edit("keep me")
        val captured = state.captureSend()

        for (outcome in listOf(
            ChatDraftSendOutcome.EMPTY,
            ChatDraftSendOutcome.BUSY,
            ChatDraftSendOutcome.NOT_CONFIGURED,
        )) {
            val result = state.onSendOutcome(captured, outcome)
            assertEquals("keep me", result.text)
            assertEquals(captured.revision, result.revision)
        }
    }

    @Test
    fun latestEmptyDraftWinsOverAnyOlderHandoffOrIntent() {
        val state = ChatDraftState()
        state.edit("abc")
        val handoff = state.issueHandoff()

        state.edit("")
        assertEquals("", state.consumeHandoff(handoff.token).text)
        assertEquals("", state.rebind(handoff.token).text)

        state.edit("latest")
        assertEquals("latest", state.rebind(handoff.token).text)
        assertFalse(state.isEmpty)
    }
}
