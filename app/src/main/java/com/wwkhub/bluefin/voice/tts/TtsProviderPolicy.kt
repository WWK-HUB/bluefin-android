package com.wwkhub.bluefin.voice.tts

/**
 * Pure switching policy for the cloud-first / local-fallback provider.
 * Kept Android-free so the whole decision matrix runs as JVM tests.
 */
internal enum class EngineState {
    CLOUD_ACTIVE,
    LOCAL_FALLBACK,
}

internal sealed class FallbackDecision {
    /** Stay on cloud; temporary trouble must never trigger a download prompt. */
    object KeepCloud : FallbackDecision()

    /** ZipVoice is installed and verified: switch without asking. */
    object SwitchLocal : FallbackDecision()

    /** Persistent cloud failure, no local engine: ask once (consent required). */
    object PromptDownload : FallbackDecision()

    /** Speech fails, text stays, and the user already declined recently. */
    object FailText : FallbackDecision()
}

internal object TtsProviderPolicy {
    /** Consecutive persistent reply failures before offering the fallback. */
    const val PERSISTENT_STREAK_THRESHOLD = 2

    /** Background recovery probe cadence while in LOCAL_FALLBACK. */
    const val PROBE_INTERVAL_MS = 5 * 60 * 1000L

    /** After a declined download prompt, stay quiet for this long. */
    const val DECLINE_COOLDOWN_MS = 24 * 60 * 60 * 1000L

    /** Bounded retries for temporary categories only. */
    const val MAX_RETRIES = 2

    fun isRetryable(category: TtsCategory): Boolean =
        category == TtsCategory.TEMPORARY_NETWORK_ERROR ||
            category == TtsCategory.RATE_LIMITED ||
            category == TtsCategory.SERVICE_UNAVAILABLE

    fun retryDelayMs(attempt: Int, retryAfterSeconds: Int): Long =
        if (retryAfterSeconds > 0) retryAfterSeconds * 1000L
        else when (attempt) {
            0 -> 500L
            1 -> 1500L
            else -> 4000L
        }

    /**
     * What to do after a reply's cloud synthesis failed. [streak] counts
     * consecutive replies with persistent failures; definitive categories
     * (quota/model) bypass the streak because they are confirmed states.
     */
    fun onCloudFailure(
        category: TtsCategory,
        localReady: Boolean,
        streak: Int,
        declineCooldownActive: Boolean,
    ): FallbackDecision {
        if (!isPersistentCategory(category)) return FallbackDecision.KeepCloud
        val confirmed = category == TtsCategory.QUOTA_EXHAUSTED || category == TtsCategory.MODEL_UNAVAILABLE
        if (!confirmed && streak < PERSISTENT_STREAK_THRESHOLD) return FallbackDecision.KeepCloud
        return when {
            localReady -> FallbackDecision.SwitchLocal
            declineCooldownActive -> FallbackDecision.FailText
            else -> FallbackDecision.PromptDownload
        }
    }

    private fun isPersistentCategory(category: TtsCategory): Boolean =
        category == TtsCategory.QUOTA_EXHAUSTED ||
            category == TtsCategory.MODEL_UNAVAILABLE ||
            category == TtsCategory.AUTH_ERROR ||
            category == TtsCategory.INVALID_VOICE

    /** Probe only while fallen back, and never more often than the interval. */
    fun shouldProbe(state: EngineState, lastProbeMs: Long, nowMs: Long): Boolean =
        state == EngineState.LOCAL_FALLBACK && nowMs - lastProbeMs >= PROBE_INTERVAL_MS

    /** Recovery switches happen at the next reply boundary, never mid-playback. */
    fun onProbeSuccess(state: EngineState): EngineState =
        if (state == EngineState.LOCAL_FALLBACK) EngineState.CLOUD_ACTIVE else state
}