package com.wwkhub.bluefin.voice.tts

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

/**
 * HTTP client for the cloud TTS worker. The device only ever presents its own
 * short-lived session token — the vendor API key lives exclusively in the
 * worker secret and cannot appear here. Tokens and request bodies are never
 * logged.
 */
internal class CloudTtsClient(val baseUrl: String) {
    private var cancelled = false

    @Volatile
    private var active: HttpURLConnection? = null

    @Synchronized
    fun cancel() {
        cancelled = true
        active?.disconnect()
    }

    /** Streams one sentence of audio; the caller owns and must close the stream. */
    fun synthesize(token: String, text: String, voiceId: String, speed: Float, emotion: TtsEmotion): InputStream {
        val body = JSONObject()
            .put("text", text)
            .put("voiceId", voiceId)
            .put("speed", speed.toDouble())
            .put("emotion", emotion.wire)
        val connection = open("/v1/tts", token, body)
        val code = connection.responseCode
        if (code !in 200..299) throw failureOf(connection)
        val type = connection.contentType?.lowercase().orEmpty()
        if (!type.contains("audio")) {
            connection.disconnect()
            throw TtsFailure(TtsCategory.UNKNOWN_ERROR, "bad_content", "语音服务返回了异常内容。")
        }
        val input = connection.inputStream
        return object : FilterInputStream(input) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    connection.disconnect()
                    active = null
                }
            }
        }
    }

    fun register(deviceId: String): JSONObject {
        val body = JSONObject().put("deviceId", deviceId).put("name", android.os.Build.MODEL ?: "android")
        return postJson("/v1/register", null, body)
    }

    fun refresh(token: String): JSONObject = postJson("/v1/token/refresh", token, JSONObject())

    /** Recovery probe: the worker runs one minimal FREE-model synthesis. */
    fun probe(token: String): Boolean {
        val result = postJson("/v1/probe", token, JSONObject())
        return result.optBoolean("cloud", false)
    }

    fun listVoices(token: String): JSONArray =
        postJson("/v1/voices", token, null, method = "GET").optJSONArray("voices") ?: JSONArray()

    fun uploadVoice(
        token: String, voiceId: String, name: String, audio: ByteArray, referenceText: String, bindingId: String,
    ): JSONObject {
        val body = JSONObject()
            .put("voiceId", voiceId)
            .put("name", name)
            .put("audio", Base64.encodeToString(audio, Base64.NO_WRAP))
            .put("audioType", "audio/wav")
            .put("referenceText", referenceText)
            .put("bindingId", bindingId)
        return postJson("/v1/voices", token, body)
    }

    fun linkVoice(token: String, voiceId: String, fishReferenceId: String, name: String, bindingId: String): JSONObject {
        val body = JSONObject()
            .put("voiceId", voiceId)
            .put("fishModelId", fishReferenceId)
            .put("bindingId", bindingId)
            .put("name", name)
        return postJson("/v1/voices/link", token, body)
    }

    fun deleteVoice(token: String, voiceId: String, bindingId: String?) {
        val body = bindingId?.let { JSONObject().put("bindingId", it) }
        if (!postJson("/v1/voices/$voiceId", token, body, method = "DELETE").optBoolean("deleted")) {
            throw TtsFailure(TtsCategory.UNKNOWN_ERROR, "delete_unconfirmed", "云端尚未确认删除。")
        }
    }

    // ---- plumbing ----------------------------------------------------------

    private fun postJson(path: String, token: String?, body: JSONObject?, method: String = "POST"): JSONObject {
        val connection = open(path, token, body, method)
        val code = connection.responseCode
        if (code !in 200..299) throw failureOf(connection)
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        active = null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            throw TtsFailure(TtsCategory.UNKNOWN_ERROR, "bad_json", "语音服务返回了异常内容。")
        }
    }

    private fun open(path: String, token: String?, body: JSONObject?, method: String = "POST"): HttpURLConnection {
        if (baseUrl.isEmpty()) {
            throw TtsFailure(TtsCategory.UNKNOWN_ERROR, "not_configured", "云端语音服务地址未配置。")
        }
        val connection = synchronized(this) {
            if (cancelled) throw CancellationException("request cancelled")
            (URL(baseUrl + path).openConnection() as HttpURLConnection).also { active = it }
        }
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            } catch (error: Exception) {
                connection.disconnect()
                active = null
                throw error
            }
        }
        return connection
    }

    private fun failureOf(connection: HttpURLConnection): TtsFailure {
        val code = connection.responseCode
        val errorText = runCatching {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        connection.disconnect()
        active = null
        if (Thread.currentThread().isInterrupted) throw CancellationException("stopped")
        // The worker's normalized category is authoritative; raw HTTP guessing
        // is only a fallback for non-worker responses (proxies, portals).
        return if (errorText.contains("\"category\"")) TtsFailure.fromJson(errorText)
        else TtsFailure.fromHttp(code)
    }
}