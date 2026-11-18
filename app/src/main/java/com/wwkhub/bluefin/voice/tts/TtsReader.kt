package com.wwkhub.bluefin.voice.tts

import android.content.Context
import com.wwkhub.bluefin.BluefinStore
import com.wwkhub.bluefin.ChatSnapshot
import com.wwkhub.bluefin.VoicePackRepository

/**
 * Facade over the provider manager for the chat screen, the overlay and the
 * foreground service. Completed replies auto-read when both switches are on;
 * manual reread works whenever speech is enabled and keeps the reply's
 * emotion (persisted with the transcript).
 */
internal class TtsReader private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val manager = TtsProviderManager.shared(appContext)

    fun isSpeaking(): Boolean = manager.isSpeaking()

    fun reread(snapshot: ChatSnapshot) {
        val index = snapshot.responseStartIndex ?: return
        val line = snapshot.lines.getOrNull(index) ?: return
        val pack = VoicePackRepository.find(appContext, VoicePackRepository.currentId(appContext)) ?: return
        manager.speak(pack, line.text, TtsEmotion.fromWire(line.emotion))
    }

    fun stop() = manager.stop()

    companion object {
        @Volatile
        private var instance: TtsReader? = null

        fun shared(context: Context): TtsReader {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: TtsReader(context).also { instance = it }
            }
        }

        fun readCompletedReply(context: Context, text: String, emotion: TtsEmotion = TtsEmotion.NEUTRAL) {
            val prefs = context.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
            if (!BluefinStore.readSpeechOn(prefs) || !BluefinStore.readAutoSpeech(prefs)) return
            if (!TtsText.hasSpeakableText(text)) return
            val pack = VoicePackRepository.find(context, VoicePackRepository.currentId(context)) ?: return
            TtsProviderManager.shared(context).speak(pack, text, emotion)
        }

        fun peek(): TtsReader? = instance
    }
}