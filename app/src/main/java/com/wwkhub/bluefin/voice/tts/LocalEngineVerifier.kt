package com.wwkhub.bluefin.voice.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wwkhub.bluefin.BluefinStore
import com.wwkhub.bluefin.VoicePackFormat
import com.wwkhub.bluefin.VoicePackRepository
import java.io.File

/**
 * Post-install inference check for the ZipVoice fallback. A model that merely
 * exists on disk is not a usable engine: the native build must succeed and a
 * real sentence must produce playable frames with the bundled reference clip.
 * Only then is the verified flag set and the fallback allowed to engage.
 */
internal object LocalEngineVerifier {
    private const val TAG = "BluefinTts"
    private const val MIN_SAMPLES = 1_600
    private val handler = Handler(Looper.getMainLooper())

    fun isReady(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
        return TtsModelManager.isInstalled(context) && BluefinStore.readZipVoiceVerified(prefs)
    }

    /** Runs the check on a worker thread; [onDone] gets true/false on main. */
    fun verify(context: Context, onDone: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        Thread(
            {
                val ok = runCatching { runCheck(appContext) }
                    .onFailure { Log.w(TAG, "engine verify failed type=${it.javaClass.simpleName}") }
                    .getOrDefault(false)
                appContext.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(BluefinStore.KEY_ZIPVOICE_VERIFIED, ok).apply()
                handler.post { onDone(ok) }
            },
            "bluefin-tts-verify",
        ).apply { isDaemon = true }.start()
    }

    fun invalidate(context: Context) {
        context.applicationContext.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(BluefinStore.KEY_ZIPVOICE_VERIFIED, false).apply()
    }

    private fun runCheck(context: Context): Boolean {
        val engine = TtsEngineHolder.get(context) ?: return false
        val pack = VoicePackRepository.listBuiltIn(context).firstOrNull() ?: return false
        val bytes = context.assets
            .open("voice-packs/${pack.id}/${pack.reference}")
            .use { it.readBytes() }
        val reference = WavReader.read(bytes, pack.sampleRate)
        val config = com.k2fsa.sherpa.onnx.GenerationConfig(
            0.2f, 1.0f, 0, reference, pack.sampleRate, pack.referenceText, pack.numSteps,
        )
        val audio = synchronized(TtsEngineHolder.lock) {
            engine.generateWithConfig("你好，我是小深。", config)
        }
        return audio.samples.size >= MIN_SAMPLES
    }
}