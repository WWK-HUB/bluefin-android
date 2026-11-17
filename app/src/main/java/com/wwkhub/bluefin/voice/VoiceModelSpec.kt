package com.wwkhub.bluefin.voice

/**
 * The one offline ASR model the app can install. Pure metadata and checks so
 * JVM tests cover the download sources, layout and completeness rules.
 *
 * The model is never bundled in the APK: the user explicitly confirms a
 * one-time download into app-private storage, and recognition runs fully
 * on-device. No audio ever leaves the phone.
 *
 * Files are fetched individually (no archive): the ModelScope asr-models
 * mirror does not carry this model, while hf-mirror serves the pinned
 * Hugging Face revision file-by-file and is reachable from mainland networks.
 */
internal object VoiceModelSpec {
    const val ID = "paraformer-zh-small-2024-03-09"
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"

    /** Pinned upstream sizes; used for progress totals. */
    const val MODEL_BYTES = 81_828_675L
    const val TOKENS_BYTES = 75_352L
    const val TOTAL_BYTES = MODEL_BYTES + TOKENS_BYTES

    /** int8 weights are ~78 MB; anything far smaller is a truncated download. */
    const val MIN_MODEL_BYTES = 60L * 1024 * 1024

    /** Token table is ~74 KB; a few hundred bytes means a bad download. */
    const val MIN_TOKENS_BYTES = 10L * 1024

    /** Approximate download size shown in the consent dialog. */
    const val DOWNLOAD_SIZE_HINT_MB = 78

    val FILES = listOf(MODEL_FILE, TOKENS_FILE)

    /** hf-mirror first for mainland networks; huggingface.co elsewhere. */
    fun urlsFor(file: String): List<String> = listOf(
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-$ID/resolve/main/$file",
        "https://huggingface.co/csukuangfj/sherpa-onnx-$ID/resolve/main/$file",
    )

    fun expectedBytes(file: String): Long = when (file) {
        MODEL_FILE -> MODEL_BYTES
        TOKENS_FILE -> TOKENS_BYTES
        else -> 0L
    }

    fun isComplete(modelBytes: Long, tokensBytes: Long): Boolean =
        modelBytes >= MIN_MODEL_BYTES && tokensBytes >= MIN_TOKENS_BYTES
}