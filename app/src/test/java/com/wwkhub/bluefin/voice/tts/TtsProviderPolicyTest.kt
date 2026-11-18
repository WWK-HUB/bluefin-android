package com.wwkhub.bluefin.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloud-first / local-fallback decision matrix. Temporary trouble must
 * never trigger a download prompt; confirmed exhaustion must; a declined
 * prompt must stay quiet for the cooldown.
 */
class TtsProviderPolicyTest {

    @Test
    fun temporaryCategoriesNeverPromptOrSwitch() {
        for (category in listOf(
            TtsCategory.TEMPORARY_NETWORK_ERROR,
            TtsCategory.RATE_LIMITED,
            TtsCategory.SERVICE_UNAVAILABLE,
        )) {
            val decision = TtsProviderPolicy.onCloudFailure(category, localReady = false, streak = 99, declineCooldownActive = false)
            assertEquals(FallbackDecision.KeepCloud, decision)
        }
    }

    @Test
    fun persistentBelowThresholdKeepsCloud() {
        val decision = TtsProviderPolicy.onCloudFailure(
            TtsCategory.AUTH_ERROR, localReady = false, streak = 1, declineCooldownActive = false,
        )
        assertEquals(FallbackDecision.KeepCloud, decision)
    }

    @Test
    fun persistentAtThresholdPromptsWhenLocalMissing() {
        val decision = TtsProviderPolicy.onCloudFailure(
            TtsCategory.INVALID_VOICE, localReady = false,
            streak = TtsProviderPolicy.PERSISTENT_STREAK_THRESHOLD, declineCooldownActive = false,
        )
        assertEquals(FallbackDecision.PromptDownload, decision)
    }

    @Test
    fun definitiveQuotaExhaustionPromptsImmediately() {
        val decision = TtsProviderPolicy.onCloudFailure(
            TtsCategory.QUOTA_EXHAUSTED, localReady = false, streak = 1, declineCooldownActive = false,
        )
        assertEquals(FallbackDecision.PromptDownload, decision)
    }

    @Test
    fun definitiveModelUnavailableSwitchesSilentlyWhenLocalReady() {
        val decision = TtsProviderPolicy.onCloudFailure(
            TtsCategory.MODEL_UNAVAILABLE, localReady = true, streak = 1, declineCooldownActive = false,
        )
        assertEquals(FallbackDecision.SwitchLocal, decision)
    }

    @Test
    fun declinedCooldownFailsTextWithoutNagging() {
        val decision = TtsProviderPolicy.onCloudFailure(
            TtsCategory.QUOTA_EXHAUSTED, localReady = false, streak = 5, declineCooldownActive = true,
        )
        assertEquals(FallbackDecision.FailText, decision)
    }

    @Test
    fun retryableSetIsExactlyTheTemporaryThree() {
        assertTrue(TtsProviderPolicy.isRetryable(TtsCategory.TEMPORARY_NETWORK_ERROR))
        assertTrue(TtsProviderPolicy.isRetryable(TtsCategory.RATE_LIMITED))
        assertTrue(TtsProviderPolicy.isRetryable(TtsCategory.SERVICE_UNAVAILABLE))
        assertFalse(TtsProviderPolicy.isRetryable(TtsCategory.QUOTA_EXHAUSTED))
        assertFalse(TtsProviderPolicy.isRetryable(TtsCategory.MODEL_UNAVAILABLE))
        assertFalse(TtsProviderPolicy.isRetryable(TtsCategory.AUTH_ERROR))
        assertFalse(TtsProviderPolicy.isRetryable(TtsCategory.INVALID_VOICE))
        assertFalse(TtsProviderPolicy.isRetryable(TtsCategory.UNKNOWN_ERROR))
    }

    @Test
    fun backoffHonorsRetryAfterAndGrowsOtherwise() {
        assertEquals(3000L, TtsProviderPolicy.retryDelayMs(0, 3))
        assertEquals(500L, TtsProviderPolicy.retryDelayMs(0, 0))
        assertEquals(1500L, TtsProviderPolicy.retryDelayMs(1, 0))
        assertEquals(4000L, TtsProviderPolicy.retryDelayMs(2, 0))
    }

    @Test
    fun probeOnlyWhileFallenBackAndThrottled() {
        val now = 1_000_000L
        assertFalse(TtsProviderPolicy.shouldProbe(EngineState.CLOUD_ACTIVE, 0, now))
        assertTrue(TtsProviderPolicy.shouldProbe(EngineState.LOCAL_FALLBACK, 0, now))
        assertFalse(
            TtsProviderPolicy.shouldProbe(EngineState.LOCAL_FALLBACK, now - 1000, now),
        )
        assertTrue(
            TtsProviderPolicy.shouldProbe(EngineState.LOCAL_FALLBACK, now - TtsProviderPolicy.PROBE_INTERVAL_MS, now),
        )
    }

    @Test
    fun probeSuccessRecoversToCloudAtNextBoundary() {
        assertEquals(EngineState.CLOUD_ACTIVE, TtsProviderPolicy.onProbeSuccess(EngineState.LOCAL_FALLBACK))
        assertEquals(EngineState.CLOUD_ACTIVE, TtsProviderPolicy.onProbeSuccess(EngineState.CLOUD_ACTIVE))
    }
}