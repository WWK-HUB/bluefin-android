package com.wwkhub.bluefin

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Maps request failures to short copy that never contains the API key. */
internal object ChatFailureText {
    /** The only reason shown for an unknown service failure. */
    const val SERVICE_FAILURE: String = "服务返回了错误，请稍后再试。"

    /** Safe desktop copy when a cached/older unknown value cannot be allowed. */
    const val BUBBLE_FAILURE: String = "回复失败，请在记录中查看。"

    fun forStatus(status: Int): String = when {
        status == 401 || status == 403 -> "API Key 可能不对，请检查 API Key。"
        status == 404 -> "找不到模型或地址，请检查模型名和 API 地址。"
        status == 408 -> "服务响应超时，请稍后再试。"
        status == 429 -> "服务忙或限流，请稍后再试。"
        status in 500..599 -> "服务忙或暂时不可用，请稍后再试。"
        else -> "请求失败（HTTP $status），请检查模型设置。"
    }

    fun forThrowable(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is SocketTimeoutException -> return "连接超时，请检查 API 地址和网络。"
                is ConnectException, is UnknownHostException ->
                    return "连不上服务，请检查 API 地址和网络。"
                is SSLException, is CertificateException ->
                    return "证书或安全连接出错，请检查 API 地址和网络。"
            }
            current = current.cause
        }
        return "连不上服务，请检查 API 地址和网络。"
    }

    /**
     * Service-provided error text is untrusted: it may contain a key, a full
     * URL or a raw JSON body. Never surface or retain it; use fixed copy.
     */
    fun forServiceFailure(): String = SERVICE_FAILURE

    /**
     * Reduces a previously stored failure reason to known, non-sensitive copy.
     * Anything outside the allowlist (including hostile service text from a
     * persisted older build) becomes [BUBBLE_FAILURE].
     */
    fun forBubble(reason: String?): String {
        val value = reason?.trim().orEmpty()
        if (value.isEmpty()) return BUBBLE_FAILURE
        if (value in SAFE_EXACT) return value
        if (value.startsWith(SAFE_HTTP_PREFIX) &&
            value.endsWith(SAFE_HTTP_SUFFIX) &&
            value.length <= SAFE_HTTP_MAX_LENGTH
        ) {
            val status = value.substring(
                SAFE_HTTP_PREFIX.length,
                value.length - SAFE_HTTP_SUFFIX.length,
            )
            if (status.isNotEmpty() && status.all { it in '0'..'9' }) return value
        }
        return BUBBLE_FAILURE
    }

    fun oneLine(message: String): String =
        message.replace('\n', ' ').replace('\r', ' ').trim().take(160)

    private const val SAFE_HTTP_PREFIX = "请求失败（HTTP "
    private const val SAFE_HTTP_SUFFIX = "），请检查模型设置。"
    private const val SAFE_HTTP_MAX_LENGTH = 48

    private val SAFE_EXACT = setOf(
        "API Key 可能不对，请检查 API Key。",
        "找不到模型或地址，请检查模型名和 API 地址。",
        "服务响应超时，请稍后再试。",
        "服务忙或限流，请稍后再试。",
        "服务忙或暂时不可用，请稍后再试。",
        "连接超时，请检查 API 地址和网络。",
        "连不上服务，请检查 API 地址和网络。",
        "证书或安全连接出错，请检查 API 地址和网络。",
        "模型没有返回内容，请稍后再试。",
        SERVICE_FAILURE,
    )
}
