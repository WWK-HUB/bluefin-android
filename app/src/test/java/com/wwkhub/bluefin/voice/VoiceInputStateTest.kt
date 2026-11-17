package com.wwkhub.bluefin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputStateTest {
    @Test
    fun recordingOnlyStartsFromIdle() {
        val state = VoiceInputState()
        val token = state.beginRecording()
        assertNotNull(token)
        assertEquals(VoicePhase.RECORDING, state.phase)
        assertNull(state.beginRecording())
    }

    @Test
    fun samplesMoveTheCurrentRoundToRecognizing() {
        val state = VoiceInputState()
        val token = state.beginRecording()!!
        assertTrue(state.beginRecognizing(token))
        assertEquals(VoicePhase.RECOGNIZING, state.phase)
        assertFalse(state.beginRecognizing(token))
    }

    @Test
    fun finishEndsTheRoundAndRejectsLateDuplicates() {
        val state = VoiceInputState()
        val token = state.beginRecording()!!
        assertTrue(state.beginRecognizing(token))
        assertTrue(state.finish(token))
        assertEquals(VoicePhase.IDLE, state.phase)
        assertFalse(state.finish(token))
    }

    @Test
    fun cancelInvalidatesInFlightCallbacks() {
        val state = VoiceInputState()
        val token = state.beginRecording()!!
        assertTrue(state.cancel())
        assertEquals(VoicePhase.IDLE, state.phase)
        assertFalse(state.isCurrent(token))
        assertFalse(state.beginRecognizing(token))
        assertFalse(state.finish(token))
        assertFalse(state.cancel())
    }

    @Test
    fun staleRoundCannotFinishANewerRound() {
        val state = VoiceInputState()
        val old = state.beginRecording()!!
        state.cancel()
        val fresh = state.beginRecording()!!
        assertTrue(fresh != old)
        assertFalse(state.finish(old))
        assertEquals(VoicePhase.RECORDING, state.phase)
        assertTrue(state.beginRecognizing(fresh))
        assertTrue(state.finish(fresh))
    }

    @Test
    fun recognizingRoundCanBeCancelledToo() {
        val state = VoiceInputState()
        val token = state.beginRecording()!!
        state.beginRecognizing(token)
        assertTrue(state.cancel())
        assertFalse(state.finish(token))
        assertEquals(VoicePhase.IDLE, state.phase)
    }
}