package com.wwkhub.bluefin

import com.wwkhub.bluefin.voice.tts.TtsEmotion
import org.json.JSONObject

internal data class ScreenObservation(
    val summary: String,
    val remark: String? = null,
    val emotion: TtsEmotion = TtsEmotion.NEUTRAL,
) {
    companion object {
        fun parse(response: String): ScreenObservation? {
            val text = response.trim()
            if (text.isEmpty()) return null
            val payload = if (text.startsWith("```")) {
                text.substringAfter('\n', "").substringBeforeLast("```").trim()
            } else text
            if (!payload.startsWith("{")) {
                // A model that can only return plain text can still provide a private summary.
                return payload.takeIf { it.isNotBlank() }?.let { ScreenObservation(it.take(500)) }
            }
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            val summary = (json.opt("summary") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val remark = (json.opt("remark") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            return ScreenObservation(summary.take(500), remark?.take(160), TtsEmotion.fromWire(json.optString("emotion")))
        }
    }
}
