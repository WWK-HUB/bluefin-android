package com.wwkhub.bluefin.voice.tts

import org.json.JSONObject

/**
 * The eight normalized failure categories. The cloud worker derives them from
 * the ACTUAL vendor response semantics; the device never guesses from a raw
 * HTTP status. [fromJson] reads the worker's `category` field — [fromHttp] is
 * only a last-resort fallback for responses that are not from our worker
 * (captive portals, generic proxies).
 */
internal enum class TtsCategory {
    TEMPORARY_NETWORK_ERROR,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    QUOTA_EXHAUSTED,
    MODEL_UNAVAILABLE,
    AUTH_ERROR,
    INVALID_VOICE,
    UNKNOWN_ERROR;

    companion object {
        fun fromWire(raw: String?): TtsCategory =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN_ERROR
    }
}

/** One speech failure with its normalized category and a safe user message. */
internal class TtsFailure(
    val category: TtsCategory,
    val code: String,
    override val message: String,
    val retryAfterSeconds: Int = 0,
) : Exception(message) {

    /** Persistent: retrying the same request right now cannot succeed. */
    val isPersistent: Boolean
        get() = category == TtsCategory.QUOTA_EXHAUSTED ||
            category == TtsCategory.MODEL_UNAVAILABLE ||
            category == TtsCategory.AUTH_ERROR ||
            category == TtsCategory.INVALID_VOICE

    /** Confirmed exhaustion/unavailability: no streak needed before fallback. */
    val isDefinitive: Boolean
        get() = category == TtsCategory.QUOTA_EXHAUSTED || category == TtsCategory.MODEL_UNAVAILABLE

    companion object {
        fun fromJson(body: String): TtsFailure = try {
            val root = JSONObject(body)
            TtsFailure(
                category = TtsCategory.fromWire(root.optString("category")),
                code = root.optString("error", "unknown"),
                message = root.optString("message").ifBlank { "语音生成失败。" },
                retryAfterSeconds = root.optInt("retryAfterSeconds", 0),
            )
        } catch (_: Exception) {
            TtsFailure(TtsCategory.UNKNOWN_ERROR, "unknown", "语音生成失败。")
        }

        fun fromHttp(status: Int): TtsFailure = when (status) {
            401, 403 -> TtsFailure(TtsCategory.AUTH_ERROR, "http_$status", "设备凭证无效或已过期。")
            402 -> TtsFailure(TtsCategory.MODEL_UNAVAILABLE, "http_$status", "免费语音模型暂不可用。")
            429 -> TtsFailure(TtsCategory.RATE_LIMITED, "http_$status", "请求过于频繁，请稍后再试。")
            in 500..599 -> TtsFailure(TtsCategory.SERVICE_UNAVAILABLE, "http_$status", "语音服务暂不可用。")
            else -> TtsFailure(TtsCategory.UNKNOWN_ERROR, "http_$status", "语音生成失败。")
        }

        fun network(): TtsFailure =
            TtsFailure(TtsCategory.TEMPORARY_NETWORK_ERROR, "network", "网络连接失败，请检查网络。")

        fun cancelled(): TtsFailure =
            TtsFailure(TtsCategory.UNKNOWN_ERROR, "cancelled", "已停止朗读。")
    }
}