package com.wwkhub.bluefin.voice.tts

/**
 * Reads a 16-bit PCM WAV into the float samples ZipVoice wants.
 *
 * Reference clips are authored outside the app, so the reader has to tolerate
 * what real encoders emit: an optional `LIST`/`fact` chunk before `data`, either
 * channel count, and any sample rate. Stereo is averaged down to mono and the
 * caller is told the file's own rate so sherpa can resample if it differs from
 * the model's 24 kHz.
 */
internal object WavReader {
    class Pcm(val samples: FloatArray, val sampleRate: Int)

    fun read(bytes: ByteArray, fallbackSampleRate: Int): FloatArray? {
        val pcm = parse(bytes) ?: return null
        if (pcm.sampleRate == fallbackSampleRate) return pcm.samples
        return resample(pcm.samples, pcm.sampleRate, fallbackSampleRate)
    }

    fun parse(bytes: ByteArray): Pcm? {
        if (bytes.size < 44) return null
        if (ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WAVE") return null
        var offset = 12
        var channels = 1
        var sampleRate = 0
        var bits = 16
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset)
            val size = leInt(bytes, offset + 4)
            if (size < 0) return null
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return null
                    channels = leShort(bytes, body + 2).coerceAtLeast(1)
                    sampleRate = leInt(bytes, body + 4)
                    bits = leShort(bytes, body + 14)
                    if (bits != 16) return null
                }
                "data" -> {
                    if (sampleRate <= 0) return null
                    val end = minOf(body + size, bytes.size)
                    val frames = (end - body) / (2 * channels)
                    if (frames <= 0) return null
                    val samples = FloatArray(frames)
                    var cursor = body
                    for (index in 0 until frames) {
                        var sum = 0
                        for (channel in 0 until channels) {
                            sum += leShort(bytes, cursor + channel * 2)
                        }
                        cursor += channels * 2
                        samples[index] = (sum.toFloat() / channels) / 32768f
                    }
                    return Pcm(samples, sampleRate)
                }
            }
            offset = body + size + (size and 1)
        }
        return null
    }

    /** Linear resample; matches what the offline verification used. */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || samples.isEmpty()) return samples
        val ratio = from.toDouble() / to
        val length = (samples.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(length)
        for (index in 0 until length) {
            val source = index * ratio
            val left = source.toInt().coerceIn(0, samples.lastIndex)
            val right = (left + 1).coerceAtMost(samples.lastIndex)
            val fraction = (source - left).toFloat()
            out[index] = samples[left] * (1f - fraction) + samples[right] * fraction
        }
        return out
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun leShort(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        val low = bytes[offset].toInt() and 0xFF
        val high = (bytes[offset + 1].toInt() and 0xFF) shl 8
        val value = low or high
        return if (value >= 0x8000) value - 0x10000 else value
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
