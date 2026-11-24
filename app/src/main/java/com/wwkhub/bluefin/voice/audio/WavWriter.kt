package com.wwkhub.bluefin.voice.audio

import java.io.File
import java.io.OutputStream

/**
 * Writes 16-bit PCM WAV, the only shape [com.wwkhub.bluefin.voice.tts.WavReader]
 * and sherpa accept as a reference clip.
 *
 * The app previously only read WAV; both the recorder and the file transcoder
 * need to write one, so the header lives here once. Two hand-written headers is
 * exactly how a `data` length field ends up one byte off.
 *
 * Nothing here touches Android, so the round trip runs as a JVM unit test.
 */
internal object WavWriter {

    private const val HEADER_BYTES = 44
    private const val BITS_PER_SAMPLE = 16

    fun write(
        sink: OutputStream,
        samples: FloatArray,
        sampleRate: Int,
        channels: Int = 1,
    ) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
        val blockAlign = channels * (BITS_PER_SAMPLE / 8)
        val dataBytes = samples.size * channels * (BITS_PER_SAMPLE / 8)
        val byteRate = sampleRate * blockAlign
        val padded = dataBytes + (dataBytes and 1)

        val header = ByteArray(HEADER_BYTES)
        ascii(header, 0, "RIFF")
        leInt(header, 4, 36 + padded)
        ascii(header, 8, "WAVE")
        ascii(header, 12, "fmt ")
        leInt(header, 16, 16)
        leShort(header, 20, 1) // PCM
        leShort(header, 22, channels)
        leInt(header, 24, sampleRate)
        leInt(header, 28, byteRate)
        leShort(header, 32, blockAlign)
        leShort(header, 34, BITS_PER_SAMPLE)
        ascii(header, 36, "data")
        leInt(header, 40, padded)
        sink.write(header)

        val buffer = ByteArray(8 * 1024)
        var cursor = 0
        for (index in samples.indices) {
            val value = quantize(samples[index])
            // The caller hands in mono; every channel gets the same sample so a
            // multi-channel header can never describe a half-filled frame.
            repeat(channels) {
                buffer[cursor++] = value.toByte()
                buffer[cursor++] = (value shr 8).toByte()
                if (cursor == buffer.size) {
                    sink.write(buffer, 0, cursor)
                    cursor = 0
                }
            }
        }
        if (cursor > 0) sink.write(buffer, 0, cursor)

        // RIFF chunks are word aligned; a 16-bit mono clip is always even, but a
        // future odd-width path must not silently produce a truncated file.
        if (padded != dataBytes) sink.write(0)
    }

    fun write(
        file: File,
        samples: FloatArray,
        sampleRate: Int,
        channels: Int = 1,
    ) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { sink -> write(sink, samples, sampleRate, channels) }
    }

    /** Float [-1,1] to signed 16-bit, with the asymmetric +1 end clamped. */
    private fun quantize(sample: Float): Int {
        val clamped = when {
            sample.isNaN() -> 0f
            sample > 1f -> 1f
            sample < -1f -> -1f
            else -> sample
        }
        val scaled = (clamped * 32767f).toInt()
        return scaled.coerceIn(-32768, 32767)
    }

    private fun ascii(target: ByteArray, offset: Int, value: String) {
        for (index in value.indices) target[offset + index] = value[index].code.toByte()
    }

    private fun leShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value shr 8).toByte()
    }

    private fun leInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value shr 8).toByte()
        target[offset + 2] = (value shr 16).toByte()
        target[offset + 3] = (value shr 24).toByte()
    }
}
