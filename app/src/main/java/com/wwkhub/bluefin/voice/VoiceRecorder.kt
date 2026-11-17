package com.wwkhub.bluefin.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * One tap-to-talk capture: 16 kHz mono PCM16 into memory, hard-capped at
 * [MAX_SECONDS]. No audio file is ever written; samples go straight to the
 * offline recognizer. The caller must hold RECORD_AUDIO before [start].
 *
 * Callbacks are delivered on the main thread. [stop] ends the round and
 * delivers the samples; [cancel] ends it and delivers null.
 */
internal class VoiceRecorder(
    private val onLevel: (Float) -> Unit,
    private val onDone: (FloatArray?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var cancelled = false

    val isActive: Boolean get() = running

    @SuppressLint("MissingPermission") // hosts check RECORD_AUDIO before start()
    fun start(): Boolean {
        if (running) return false
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        } catch (_: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        running = true
        cancelled = false
        Thread({ capture(record, minBuffer) }, "bluefin-voice-record")
            .apply { isDaemon = true }
            .start()
        return true
    }

    /** Graceful end: the capture loop exits and delivers what was heard. */
    fun stop() {
        running = false
    }

    fun cancel() {
        cancelled = true
        running = false
    }

    private fun capture(record: AudioRecord, minBuffer: Int) {
        val data = FloatArray(SAMPLE_RATE * MAX_SECONDS)
        val buffer = ShortArray(minBuffer / 2)
        var count = 0
        var levelPostedAt = 0L
        var failed = false
        try {
            record.startRecording()
            while (running && count < data.size) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    failed = read < 0
                    break
                }
                var peak = 0
                val room = minOf(read, data.size - count)
                for (i in 0 until room) {
                    val value = buffer[i].toInt()
                    data[count + i] = value / 32768f
                    val magnitude = abs(value)
                    if (magnitude > peak) peak = magnitude
                }
                count += room
                val now = System.currentTimeMillis()
                if (now - levelPostedAt >= 100) {
                    levelPostedAt = now
                    val level = peak / 32768f
                    handler.post { if (running) onLevel(level) }
                }
            }
        } catch (_: Exception) {
            failed = true
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            running = false
        }
        val samples = if (cancelled || failed || count == 0) null else data.copyOf(count)
        handler.post { onDone(samples) }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 60
    }
}