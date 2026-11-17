package com.wwkhub.bluefin.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.wwkhub.bluefin.VoicePack
import java.io.File

/**
 * Speaks reply text with the selected timbre.
 *
 * Pipeline per reply: strip Markdown → split into sentences → synthesise one
 * sentence ahead → play it through [AudioTrack] at whatever rate the engine
 * produced (24 kHz for ZipVoice) → repeat.
 *
 * Synthesis is roughly real time on a phone, so playback of sentence N runs
 * while sentence N+1 is being generated; without that overlap the pet would
 * pause for a second between every sentence.
 *
 * Audio focus is requested so music ducks instead of talking over the pet, and
 * any loss of focus (incoming call, another app starting playback) stops the
 * speech immediately rather than mixing into it.
 */
internal class TtsSpeaker(private val context: Context) {

    private companion object {
        const val TAG = "BluefinTts"

        /** ZipVoice returns empty audio instead of throwing when it cannot speak. */
        const val MIN_SAMPLES = 1_600
    }

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var cancelled = false

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var worker: Thread? = null

    private var focusRequest: AudioFocusRequest? = null

    /** Unit work: synthesise [text] and block while it plays. */
    fun speak(pack: VoicePack, text: String) {
        val sentences = TtsText.sentences(text)
        if (sentences.isEmpty()) return
        stopInternal(releaseFocus = true)
        cancelled = false
        val reference = readReference(pack) ?: return
        val thread = Thread(
            { run(pack, reference, sentences) },
            "bluefin-tts-speak",
        )
        worker = thread.apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        stopInternal(releaseFocus = true)
    }

    fun isSpeaking(): Boolean = worker?.isAlive == true

    private fun stopInternal(releaseFocus: Boolean) {
        cancelled = true
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        if (releaseFocus) abandonFocus()
    }

    private fun run(pack: VoicePack, reference: FloatArray, sentences: List<String>) {
        val engine = TtsEngineHolder.get(appContext) ?: return
        if (!requestFocus()) return

        try {
            var pending: GeneratedTts? = synthesize(engine, pack, reference, sentences[0])
            var index = 1
            while (pending != null && !cancelled) {
                // Start the next sentence while this one is still playing.
                val next = if (index < sentences.size) {
                    synthesize(engine, pack, reference, sentences[index]).also { index++ }
                } else {
                    null
                }
                playBlocking(pending)
                pending = next
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "speak failed type=${failure.javaClass.simpleName}")
        } finally {
            runCatching { track?.release() }
            track = null
            abandonFocus()
        }
    }

    private class GeneratedTts(val samples: FloatArray, val sampleRate: Int)

    private fun synthesize(
        engine: com.k2fsa.sherpa.onnx.OfflineTts,
        pack: VoicePack,
        reference: FloatArray,
        sentence: String,
    ): GeneratedTts? = synchronized(TtsEngineHolder.lock) {
        if (cancelled) return null
        val config = GenerationConfig(
            0.2f, // silenceScale
            pack.speed,
            0, // sid
            reference,
            pack.sampleRate,
            pack.referenceText,
            pack.numSteps,
        )
        val audio = runCatching { engine.generateWithConfig(sentence, config) }.getOrNull()
            ?: return null
        val samples = audio.samples
        if (samples.size < MIN_SAMPLES) return null
        GeneratedTts(samples, audio.sampleRate)
    }

    /** Writes the whole buffer in chunks and returns when it has been heard. */
    private fun playBlocking(generated: GeneratedTts) {
        if (cancelled) return
        val samples = generated.samples
        val minBuffer = AudioTrack.getMinBufferSize(
            generated.sampleRate,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4_096)
        val shorts = ShortArray(samples.size) { index ->
            (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(generated.sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, shorts.size * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = player
        try {
            player.play()
            var offset = 0
            val chunk = 4_096
            while (offset < shorts.size && !cancelled) {
                val count = minOf(chunk, shorts.size - offset)
                val written = player.write(shorts, offset, count)
                if (written <= 0) break
                offset += written
            }
            // Let the tail drain so the last syllable is not cut off.
            if (!cancelled) player.stop()
        } finally {
            runCatching { player.release() }
            if (track === player) track = null
        }
    }

    private fun readReference(pack: VoicePack): FloatArray? = runCatching {
        val bytes = if (pack.builtIn) {
            appContext.assets.open("voice-packs/${pack.id}/${pack.reference}")
                .use { it.readBytes() }
        } else {
            File(File(appContext.filesDir, "voice-packs/${pack.id}"), pack.reference)
                .readBytes()
        }
        WavReader.read(bytes, pack.sampleRate)
    }.getOrNull()

    private fun requestFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> stopInternal(releaseFocus = false)
            }
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(listener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: return
            focusRequest = null
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        }
    }
}
