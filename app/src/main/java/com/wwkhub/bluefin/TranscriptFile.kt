package com.wwkhub.bluefin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class ChatRole {
    USER,
    ASSISTANT,
}

internal data class ChatLine(
    val role: ChatRole,
    val text: String,
    val stopped: Boolean = false,
    val failed: Boolean = false,
)

/**
 * One app-private transcript shared by the full chat screen and the overlay
 * panel. It is separate from the persona file; clearing this file never touches
 * the persona or endpoint settings.
 */
internal object TranscriptFile {
    private const val FILE = "bluefin_chat_transcript.json"

    fun read(context: Context): MutableList<ChatLine> {
        val file = file(context)
        if (!file.isFile) return mutableListOf()
        return try {
            val array = JSONArray(file.readText())
            val lines = mutableListOf<ChatLine>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val role = if (item.optString("role") == "assistant") ChatRole.ASSISTANT else ChatRole.USER
                val text = item.optString("text")
                val stopped = item.optBoolean("stopped", false)
                val failed = item.optBoolean("failed", false)
                if (text.isEmpty() && !stopped && !failed) continue
                lines.add(
                    ChatLine(
                        role = role,
                        text = text,
                        stopped = stopped,
                        failed = failed,
                    ),
                )
            }
            lines
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun write(context: Context, lines: List<ChatLine>) {
        try {
            val array = JSONArray()
            lines.forEach { line ->
                array.put(
                    JSONObject()
                        .put("role", if (line.role == ChatRole.ASSISTANT) "assistant" else "user")
                        .put("text", line.text)
                        .put("stopped", line.stopped)
                        .put("failed", line.failed),
                )
            }
            file(context).writeText(array.toString())
        } catch (_: Exception) {
            // History is helpful but never allowed to break a send.
        }
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
            // Nothing else to do; the in-memory transcript is cleared by the session.
        }
    }

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE)
}
