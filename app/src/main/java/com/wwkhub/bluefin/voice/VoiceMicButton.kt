package com.wwkhub.bluefin.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.wwkhub.bluefin.ChatDraft
import com.wwkhub.bluefin.R
import com.wwkhub.bluefin.ScreenAwareness
import com.wwkhub.bluefin.VoiceSetupActivity

/**
 * Binds one mic button to the shared [VoiceInput]. The host surface owns
 * routing: recognized text goes through [VoiceSendPolicy] and then the same
 * send/look-command paths as typed text.
 *
 * Tap once to record, tap again to stop and auto-send. While recognizing,
 * taps are ignored. Attach when the surface becomes usable, detach when it
 * goes away — detaching cancels any in-flight round (no background recording).
 */
internal class VoiceMicButton(
    private val context: Context,
    private val button: MaterialButton,
    private val host: Host,
) : VoiceInput.Listener {
    interface Host {
        fun isChatConfigured(): Boolean
        fun isChatBusy(): Boolean

        /** Show a voice status line (recording timer / recognizing). */
        fun voiceStatus(text: String)

        /** Restore the surface's normal status line. */
        fun voiceStatusIdle()

        /** Route recognized text exactly like a typed send. */
        fun sendVoiceText(text: String)

        /** Recognized text matched the look-screen command. */
        fun voiceLookCommand(text: String)

        /** Chat is not configured; offer the settings entry. */
        fun voiceNotConfigured()

        /** Busy: keep the utterance as an editable draft instead of losing it. */
        fun stashVoiceDraft(text: String)
    }

    private val voice = VoiceInput.shared(context)
    private var attached = false
    private var recordingStartedAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!attached || voice.phase != VoicePhase.RECORDING) return
            val seconds = ((SystemClock.elapsedRealtime() - recordingStartedAt) / 1000).toInt()
            host.voiceStatus(context.getString(R.string.voice_recording, seconds))
            button.postDelayed(this, 500)
        }
    }

    fun attach() {
        if (attached) return
        attached = true
        voice.attach(this)
        button.setOnClickListener { onMicClick() }
        renderPhase(voice.phase)
    }

    fun detach() {
        if (!attached) return
        attached = false
        button.removeCallbacks(ticker)
        voice.detach(this)
        voice.cancel()
        button.setOnClickListener(null)
        renderIdle()
    }

    private fun onMicClick() {
        when (voice.phase) {
            VoicePhase.RECORDING -> voice.stopRecording()
            VoicePhase.RECOGNIZING -> Unit
            VoicePhase.IDLE -> ensureReadyThenRecord()
        }
    }

    private fun ensureReadyThenRecord() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted && VoiceModelManager.isInstalled(context)) {
            beginRound()
            return
        }
        VoiceSetupActivity.launch(context) { ready -> if (ready) beginRound() }
    }

    private fun beginRound() {
        if (!voice.startRecording()) return
        recordingStartedAt = SystemClock.elapsedRealtime()
        button.post(ticker)
    }

    override fun onVoicePhaseChanged(phase: VoicePhase) {
        if (!attached) return
        renderPhase(phase)
    }

    override fun onVoiceLevel(level: Float) {
        if (attached && voice.phase == VoicePhase.RECORDING) {
            button.alpha = 0.7f + 0.3f * level.coerceIn(0f, 1f)
        }
    }

    override fun onVoiceText(text: String) {
        if (!attached) return
        val decision = VoiceSendPolicy.decide(
            text = text,
            configured = host.isChatConfigured(),
            busy = host.isChatBusy(),
            isLookCommand = ScreenAwareness::isLookCommand,
        )
        when (decision) {
            VoiceSendDecision.SEND -> host.sendVoiceText(text)
            VoiceSendDecision.LOOK_COMMAND -> host.voiceLookCommand(text)
            VoiceSendDecision.EMPTY -> toast(R.string.voice_empty_result)
            VoiceSendDecision.BUSY -> {
                host.stashVoiceDraft(text)
                toast(R.string.voice_busy)
            }
            VoiceSendDecision.NOT_CONFIGURED -> host.voiceNotConfigured()
        }
    }

    override fun onVoiceError(message: String) {
        if (attached) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun renderPhase(phase: VoicePhase) {
        when (phase) {
            VoicePhase.RECORDING -> {
                button.setIconResource(R.drawable.ic_voice_stop)
                button.contentDescription = context.getString(R.string.voice_mic_stop)
                button.isEnabled = true
            }
            VoicePhase.RECOGNIZING -> {
                button.removeCallbacks(ticker)
                button.setIconResource(R.drawable.ic_mic)
                button.isEnabled = false
                host.voiceStatus(context.getString(R.string.voice_recognizing))
            }
            VoicePhase.IDLE -> renderIdle()
        }
    }

    private fun renderIdle() {
        button.removeCallbacks(ticker)
        button.setIconResource(R.drawable.ic_mic)
        button.contentDescription = context.getString(R.string.voice_mic)
        button.isEnabled = true
        button.alpha = 1f
        host.voiceStatusIdle()
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
}