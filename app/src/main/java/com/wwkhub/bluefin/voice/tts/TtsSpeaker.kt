package com.wwkhub.bluefin.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.wwkhub.bluefin.BluefinForegroundService
import com.wwkhub.bluefin.VoicePack
import java.io.File

/** Synthesizes and plays the sentences in a reply with one cancellable playback job. */
internal class TtsSpeaker(context: Context) {
    private companion object {
        const val TAG = "BluefinTts"
        const val MIN_SAMPLES = 1_600
    }

    private class Playback {
        lateinit var worker: Thread
        var track: AudioTrack? = null
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val lock = Any()
    @Volatile private var active: Playback? = null

    fun speak(pack: VoicePack, text: String) {
        // One sentence per native call: ZipVoice returns empty audio for
        // multi-sentence text and its instance stays poisoned afterwards, so
        // synthesize() below rebuilds the engine when that happens.
        val sentences = TtsText.sentences(text)
        if (sentences.isEmpty()) return
        val playback = Playback()
        playback.worker = Thread({ run(playback, pack, sentences) }, "bluefin-tts-speak").apply {
            isDaemon = true
        }
        synchronized(lock) {
            active?.let(::cancelLocked)
            active = playback
        }
        BluefinForegroundService.refresh(appContext)
        playback.worker.start()
    }

    fun stop() {
        synchronized(lock) {
            val playback = active ?: return
            active = null
            cancelLocked(playback)
        }
        main.post { BluefinForegroundService.refresh(appContext) }
    }

    fun isSpeaking(): Boolean = active != null

    private fun cancelLocked(playback: Playback) {
        playback.worker.interrupt()
        playback.track?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    private fun run(playback: Playback, pack: VoicePack, sentences: List<String>) {
        var focus: AudioFocusRequest? = null
        try {
            val reference = readReference(pack) ?: return
            Log.d(TAG, "Local playback starting sentences=${sentences.size}")
            for ((index, sentence) in sentences.withIndex()) {
                if (active !== playback) {
                    Log.d(TAG, "Local playback superseded at sentence $index")
                    return
                }
                val generated = synthesize(playback, pack, reference, sentence) ?: continue
                if (focus == null) {
                    focus = requestFocus(playback) ?: return
                }
                playBlocking(playback, generated)
            }
            Log.d(TAG, "Local playback loop complete")
        } catch (failure: Exception) {
            if (active === playback) Log.w(TAG, "Playback failed: ${failure.javaClass.simpleName}")
        } finally {
            focus?.let { audioManager.abandonAudioFocusRequest(it) }
            synchronized(lock) {
                if (active === playback) active = null
            }
            main.post { BluefinForegroundService.refresh(appContext) }
        }
    }

    private class GeneratedTts(val samples: FloatArray, val sampleRate: Int)

    private fun synthesize(
        playback: Playback,
        pack: VoicePack,
        reference: FloatArray,
        sentence: String,
    ): GeneratedTts? = synchronized(TtsEngineHolder.lock) {
        if (active !== playback) return null
        val config = GenerationConfig(0.2f, pack.speed, 0, reference, pack.sampleRate, pack.referenceText, pack.numSteps)
        var engine = TtsEngineHolder.get(appContext) ?: return null
        var audio = engine.generateWithConfig(sentence, config)
        if (audio.samples.size < MIN_SAMPLES) {
            // An empty generation poisons the native instance: every later
            // call returns empty instantly. Rebuild once and retry so one bad
            // sentence cannot sink the whole reply.
            Log.w(TAG, "Empty generation; rebuilding engine and retrying once")
            TtsEngineHolder.reset()
            if (active !== playback) return null
            engine = TtsEngineHolder.get(appContext) ?: return null
            audio = engine.generateWithConfig(sentence, config)
        }
        val samples = audio.samples
        if (samples.size < MIN_SAMPLES) {
            Log.w(TAG, "Engine produced no playable speech for a sentence")
            return null
        }
        GeneratedTts(samples, audio.sampleRate)
    }

    private fun playBlocking(playback: Playback, generated: GeneratedTts) {
        val sampleRate = generated.sampleRate
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(8_192)
        val samples = generated.samples
        val pcm = ShortArray(samples.size) { index ->
            (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val player = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            synchronized(lock) {
                if (active !== playback) return
                playback.track = player
                player.play()
            }
            var written = 0
            while (written < pcm.size && active === playback) {
                val count = player.write(pcm, written, minOf(4_096, pcm.size - written))
                if (count <= 0) break
                written += count
            }
            // AudioTrack.write returns when frames enter the buffer. Keep the
            // track alive until the hardware consumes them.
            val deadline = SystemClock.elapsedRealtime() + written * 1_000L / sampleRate + 1_000L
            while (active === playback && player.playbackHeadPosition < written &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                Thread.sleep(20)
            }
            synchronized(lock) {
                if (active === playback && written > 0) {
                    player.stop()
                    Log.d(TAG, "Playback finished: $written/${pcm.size} frames")
                }
            }
        } finally {
            synchronized(lock) {
                playback.track = null
                player.release()
            }
        }
    }

    private fun readReference(pack: VoicePack): FloatArray? = runCatching {
        val bytes = if (pack.builtIn) {
            appContext.assets.open("voice-packs/${pack.id}/${pack.reference}").use { it.readBytes() }
        } else {
            File(File(appContext.filesDir, "voice-packs/${pack.id}"), pack.reference).readBytes()
        }
        WavReader.read(bytes, pack.sampleRate)
    }.onFailure {
        Log.w(TAG, "Voice reference could not be read: ${it.javaClass.simpleName}")
    }.getOrNull()

    private fun requestFocus(playback: Playback): AudioFocusRequest? = synchronized(lock) {
        if (active !== playback) return null
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) {
                    synchronized(lock) {
                        if (active === playback) {
                            active = null
                            cancelLocked(playback)
                        }
                    }
                }
            }, main)
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            request
        } else {
            Log.w(TAG, "Audio focus request declined")
            null
        }
    }
}
