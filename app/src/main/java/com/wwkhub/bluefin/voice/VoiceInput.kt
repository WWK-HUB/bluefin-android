package com.wwkhub.bluefin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wwkhub.bluefin.R

/**
 * Process-scoped voice input orchestrator, shared by the chat screen and the
 * overlay record panel. Construction is lazy and touches nothing until the
 * user actually taps a mic button; the overlay show path never reaches it.
 *
 * One round: startRecording -> (samples) -> RECOGNIZING -> text/error.
 * The active surface attaches as [Listener]; recognized text is handed back
 * to the host, which routes it through the same gates as typed text.
 * All methods are called from the UI thread.
 */
internal class VoiceInput private constructor(context: Context) {
    interface Listener {
        fun onVoicePhaseChanged(phase: VoicePhase)
        fun onVoiceLevel(level: Float)
        fun onVoiceText(text: String)
        fun onVoiceError(message: String)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val state = VoiceInputState()
    private val recognizer = VoiceRecognizer(VoiceModelManager.modelDir(appContext))
    private var recorder: VoiceRecorder? = null
    private var listener: Listener? = null
    private val releaseRecognizer = Runnable { recognizer.release() }

    val phase: VoicePhase get() = state.phase

    fun attach(listener: Listener) {
        this.listener = listener
    }

    fun detach(listener: Listener) {
        if (this.listener === listener) this.listener = null
    }

    /** Starts a round; false when already active or the microphone fails. */
    fun startRecording(): Boolean {
        val generation = state.beginRecording() ?: return false
        handler.removeCallbacks(releaseRecognizer)
        val rec = VoiceRecorder(
            onLevel = { level ->
                if (state.isCurrent(generation)) listener?.onVoiceLevel(level)
            },
            onDone = { samples -> onSamples(generation, samples) },
        )
        recorder = rec
        if (!rec.start()) {
            state.finish(generation)
            recorder = null
            listener?.onVoiceError(appContext.getString(R.string.voice_record_failed))
            listener?.onVoicePhaseChanged(VoicePhase.IDLE)
            return false
        }
        listener?.onVoicePhaseChanged(VoicePhase.RECORDING)
        Log.i(TAG, "voice round started generation=$generation")
        return true
    }

    /** Ends the current round; samples (and recognition) follow asynchronously. */
    fun stopRecording() {
        recorder?.stop()
    }

    /** Drops the round and invalidates in-flight callbacks; no error is shown. */
    fun cancel() {
        if (state.cancel()) {
            recorder?.cancel()
            recorder = null
            listener?.onVoicePhaseChanged(VoicePhase.IDLE)
        }
    }

    private fun onSamples(generation: Long, samples: FloatArray?) {
        recorder = null
        if (samples == null) {
            // User cancel already bumped the generation; only a real mic
            // failure finishes the round here and reports an error.
            if (state.finish(generation)) {
                Log.w(TAG, "voice round produced no samples generation=$generation")
                listener?.onVoiceError(appContext.getString(R.string.voice_record_failed))
                listener?.onVoicePhaseChanged(VoicePhase.IDLE)
            }
            return
        }
        if (!state.beginRecognizing(generation)) return
        Log.i(TAG, "voice samples=${samples.size} recognizing generation=$generation")
        listener?.onVoicePhaseChanged(VoicePhase.RECOGNIZING)
        Thread(
            {
                val result = try {
                    recognizer.recognize(samples)
                } catch (_: Exception) {
                    recognizer.release()
                    null
                }
                handler.post {
                    if (!state.finish(generation)) return@post
                    Log.i(TAG, "voice result chars=${result?.length ?: -1} generation=$generation")
                    when {
                        result == null ->
                            listener?.onVoiceError(appContext.getString(R.string.voice_recognize_failed))
                        result.isBlank() ->
                            listener?.onVoiceError(appContext.getString(R.string.voice_empty_result))
                        else -> listener?.onVoiceText(result)
                    }
                    listener?.onVoicePhaseChanged(VoicePhase.IDLE)
                    handler.removeCallbacks(releaseRecognizer)
                    handler.postDelayed(releaseRecognizer, RECOGNIZER_IDLE_RELEASE_MS)
                }
            },
            "bluefin-voice-asr",
        ).apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "BluefinVoice"
        private const val RECOGNIZER_IDLE_RELEASE_MS = 120_000L

        @Volatile
        private var instance: VoiceInput? = null

        fun shared(context: Context): VoiceInput {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: VoiceInput(context).also { instance = it }
            }
        }
    }
}