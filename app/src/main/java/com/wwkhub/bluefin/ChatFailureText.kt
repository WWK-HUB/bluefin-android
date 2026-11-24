package com.wwkhub.bluefin

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
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
    const val SCREEN_EXPIRED: String = "截图已失效，请重新发起看屏。"
    const val INVALID_HEADERS = "请检查附加请求头。每行填写「名称: 值」，HTTP 路由和传输字段由应用管理。"
    private const val BILLING_FAILURE = "模型服务报告计费问题（402），请检查当前 API Key 对应的余额、额度或计费设置。"
    private const val AUTH_FAILURE = "API Key 未通过认证（401），请检查密钥及其对应的 API 地址。"
    private const val PERMISSION_FAILURE = "服务拒绝访问（403），请检查 API Key 的权限及所选模型是否已开通。"
    private const val REDIRECT_FAILURE = "API 地址发生跳转，请填写服务提供的最终 API 地址。"
    private const val REQUEST_FAILURE = "服务拒绝了请求参数，请检查接口格式、模型名和高级设置。"
    private const val SIZE_FAILURE = "请求内容超出服务限制，请缩短对话或改用支持较长上下文的模型。"
    private const val MEDIA_FAILURE = "服务不支持请求的内容格式，请检查接口格式或模型是否支持图片。"
    private const val DNS_FAILURE = "找不到 API 主机，请检查地址域名、DNS 和网络。"
    private const val CONNECT_FAILURE = "无法连接 API 主机，请检查网络、地址及端口。"
    private const val UNKNOWN_FAILURE = "模型请求失败，请检查设置后重试。"

    fun forStatus(status: Int): String = when {
        status in 300..399 -> REDIRECT_FAILURE
        status == 400 || status == 422 -> REQUEST_FAILURE
        status == 401 -> AUTH_FAILURE
        status == 402 -> BILLING_FAILURE
        status == 403 -> PERMISSION_FAILURE
        status == 404 || status == 405 -> "找不到模型或地址，请检查模型名和 API 地址。"
        status == 408 -> "服务响应超时，请稍后再试。"
        status == 413 -> SIZE_FAILURE
        status == 415 -> MEDIA_FAILURE
        status == 429 -> "服务忙或限流，请稍后再试。"
        status in 500..599 -> "服务忙或暂时不可用，请稍后再试。"
        else -> "请求失败（HTTP $status），请检查模型设置。"
    }

    fun forThrowable(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is ChatAddressException -> return current.hint
                is ModelResponseException -> return responseHint(current.issue)
                is ChatHttpStatusException -> return forStatus(current.status)
                is ChatServiceException -> return forServiceFailure()
                is SocketTimeoutException -> return "连接超时，请检查 API 地址和网络。"
                is UnknownHostException -> return DNS_FAILURE
                is ConnectException, is NoRouteToHostException -> return CONNECT_FAILURE
                is SSLException, is CertificateException ->
                    return "证书或安全连接出错，请检查 API 地址和网络。"
                is SocketException -> return CONNECT_FAILURE
            }
            current = current.cause
        }
        return UNKNOWN_FAILURE
    }

    private fun responseHint(issue: ModelResponseIssue): String = when (issue) {
        ModelResponseIssue.INVALID -> "服务返回的内容无法识别，请检查 API 地址是否指向模型接口。"
        ModelResponseIssue.FORMAT_MISMATCH -> "服务返回的格式与设置不匹配，请核对接口格式和 API 地址。"
        ModelResponseIssue.EMPTY -> "模型未返回文字，请确认所选模型支持文本对话。"
        ModelResponseIssue.OUTPUT_LIMIT -> "模型达到输出上限，未生成文字，请调高最大输出 token。"
        ModelResponseIssue.FILTERED -> "模型拒绝或过滤了这次回复，请调整输入后重试。"
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
        AUTH_FAILURE,
        PERMISSION_FAILURE,
        BILLING_FAILURE,
        REDIRECT_FAILURE,
        REQUEST_FAILURE,
        SIZE_FAILURE,
        MEDIA_FAILURE,
        DNS_FAILURE,
        CONNECT_FAILURE,
        UNKNOWN_FAILURE,
        INVALID_HEADERS,
        EndpointAddressPolicy.CHAT_PROTOCOL_HINT,
        EndpointAddressPolicy.LEGACY_CHAT_PROTOCOL_HINT,
        EndpointAddressPolicy.MESSAGES_PROTOCOL_HINT,
        EndpointAddressPolicy.CHAT_FRAGMENT_HINT,
        EndpointAddressPolicy.URL_CREDENTIALS_HINT,
        "找不到模型或地址，请检查模型名和 API 地址。",
        "服务响应超时，请稍后再试。",
        "服务忙或限流，请稍后再试。",
        "服务忙或暂时不可用，请稍后再试。",
        "连接超时，请检查 API 地址和网络。",
        "连不上服务，请检查 API 地址和网络。",
        "证书或安全连接出错，请检查 API 地址和网络。",
        "模型没有返回内容，请稍后再试。",
        SERVICE_FAILURE,
        SCREEN_EXPIRED,
    ) + ModelResponseIssue.entries.map(::responseHint)
}
