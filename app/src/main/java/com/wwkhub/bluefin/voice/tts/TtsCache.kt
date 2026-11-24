package com.wwkhub.bluefin.voice.tts

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache for synthesized cloud sentences, keyed by everything that changes
 * the audio: text, internal voice id, speed and emotion. LRU by last-modified
 * time with a hard byte ceiling; lives in cacheDir so the system may reclaim
 * it under storage pressure without losing anything irreplaceable.
 */
internal class TtsCache(context: Context) {
    private val root = File(context.applicationContext.cacheDir, DIR)

    private fun fileFor(voiceId: String, key: String): File = File(root, "$voiceId.$key.mp3")

    fun get(voiceId: String, key: String): File? {
        val file = fileFor(voiceId, key)
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /** Moves a completed temp file into the cache and evicts over the ceiling. */
    fun put(voiceId: String, key: String, temp: File): File? {
        root.mkdirs()
        val target = fileFor(voiceId, key)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        evict()
        return target.takeIf { it.isFile }
    }

    fun sizeBytes(): Long = root.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        root.deleteRecursively()
    }

    fun removeVoice(voiceId: String) {
        root.listFiles().orEmpty().filter { it.name.startsWith("$voiceId.") }.forEach { it.delete() }
    }

    fun removeLegacyEntries() {
        root.listFiles().orEmpty().filter { LEGACY_FILE.matches(it.name) }.forEach { it.delete() }
    }

    private fun evict() {
        val files = root.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        const val DIR = "cloud-tts"
        const val MAX_BYTES = 50L * 1024 * 1024

        /** Stable cache key; pure so JVM tests cover collision resistance. */
        private val LEGACY_FILE = Regex("""[0-9a-f]{40}\.mp3""")

        fun key(text: String, voiceId: String, speed: Float, emotion: String, base: String = "", revision: Long = 0): String {
            val input = "v2|$base|$voiceId|$revision|%.2f|$emotion|$text".format(java.util.Locale.US, speed)
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(40)
        }
    }
}