package com.wwkhub.bluefin.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * Lazy holder for the sherpa-onnx offline recognizer. Construction loads the
 * ~79 MB int8 weights, so it happens on a worker thread at first recognition,
 * never on a window-open path. The host releases it after an idle timeout to
 * give the RAM back; the next round rebuilds it.
 */
internal class VoiceRecognizer(private val modelDir: File) {
    private var recognizer: OfflineRecognizer? = null

    @Synchronized
    fun recognize(samples: FloatArray): String {
        val engine = recognizer ?: build().also { recognizer = it }
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(samples, VoiceRecorder.SAMPLE_RATE)
            engine.decode(stream)
            return engine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
    }

    private fun build(): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = VoiceRecorder.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = File(modelDir, VoiceModelSpec.MODEL_FILE).absolutePath,
                ),
                tokens = File(modelDir, VoiceModelSpec.TOKENS_FILE).absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
        )
        // No AssetManager: model paths are absolute files in app-private storage.
        return OfflineRecognizer(config = config)
    }
}