package com.wwkhub.bluefin.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wwkhub.bluefin.VoicePackFormat
import com.wwkhub.bluefin.voice.tts.WavReader

/**
 * Records a short reference clip for a new timbre.
 *
 * Distinct from `voice.VoiceRecorder` on purpose: that one feeds the offline
 * recogniser, is pinned to 16 kHz for 60 s, and documents that it never writes
 * a file. This one produces the 10 s, 24 kHz clip a voice pack needs, so
 * changing the recogniser's contract is not on the table.
 *
 * The caller must hold RECORD_AUDIO before [start].
 */
internal class ReferenceRecorder(
    private val onLevel: (Float) -> Unit,
    private val onDone: (samples: FloatArray?, sampleRate: Int) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @Volatile private var running = false
    @Volatile private var cancelled = false

    /**
     * Starts capturing. Returns the rate the device actually opened, or null
     * when no candidate configuration initialised.
     */
    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO
    fun start(): Int? {
        if (running) return null
        var chosenRate = 0
        var chosenRecord: AudioRecord? = null
        for (rate in CANDIDATE_RATES) {
            val min = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
            if (min <= 0) continue
            val buffer = (min * 2).coerceAtLeast(rate / 2)
            val candidate = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, CHANNEL, ENCODING, buffer)
            }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                chosenRate = rate
                chosenRecord = candidate
                break
            }
            runCatching { candidate.release() }
        }
        val recorder = chosenRecord ?: return null
        val rate = chosenRate
        record = recorder
        running = true
        cancelled = false
        val capacity = (rate * MAX_SECONDS).toInt()
        thread = Thread({
            capture(recorder, rate, capacity)
        }, "bluefin-reference-record").also { it.start() }
        return rate
    }

    /** Stops capture and delivers whatever was collected. */
    fun stop() {
        if (!running) return
        running = false
    }

    /** Stops without delivering a clip. */
    fun cancel() {
        cancelled = true
        running = false
    }

    private fun capture(recorder: AudioRecord, rate: Int, capacity: Int) {
        val collected = FloatArray(capacity)
        var filled = 0
        val chunk = ShortArray(1024)
        val startMs = android.os.SystemClock.elapsedRealtime()
        try {
            recorder.startRecording()
            while (running && filled < capacity && android.os.SystemClock.elapsedRealtime() - startMs < MAX_SECONDS * 1000) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read < 0) break
                if (read == 0) continue
                var peak = 0f
                val limit = minOf(read, capacity - filled)
                for (index in 0 until limit) {
                    val value = chunk[index] / 32768f
                    collected[filled + index] = value
                    val magnitude = if (value < 0f) -value else value
                    if (magnitude > peak) peak = magnitude
                }
                filled += limit
                val level = peak.coerceIn(0f, 1f)
                main.post { onLevel(level) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "capture failed", error)
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            record = null
            val wasCancelled = cancelled
            val samples = if (wasCancelled) {
                null
            } else {
                val exact = if (filled == collected.size) collected else collected.copyOf(filled)
                if (rate == AudioTranscoder.TARGET_SAMPLE_RATE) {
                    exact
                } else {
                    WavReader.resample(exact, rate, AudioTranscoder.TARGET_SAMPLE_RATE)
                }
            }
            main.post {
                running = false
                onDone(samples, AudioTranscoder.TARGET_SAMPLE_RATE)
            }
        }
    }

    companion object {
        private const val TAG = "ReferenceRecorder"
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 24 kHz is the pack rate, so the first candidate needs no resample. */
        private val CANDIDATE_RATES = intArrayOf(
            AudioTranscoder.TARGET_SAMPLE_RATE,
            16_000,
            44_100,
            48_000,
        )

        /** Same ceiling the pack format enforces, so no clip can overshoot it. */
        val MAX_SECONDS: Int = VoicePackFormat.MAX_REFERENCE_SECONDS.toInt()
    }
}
