package com.wwkhub.bluefin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.Executors

internal enum class ChatRole {
    USER,
    ASSISTANT,
}

internal data class ChatLine(
    val role: ChatRole,
    val text: String,
    val stopped: Boolean = false,
    val failed: Boolean = false,
    /** Wire name of the reply's emotion marker; null for legacy/user lines. */
    val emotion: String? = null,
)

/**
 * One app-private transcript shared by the full chat screen and the overlay
 * panel. It is separate from the persona file; clearing this file never touches
 * the persona or endpoint settings.
 */
internal object TranscriptFile {
    private const val FILE = "bluefin_chat_transcript.json"

    fun read(context: Context): List<ChatLine> = read(file(context))

    internal fun read(file: File): List<ChatLine> {
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
                val emotion = item.optString("emotion").takeIf { it.isNotEmpty() }
                if (text.isEmpty() && !stopped && !failed) continue
                lines.add(
                    ChatLine(
                        role = role,
                        text = text,
                        stopped = stopped,
                        failed = failed,
                        emotion = emotion,
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
            write(file(context), lines)
        } catch (_: Exception) {
            // History is helpful but never allowed to break a send.
        }
    }

    fun clear(context: Context) {
        write(context, emptyList())
    }

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE)

    internal fun write(file: File, lines: List<ChatLine>) {
        val array = JSONArray()
        lines.forEach { line ->
            array.put(
                JSONObject()
                    .put("role", if (line.role == ChatRole.ASSISTANT) "assistant" else "user")
                    .put("text", line.text)
                    .put("stopped", line.stopped)
                    .put("failed", line.failed)
                    .put("emotion", line.emotion ?: ""),
            )
        }
        val staged = File(file.parentFile, "${file.name}.new")
        try {
            FileOutputStream(staged).use { output ->
                output.write(array.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(staged.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            staged.delete()
        }
    }
}

/** Callers enqueue while holding the chat session lock. Only the writer accesses the disk. */
internal class TranscriptWrites(
    private val dispatch: (Runnable) -> Unit = Executors.newSingleThreadExecutor { work ->
        Thread(work, "bluefin-transcript").apply { isDaemon = true }
    }::execute,
) {
    @Volatile private var revision = 0L

    fun write(action: () -> Unit) {
        val queuedRevision = revision
        dispatch(Runnable { if (queuedRevision == revision) action() })
    }

    fun clear(action: () -> Unit) {
        revision++
        write(action)
    }
}
