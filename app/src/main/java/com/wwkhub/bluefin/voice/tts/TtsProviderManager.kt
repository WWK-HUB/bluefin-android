package com.wwkhub.bluefin.voice.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.wwkhub.bluefin.BluefinStore
import com.wwkhub.bluefin.VoicePack
import com.wwkhub.bluefin.VoicePackRepository
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The single speech orchestrator: cloud first, ZipVoice as an on-demand
 * fallback.
 *
 * States: CLOUD_ACTIVE (default) and LOCAL_FALLBACK. Temporary cloud trouble
 * retries with backoff and never prompts a download; confirmed persistent
 * trouble switches to an installed+verified local engine silently, or asks
 * the user once (24h cooldown after a decline). Recovery is probed at most
 * every five minutes while fallen back, and the switch back happens at the
 * next reply boundary — never mid-playback. No path ever calls a paid model:
 * the worker hardcodes s2.1-pro-free and this class has no vendor access.
 */
internal class TtsProviderManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cloud = CloudTtsSpeaker(appContext)
    private val local = TtsSpeaker(appContext)
    private val client = CloudTtsClient(appContext)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val lock = Any()

    @Volatile
    private var state = loadState()

    @Volatile
    private var persistentStreak = 0

    @Volatile
    private var lastProbeMs = 0L

    @Volatile
    private var probing = false

    fun currentState(): EngineState = state

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun isSpeaking(): Boolean = cloud.isSpeaking() || local.isSpeaking()

    fun stop() {
        cloud.stop()
        local.stop()
    }

    fun speak(pack: VoicePack, text: String, emotion: TtsEmotion) {
        if (state == EngineState.LOCAL_FALLBACK) {
            if (LocalEngineVerifier.isReady(appContext)) {
                local.speak(pack, text)
                maybeProbe()
                return
            }
            // The local engine was deleted while fallen back: return to cloud.
            setState(EngineState.CLOUD_ACTIVE)
        }
        speakCloud(pack, text, emotion)
    }

    /** User-initiated "try cloud again" from the offline settings. */
    fun retryCloudNow() {
        persistentStreak = 0
        setState(EngineState.CLOUD_ACTIVE)
        toast("已切回云端语音，将在下一条回复生效。")
    }

    /** Called by the download flow once installation AND verification passed. */
    fun notifyLocalReady() {
        persistentStreak = 0
        setState(EngineState.LOCAL_FALLBACK)
        toast("本地备用语音已就绪，云端恢复后会自动切回。")
    }

    /** Called after the user deletes the local model. */
    fun notifyLocalDeleted() {
        LocalEngineVerifier.invalidate(appContext)
        if (state == EngineState.LOCAL_FALLBACK) setState(EngineState.CLOUD_ACTIVE)
    }

    fun onSpeechDisabled() {
        stop()
    }

    private fun speakCloud(pack: VoicePack, text: String, emotion: TtsEmotion) {
        if (!CloudTtsConfig.isConfigured(appContext)) {
            toast("云端语音服务地址未配置，请在朗读设置中填写。")
            return
        }
        val speed = BluefinStore.readSpeechSpeed(prefs())
        cloud.speak(pack, text, emotion, speed) { failure ->
            onCloudFailure(pack, text, emotion, failure)
        }
    }

    private fun onCloudFailure(pack: VoicePack, text: String, emotion: TtsEmotion, failure: TtsFailure) {
        if (!TtsProviderPolicy.isRetryable(failure.category)) {
            // Persistent: a bad voice binding may succeed after re-preparation.
            if (failure.category == TtsCategory.INVALID_VOICE) {
                CloudVoiceManager.invalidate(appContext, pack.id)
            }
            persistentStreak++
        }
        val decision = TtsProviderPolicy.onCloudFailure(
            category = failure.category,
            localReady = LocalEngineVerifier.isReady(appContext),
            streak = persistentStreak,
            declineCooldownActive = declineCooldownActive(),
        )
        when (decision) {
            FallbackDecision.KeepCloud -> toast(failure.message)
            FallbackDecision.SwitchLocal -> {
                persistentStreak = 0
                setState(EngineState.LOCAL_FALLBACK)
                toast("云端语音暂不可用，已切换到本地备用音色。")
                local.speak(pack, text)
            }
            FallbackDecision.PromptDownload -> {
                toast(failure.message)
                // A dedicated translucent activity keeps the consent visible
                // over whichever screen is in front (main, chat or overlay).
                if (!com.wwkhub.bluefin.FallbackSetupActivity.showing) {
                    com.wwkhub.bluefin.FallbackSetupActivity.launch(appContext)
                }
            }
            FallbackDecision.FailText -> toast(failure.message)
        }
    }

    private fun maybeProbe() {
        val now = System.currentTimeMillis()
        if (!TtsProviderPolicy.shouldProbe(state, lastProbeMs, now) || probing) return
        if (!CloudTtsConfig.isConfigured(appContext)) return
        lastProbeMs = now
        probing = true
        Thread(
            {
                try {
                    val ok = CloudIdentity.withToken(appContext, client) { client.probe(it) }
                    if (ok && state == EngineState.LOCAL_FALLBACK) {
                        // Switch at the next reply boundary; current playback
                        // (local) is never interrupted.
                        persistentStreak = 0
                        setState(EngineState.CLOUD_ACTIVE)
                        main.post { toast("云端语音已恢复，下一条回复将使用云端音色。") }
                    }
                } catch (failure: Exception) {
                    Log.d("BluefinCloudTts", "probe failed type=${failure.javaClass.simpleName}")
                } finally {
                    probing = false
                }
            },
            "bluefin-tts-probe",
        ).apply { isDaemon = true }.start()
    }

    private fun declineCooldownActive(): Boolean =
        System.currentTimeMillis() < prefs().getLong(BluefinStore.KEY_FALLBACK_DECLINED_UNTIL, 0L)

    private fun markDeclined() {
        prefs().edit()
            .putLong(BluefinStore.KEY_FALLBACK_DECLINED_UNTIL, System.currentTimeMillis() + TtsProviderPolicy.DECLINE_COOLDOWN_MS)
            .apply()
    }

    /** The settings flow calls this when the user explicitly declines. */
    fun userDeclinedFallback() {
        markDeclined()
    }

    private fun setState(next: EngineState) {
        if (state == next) return
        state = next
        prefs().edit().putString(BluefinStore.KEY_TTS_ENGINE_STATE, next.name).apply()
        main.post { listeners.toList().forEach { runCatching { it() } } }
    }

    private fun loadState(): EngineState {
        val stored = prefs().getString(BluefinStore.KEY_TTS_ENGINE_STATE, null)
        val parsed = runCatching { EngineState.valueOf(stored ?: "") }.getOrNull()
        return if (parsed == EngineState.LOCAL_FALLBACK && LocalEngineVerifier.isReady(appContext)) {
            EngineState.LOCAL_FALLBACK
        } else {
            EngineState.CLOUD_ACTIVE
        }
    }

    private fun prefs() = appContext.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)

    private fun toast(message: String) {
        main.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        @Volatile
        private var instance: TtsProviderManager? = null

        fun shared(context: Context): TtsProviderManager {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: TtsProviderManager(context).also { instance = it }
            }
        }

        fun peek(): TtsProviderManager? = instance
    }
}