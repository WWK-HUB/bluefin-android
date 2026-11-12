package com.wwkhub.bluefin

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * Serves an installed pack under `https://appassets.androidplatform.net/packs/<id>/...`.
 * The URL stays on the same origin as the host page so ES modules, fetches of
 * `model.json` and texture loads work without file access.
 */
internal class PackAssetHandler(private val context: Context) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val clean = android.net.Uri.decode(path.substringBefore('?')).trimStart('/')
        val slash = clean.indexOf('/')
        if (slash <= 0) return null
        val id = clean.substring(0, slash)
        if (!PackPaths.validId(id) || id == PackFormat.BUILT_IN_ID) return null
        val rel = PackPaths.normalizeArchivePath(clean.substring(slash + 1)) ?: return null
        val root = File(PackRepository.packRoot(context), id).canonicalFile
        if (!root.isDirectory) return null
        val file = File(root, rel).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) return null
        if (!file.isFile) return null
        return try {
            WebResourceResponse(
                mimeFor(file.name),
                if (isText(file.name)) "utf-8" else null,
                200,
                "OK",
                mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"),
                file.inputStream(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isText(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".json") ||
            lower.endsWith(".html") || lower.endsWith(".css") || lower.endsWith(".svg")
    }

    private fun mimeFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "text/javascript"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".ogg") -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }
}
