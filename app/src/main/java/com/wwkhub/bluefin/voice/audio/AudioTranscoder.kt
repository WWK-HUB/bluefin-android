package com.wwkhub.bluefin.voice.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.wwkhub.bluefin.VoicePackFormat
import com.wwkhub.bluefin.voice.tts.WavReader
import java.io.File
import java.io.IOException

/** Why a picked file could not become a reference clip. */
internal enum class AudioImportError {
    NOT_AUDIO,
    UNSUPPORTED_CODEC,
    TOO_LARGE,
    TOO_SHORT,
    DECODE_FAILED,
}

internal sealed class TranscodeResult {
    /** [truncated] is true when the source was longer than the 10 s ceiling. */
    class Ok(val wav: File, val seconds: Double, val truncated: Boolean) : TranscodeResult()
    class Failed(val reason: AudioImportError) : TranscodeResult()
}

/**
 * Turns any audio the platform can decode into the 24 kHz mono 16-bit WAV a
 * voice pack must contain.
 *
 * 24 kHz is not arbitrary: it is `pack.sampleRate`, which
 * [com.wwkhub.bluefin.voice.tts.TtsSpeaker] hands to sherpa as the model input
 * rate, and it is what the bundled timbres use. It also sits inside the 8 k to
 * 48 k window the manifest validator accepts.
 *
 * Decoding stops as soon as the reference ceiling is reached, so a user picking
 * a five-minute song does not allocate five minutes of PCM to then throw most
 * of it away.
 */
internal object AudioTranscoder {

    private const val TAG = "AudioTranscoder"

    /** Target rate written into every created pack. */
    const val TARGET_SAMPLE_RATE = 24_000

    /** Shortest acceptable clip; below this the clone has nothing to learn. */
    const val MIN_REFERENCE_SECONDS = 1.0

    /** Ceiling shared with the zip importer so both paths agree. */
    const val MAX_REFERENCE_SECONDS = VoicePackFormat.MAX_REFERENCE_SECONDS

    /** A reference clip is 10 s; anything this big is the wrong file. */
    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024

    private const val DECODE_TIMEOUT_US = 10_000L

    fun toReferenceWav(context: Context, uri: Uri, target: File): TranscodeResult {
        val staging = File(context.cacheDir, "voice-src-${System.nanoTime()}")
        staging.mkdirs()
        val source = File(staging, "source.bin")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (!copySource(context, uri, source)) {
                return TranscodeResult.Failed(AudioImportError.TOO_LARGE)
            }
            extractor.setDataSource(source.absolutePath)
            val track = findAudioTrack(extractor)
                ?: return TranscodeResult.Failed(AudioImportError.NOT_AUDIO)
            extractor.selectTrack(track)

            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (error: IOException) {
                Log.w(TAG, "no decoder for $mime", error)
                return TranscodeResult.Failed(AudioImportError.UNSUPPORTED_CODEC)
            }
            codec.configure(format, null, null, 0)
            codec.start()

            val decoded = decodeBounded(extractor, codec)
                ?: return TranscodeResult.Failed(AudioImportError.DECODE_FAILED)

            val seconds = decoded.samples.size.toDouble() / decoded.sampleRate
            if (seconds < MIN_REFERENCE_SECONDS) {
                return TranscodeResult.Failed(AudioImportError.TOO_SHORT)
            }

            val targetPcm = if (decoded.sampleRate == TARGET_SAMPLE_RATE) {
                decoded.samples
            } else {
                WavReader.resample(decoded.samples, decoded.sampleRate, TARGET_SAMPLE_RATE)
            }
            WavWriter.write(target, targetPcm, TARGET_SAMPLE_RATE)
            return TranscodeResult.Ok(
                wav = target,
                seconds = targetPcm.size.toDouble() / TARGET_SAMPLE_RATE,
                truncated = decoded.truncated,
            )
        } catch (error: Exception) {
            Log.w(TAG, "transcode failed", error)
            return TranscodeResult.Failed(AudioImportError.DECODE_FAILED)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            staging.deleteRecursively()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        return null
    }

    private class Decoded(
        val samples: FloatArray,
        val sampleRate: Int,
        val truncated: Boolean,
    )

    /**
     * Reads until the reference ceiling or end of stream. The ceiling is
     * applied on source samples, not resampled ones, so a 48 kHz source stops
     * at 48k × 10 samples and keeps its full quality for the resample step.
     */
    private fun decodeBounded(extractor: MediaExtractor, codec: MediaCodec): Decoded? {
        var sampleRate = TARGET_SAMPLE_RATE
        var channels = 1
        var pcmFloat = false
        var collected = FloatBucket(initial = sampleRate * 2)
        var reachedCeiling = false

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var guard = 0

        while (!outputDone) {
            if (guard++ > MAX_DEQUEUE_ROUNDS) return null
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(DECODE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = codec.outputFormat
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    pcmFloat = output.pcmEncoding() == ANDROID_PCM_FLOAT
                }
                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        collected.add(
                            if (pcmFloat) {
                                readFloat(buffer, info.size, channels)
                            } else {
                                readPcm16(buffer, info.size, channels)
                            },
                        )
                        val ceiling = (sampleRate * MAX_REFERENCE_SECONDS).toInt()
                        if (collected.size >= ceiling) {
                            reachedCeiling = true
                            codec.releaseOutputBuffer(outputIndex, false)
                            outputDone = true
                            continue
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        val ceiling = (sampleRate * MAX_REFERENCE_SECONDS).toInt().coerceAtLeast(1)
        val samples = collected.toArray(ceiling)
        if (samples.isEmpty()) return null
        return Decoded(samples, sampleRate, reachedCeiling || samples.size >= ceiling)
    }

    /**
     * Growable float sink. Appending into a fresh array each block would make a
     * 10 s clip quadratic in allocations; doubling keeps it linear.
     */
    private class FloatBucket(initial: Int) {
        private var data = FloatArray(initial.coerceAtLeast(1))
        var size = 0
            private set

        fun add(block: FloatArray) {
            if (block.isEmpty()) return
            ensure(size + block.size)
            block.copyInto(data, size)
            size += block.size
        }

        private fun ensure(capacity: Int) {
            if (capacity <= data.size) return
            var next = data.size
            while (next < capacity) next *= 2
            data = data.copyOf(next)
        }

        fun toArray(limit: Int): FloatArray = data.copyOf(size.coerceAtMost(limit))
    }

    /** Signed 16-bit little endian, downmixed to mono by averaging channels. */
    private fun readPcm16(
        buffer: java.nio.ByteBuffer,
        byteCount: Int,
        channels: Int,
    ): FloatArray {
        val frames = byteCount / (2 * channels)
        val out = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                val low = buffer.get().toInt() and 0xFF
                val high = buffer.get().toInt() and 0xFF
                val value = low or (high shl 8)
                sum += if (value >= 0x8000) value - 0x10000 else value
            }
            out[frame] = (sum.toFloat() / channels) / 32768f
        }
        return out
    }

    /** 32-bit float PCM, which MediaCodec reports as ENCODING_PCM_FLOAT. */
    private fun readFloat(
        buffer: java.nio.ByteBuffer,
        byteCount: Int,
        channels: Int,
    ): FloatArray {
        val frames = byteCount / (4 * channels)
        val out = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += buffer.float
            }
            out[frame] = sum / channels
        }
        return out
    }

    /**
     * Copies through a bounded buffer so a large pick cannot fill the cache
     * before we notice. Returns false when the source exceeds the ceiling.
     */
    private fun copySource(context: Context, uri: Uri, target: File): Boolean {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(uri) ?: return false
        input.use { stream ->
            target.outputStream().buffered().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SOURCE_BYTES) return false
                    sink.write(buffer, 0, read)
                }
            }
        }
        return target.length() > 0
    }

    private const val MAX_DEQUEUE_ROUNDS = 20_000

    private const val ANDROID_PCM_FLOAT = 4 // AudioFormat.ENCODING_PCM_FLOAT

    private fun MediaFormat.pcmEncoding(): Int =
        if (containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            2 // AudioFormat.ENCODING_PCM_16BIT
        }
}
