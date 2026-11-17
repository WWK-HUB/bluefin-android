package com.wwkhub.bluefin.voice.tts

/**
 * The one offline text-to-speech engine the app can install: sherpa-onnx
 * ZipVoice (distill, int8, zh-en) plus its 24 kHz vocoder.
 *
 * Nothing is bundled in the APK. The user confirms a one-time download into
 * app-private storage, and synthesis then runs fully on-device: reply text
 * never leaves the phone.
 *
 * Upstream only publishes the ZipVoice weights as a `.tar.bz2`, and Android has
 * no bzip2 decoder, so the app pulls the already-unpacked files from this
 * project's own `tts-models` release. Both URLs point at the same five files;
 * gh-proxy is tried first because direct GitHub is blocked on mainland
 * networks.
 *
 * `lexicon.txt` is mandatory: without it Chinese text falls back to per-
 * character guessing and the same sentence takes 3.4x longer to say.
 */
internal object OfflineTtsSpec {
    const val ID = "zipvoice-distill-int8-zh-en-emilia"

    const val TOKENS_FILE = "tokens.txt"
    const val ENCODER_FILE = "encoder.int8.onnx"
    const val DECODER_FILE = "decoder.int8.onnx"
    const val LEXICON_FILE = "lexicon.txt"
    const val VOCODER_FILE = "vocos_24khz.onnx"

    const val TOKENS_BYTES = 2_570L
    const val ENCODER_BYTES = 5_570_211L
    const val DECODER_BYTES = 124_657_100L
    const val LEXICON_BYTES = 1_727_147L
    const val VOCODER_BYTES = 54_157_409L

    const val TOTAL_BYTES = TOKENS_BYTES + ENCODER_BYTES + DECODER_BYTES + LEXICON_BYTES + VOCODER_BYTES

    /** Int8 decoder is ~119 MB; anything far smaller is a truncated download. */
    const val MIN_DECODER_BYTES = 100L * 1024 * 1024
    const val MIN_ENCODER_BYTES = 4L * 1024 * 1024
    const val MIN_LEXICON_BYTES = 1L * 1024 * 1024
    const val MIN_VOCODER_BYTES = 40L * 1024 * 1024
    const val MIN_TOKENS_BYTES = 1L * 1024

    /** Shown in the consent dialog; rounded up from TOTAL_BYTES. */
    const val DOWNLOAD_SIZE_HINT_MB = 178

    val FILES = listOf(TOKENS_FILE, ENCODER_FILE, DECODER_FILE, LEXICON_FILE, VOCODER_FILE)

    private const val RELEASE_BASE =
        "https://github.com/WWK-HUB/bluefin-android/releases/download/tts-models"

    /** gh-proxy first for mainland networks; direct GitHub as the fallback. */
    fun urlsFor(file: String): List<String> = listOf(
        "https://gh-proxy.com/$RELEASE_BASE/$file",
        "$RELEASE_BASE/$file",
    )

    fun expectedBytes(file: String): Long = when (file) {
        TOKENS_FILE -> TOKENS_BYTES
        ENCODER_FILE -> ENCODER_BYTES
        DECODER_FILE -> DECODER_BYTES
        LEXICON_FILE -> LEXICON_BYTES
        VOCODER_FILE -> VOCODER_BYTES
        else -> 0L
    }

    fun minBytesFor(file: String): Long = when (file) {
        TOKENS_FILE -> MIN_TOKENS_BYTES
        ENCODER_FILE -> MIN_ENCODER_BYTES
        DECODER_FILE -> MIN_DECODER_BYTES
        LEXICON_FILE -> MIN_LEXICON_BYTES
        VOCODER_FILE -> MIN_VOCODER_BYTES
        else -> 0L
    }

    /** Every file must be present and past its minimum size. */
    fun isComplete(sizes: Map<String, Long>): Boolean =
        FILES.all { (sizes[it] ?: 0L) >= minBytesFor(it) }
}
