package com.wwkhub.bluefin.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSendPolicyTest {
    private val neverLook: (String) -> Boolean = { false }
    private val alwaysLook: (String) -> Boolean = { true }

    @Test
    fun blankTextNeverSends() {
        assertEquals(
            VoiceSendDecision.EMPTY,
            VoiceSendPolicy.decide("   ", configured = true, busy = false, isLookCommand = neverLook),
        )
        assertEquals(
            VoiceSendDecision.EMPTY,
            VoiceSendPolicy.decide("", configured = false, busy = true, isLookCommand = alwaysLook),
        )
    }

    @Test
    fun lookCommandWinsBeforeConfigurationAndBusy() {
        // Matches the typed path: the look intercept runs before session.send.
        assertEquals(
            VoiceSendDecision.LOOK_COMMAND,
            VoiceSendPolicy.decide("看看我的屏幕", configured = false, busy = true, isLookCommand = alwaysLook),
        )
    }

    @Test
    fun unconfiguredAndBusyBlockSend() {
        assertEquals(
            VoiceSendDecision.NOT_CONFIGURED,
            VoiceSendPolicy.decide("你好", configured = false, busy = false, isLookCommand = neverLook),
        )
        assertEquals(
            VoiceSendDecision.BUSY,
            VoiceSendPolicy.decide("你好", configured = true, busy = true, isLookCommand = neverLook),
        )
    }

    @Test
    fun readyTextSends() {
        assertEquals(
            VoiceSendDecision.SEND,
            VoiceSendPolicy.decide("你好呀", configured = true, busy = false, isLookCommand = neverLook),
        )
    }

    @Test
    fun realLookCommandRegexIsRecognized() {
        // The production intercept is ScreenAwareness.isLookCommand; the policy
        // must route it, not send it as chat text.
        val look = com.wwkhub.bluefin.ScreenAwareness::isLookCommand
        assertEquals(
            VoiceSendDecision.LOOK_COMMAND,
            VoiceSendPolicy.decide("蓝鳍，看看我的屏幕", configured = true, busy = false, isLookCommand = look),
        )
        assertEquals(
            VoiceSendDecision.SEND,
            VoiceSendPolicy.decide("今天天气怎么样", configured = true, busy = false, isLookCommand = look),
        )
    }
}