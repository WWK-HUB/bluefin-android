package com.wwkhub.bluefin.voice.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File

/**
 * Owns the single native [OfflineTts] instance.
 *
 * Building the engine costs about 2.2 s on a mid-range phone, so the instance is
 * kept alive across replies and only released when the feature is switched off
 * or the process goes away. Concurrent synthesis is not safe on one instance,
 * so callers must hold [lock] around [synthesize].
 *
 * `null` is passed as the AssetManager on purpose: every model file is an
 * absolute path in app-private storage, and a non-null manager would make
 * sherpa look inside the APK instead.
 */
internal object TtsEngineHolder {
    private const val TAG = "BluefinTts"

    /** Serialises generate/release; the native object is not thread safe. */
    val lock = Any()

    @Volatile
    private var engine: OfflineTts? = null

    @Volatile
    private var broken = false

    /** True once construction has failed, so the UI can offer a re-download. */
    fun isBroken(): Boolean = broken

    fun reset() {
        synchronized(lock) {
            runCatching { engine?.release() }
            engine = null
            broken = false
        }
    }

    /**
     * Returns the engine, building it on first use. Returns null when the model
     * files are missing or the native constructor failed; callers treat that as
     * "speech unavailable" rather than an exception.
     */
    fun get(context: Context): OfflineTts? = synchronized(lock) {
        engine?.let { return it }
        if (broken) return null
        if (!TtsModelManager.isInstalled(context)) return null
        return try {
            val created = build(context)
            engine = created
            created
        } catch (failure: Throwable) {
            broken = true
            // Only the type goes to logcat: model paths are private.
            Log.e(TAG, "engine build failed type=${failure.javaClass.simpleName}")
            null
        }
    }

    private fun build(context: Context): OfflineTts {
        val dir = TtsModelManager.modelDir(context)
        // Positional arguments on purpose: these config classes come from the
        // sherpa AAR and their Kotlin parameter names are not part of any API we
        // control.
        val zipvoice = OfflineTtsZipVoiceModelConfig(
            File(dir, OfflineTtsSpec.TOKENS_FILE).absolutePath,
            File(dir, OfflineTtsSpec.ENCODER_FILE).absolutePath,
            File(dir, OfflineTtsSpec.DECODER_FILE).absolutePath,
            File(dir, OfflineTtsSpec.VOCODER_FILE).absolutePath,
            "", // data_dir:废弃 since sherpa 2.0, lexicon replaces it
            File(dir, OfflineTtsSpec.LEXICON_FILE).absolutePath,
            0.1f, // feat_scale
            0.5f, // t_shift
            0.1f, // target_rms
            1.0f, // guidance_scale
        )
        val modelConfig = OfflineTtsModelConfig(
            OfflineTtsVitsModelConfig(),
            OfflineTtsMatchaModelConfig(),
            OfflineTtsKokoroModelConfig(),
            zipvoice,
            OfflineTtsKittenModelConfig(),
            OfflineTtsPocketModelConfig(),
            OfflineTtsSupertonicModelConfig(),
            2, // num_threads
            false, // debug
            "cpu",
        )
        val config = OfflineTtsConfig(modelConfig, "", "", 1, 0.2f)
        return OfflineTts(null, config)
    }
}
