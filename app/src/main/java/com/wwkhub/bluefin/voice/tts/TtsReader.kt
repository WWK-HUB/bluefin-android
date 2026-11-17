package com.wwkhub.bluefin.voice.tts

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.wwkhub.bluefin.BluefinStore
import com.wwkhub.bluefin.ChatPhase
import com.wwkhub.bluefin.ChatSession
import com.wwkhub.bluefin.ChatSnapshot
import com.wwkhub.bluefin.VoicePack
import com.wwkhub.bluefin.VoicePackRepository

/**
 * Watches [ChatSession] and reads each finished reply aloud.
 *
 * This lives outside ChatSession on purpose: the transcript has no business
 * knowing about audio, and keeping the hook here means speech can be switched
 * off without touching the conversation code.
 *
 * Only a *freshly finished* reply is spoken. The session fires several snapshots
 * per turn, so the last spoken reply is remembered and each one is spoken at
 * most once. Stopped and failed turns are skipped: half an answer, or an error
 * bubble, is worse than silence.
 *
 * Several chat surfaces can be open at once (the in-app screen and the overlay
 * panel), so listeners are tracked per owner token rather than per call: an
 * unbalanced detach can never silence the other surface.
 */
internal class TtsReader private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val speaker = TtsSpeaker(appContext)

    private val owners = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Any, Boolean>(),
    )

    private var lastSpokenGeneration = -1
    private var lastSpokenIndex = -1

    private var session: ChatSession? = null

    private val listener: (ChatSnapshot) -> Unit = { snapshot -> onSnapshot(snapshot) }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                BluefinStore.KEY_SPEECH_ON -> if (!isEnabled()) handler.post { speaker.stop() }
                BluefinStore.KEY_VOICE_PACK_ID -> handler.post { speaker.stop() }
            }
        }

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)

    /** [owner] is the surface that wants replies read; usually the Activity or window. */
    fun attach(owner: Any) {
        val first = owners.isEmpty()
        owners.add(owner)
        if (!first || session != null) return
        val active = ChatSession.shared(appContext)
        session = active
        active.addListener(listener)
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun detach(owner: Any) {
        owners.remove(owner)
        if (owners.isNotEmpty() || session == null) return
        session?.removeListener(listener)
        session = null
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        speaker.stop()
        lastSpokenGeneration = -1
        lastSpokenIndex = -1
    }

    fun isEnabled(): Boolean = BluefinStore.readSpeechOn(prefs())

    fun isSpeaking(): Boolean = speaker.isSpeaking()

    /** Reads the reply shown on the reply page again. */
    fun reread(snapshot: ChatSnapshot) {
        val index = snapshot.responseStartIndex ?: return
        val text = snapshot.lines.getOrNull(index)?.text ?: return
        speakNow(text)
    }

    /** Explicit user request: speaks even when auto-read is off. */
    fun speakNow(text: String) {
        val pack = currentPack() ?: return
        if (!TtsText.hasSpeakableText(text)) return
        speaker.speak(pack, text)
    }

    fun stop() {
        speaker.stop()
    }

    private fun onSnapshot(snapshot: ChatSnapshot) {
        if (!isEnabled()) return
        if (snapshot.phase != ChatPhase.IDLE || snapshot.workerBusy) return
        val index = snapshot.responseStartIndex ?: return
        val generation = snapshot.requestGeneration
        if (generation == lastSpokenGeneration && index == lastSpokenIndex) return
        val text = snapshot.lines.getOrNull(index)?.text ?: return
        if (!TtsText.hasSpeakableText(text)) return
        lastSpokenGeneration = generation
        lastSpokenIndex = index
        speakNow(text)
    }

    private fun currentPack(): VoicePack? {
        val id = VoicePackRepository.currentId(appContext)
        return VoicePackRepository.find(appContext, id)
    }

    companion object {
        @Volatile
        private var instance: TtsReader? = null

        fun shared(context: Context): TtsReader {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: TtsReader(context).also { instance = it }
            }
        }

        /** Stops playback without forcing construction of a reader. */
        fun peek(): TtsReader? = instance
    }
}
