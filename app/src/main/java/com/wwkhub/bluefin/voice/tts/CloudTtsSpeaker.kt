package com.wwkhub.bluefin.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wwkhub.bluefin.BluefinForegroundService
import com.wwkhub.bluefin.VoicePack
import com.wwkhub.bluefin.VoicePackRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Cloud sentence playback: one cancellable job per reply.
 *
 * Naturalness rules implemented here:
 *  - whole semantic sentences are synthesized (never per character/token);
 *  - sentence N+1 is downloaded and prepared WHILE sentence N plays, so the
 *    gap between sentences is only a MediaPlayer.start;
 *  - two MediaPlayer instances ping-pong; neither is re-initialized mid-reply;
 *  - cache hits skip the network entirely;
 *  - temporary failures retry with backoff, persistent failures abort the job
 *    and surface the normalized category to the provider manager.
 */
internal class CloudTtsSpeaker(context: Context) {
    private companion object {
        const val TAG = "BluefinCloudTts"
        const val MIN_AUDIO_BYTES = 512L
    }

    private class Playback(val client: CloudTtsClient, val revision: Long) {
        lateinit var worker: Thread
        @Volatile var current: MediaPlayer? = null
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val cache = TtsCache(appContext)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val lock = Any()
    @Volatile private var active: Playback? = null

    fun speak(
        pack: VoicePack,
        baseUrl: String,
        text: String,
        emotion: TtsEmotion,
        speed: Float,
        onFailed: (TtsFailure) -> Unit,
    ) {
        val sentences = TtsText.sentences(text)
        if (sentences.isEmpty()) return
        val playback = Playback(CloudTtsClient(baseUrl), VoicePackRepository.revision(appContext, pack.id))
        playback.worker = Thread(
            { run(playback, pack, sentences, emotion, speed, onFailed) },
            "bluefin-cloud-tts",
        ).apply { isDaemon = true }
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
        playback.client.cancel()
        playback.current?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        playback.current = null
    }

    private fun run(
        playback: Playback,
        pack: VoicePack,
        sentences: List<String>,
        emotion: TtsEmotion,
        speed: Float,
        onFailed: (TtsFailure) -> Unit,
    ) {
        var focus: AudioFocusRequest? = null
        val completed = LinkedBlockingQueue<Unit>()
        var previous: MediaPlayer? = null
        var reported: TtsFailure? = null
        try {
            CloudVoiceManager.ensureVoice(appContext, pack, playback.client, playback.revision)
            for ((index, sentence) in sentences.withIndex()) {
                if (active !== playback) return
                val file = sentenceFile(playback, pack, sentence, emotion, speed) ?: continue
                val player = MediaPlayer()
                try {
                    player.setAudioAttributes(attributes)
                    player.setDataSource(file.absolutePath)
                    player.setOnCompletionListener { completed.offer(Unit) }
                    player.setOnErrorListener { _, _, _ ->
                        completed.offer(Unit)
                        true
                    }
                    player.prepare()
                } catch (error: Exception) {
                    player.release()
                    if (active !== playback) return
                    Log.w(TAG, "sentence prepare failed type=${error.javaClass.simpleName}")
                    continue
                }
                if (active !== playback) {
                    player.release()
                    return
                }
                if (index == 0) {
                    focus = requestFocus(playback) ?: run {
                        player.release()
                        return
                    }
                } else {
                    // Wait for the previous sentence to finish; the next player
                    // is already prepared, so the handover is near-gapless.
                    awaitCompletion(playback, completed)
                    previous?.let { runCatching { it.release() } }
                    previous = null
                    if (active !== playback) {
                        player.release()
                        return
                    }
                }
                synchronized(lock) {
                    if (active !== playback) {
                        player.release()
                        return
                    }
                    playback.current = player
                    player.start()
                }
                previous = player
            }
            if (previous != null) awaitCompletion(playback, completed)
        } catch (failure: TtsFailure) {
            if (active === playback) reported = failure
        } catch (_: CancellationException) {
            // User stopped or a newer reply replaced this one.
        } catch (failure: Exception) {
            if (active === playback) {
                Log.w(TAG, "playback failed type=${failure.javaClass.simpleName}")
                reported = TtsFailure(TtsCategory.UNKNOWN_ERROR, "playback", "语音播放失败。")
            }
        } finally {
            previous?.let { runCatching { it.release() } }
            synchronized(lock) {
                playback.current?.let { runCatching { it.release() } }
                playback.current = null
                if (active === playback) active = null
            }
            focus?.let { audioManager.abandonAudioFocusRequest(it) }
            main.post { BluefinForegroundService.refresh(appContext) }
            val failure = reported
            if (failure != null && !playback.worker.isInterrupted) main.post {
                if (CloudTtsConfig.baseUrl(appContext) == playback.client.baseUrl) onFailed(failure)
            }
        }
    }

    private fun awaitCompletion(playback: Playback, completed: LinkedBlockingQueue<Unit>) {
        while (active === playback) {
            val signal = completed.poll(200, TimeUnit.MILLISECONDS)
            if (signal != null) return
        }
    }

    /** Cache hit, or a streamed download with bounded temporary retries. */
    private fun sentenceFile(
        playback: Playback,
        pack: VoicePack,
        sentence: String,
        emotion: TtsEmotion,
        speed: Float,
    ): File? {
        val key = TtsCache.key(sentence, pack.id, speed, emotion.wire, playback.client.baseUrl, playback.revision)
        cache.get(pack.id, key)?.let { return it }
        var attempt = 0
        while (active === playback) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("stopped")
            val temp = File(appContext.cacheDir, "${TtsCache.DIR}/.part-${System.nanoTime()}")
            try {
                CloudIdentity.withToken(appContext, playback.client) { token ->
                    playback.client.synthesize(token, sentence, pack.id, speed, emotion).use { input ->
                        temp.parentFile?.mkdirs()
                        temp.outputStream().use { output -> input.copyTo(output, 16 * 1024) }
                    }
                }
                if (temp.length() < MIN_AUDIO_BYTES) {
                    temp.delete()
                    throw TtsFailure(TtsCategory.UNKNOWN_ERROR, "empty_audio", "语音生成结果为空。")
                }
                return synchronized(lock) {
                    if (active === playback) cache.put(pack.id, key, temp)
                    else {
                        temp.delete()
                        null
                    }
                }
            } catch (failure: TtsFailure) {
                temp.delete()
                if (!TtsProviderPolicy.isRetryable(failure.category) || attempt >= TtsProviderPolicy.MAX_RETRIES) {
                    throw failure
                }
                Thread.sleep(TtsProviderPolicy.retryDelayMs(attempt, failure.retryAfterSeconds))
                attempt++
            } catch (_: IOException) {
                temp.delete()
                if (attempt >= TtsProviderPolicy.MAX_RETRIES) throw TtsFailure.network()
                Thread.sleep(TtsProviderPolicy.retryDelayMs(attempt, 0))
                attempt++
            } catch (error: Exception) {
                temp.delete()
                if (error is CancellationException) throw error
                throw TtsFailure.network()
            }
        }
        return null
    }

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
            Log.w(TAG, "audio focus request declined")
            null
        }
    }
}