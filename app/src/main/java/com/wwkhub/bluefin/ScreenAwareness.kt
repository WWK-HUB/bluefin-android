package com.wwkhub.bluefin

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** State shared between the consent activity, capture service and chat entry points. */
internal object ScreenAwareness {
    const val DEFAULT_PROMPT = "看看现在的屏幕，告诉我你看到了什么。"
    const val MIN_INTERVAL = 15
    const val MAX_INTERVAL = 3600
    const val DEFAULT_INTERVAL = 30

    var service: ScreenCaptureService? = null
    var starting = false
    var pendingModel: ScreenModel? = null
    private var summaryIsCurrent: (() -> Boolean)? = null
    var latestSummary: String? = null
        private set
    private var observedAt = 0L
    var checkedAt: Long? = null
        private set
    var succeededAt: Long? = null
        private set
    var status = ""
    @Volatile var reviewVisible = false
    var onProjectionReady: ((Boolean) -> Unit)? = null
    var hideOverlayForCapture: ((Boolean) -> Unit)? = null
    var onOverlayReply: ((String, ChatSnapshot) -> Boolean)? = null
    var onOverlayObservation: ((ChatSnapshot, () -> Boolean) -> Unit)? = null

    fun sendScreen(
        context: Context,
        prompt: String,
        jpeg: ByteArray,
        model: ScreenModel,
        draft: ChatDraftCapture?,
        isCurrent: () -> Boolean = { true },
    ): ChatSnapshot? {
        val app = context.applicationContext
        val canSend = { isCurrent() && model.isCurrent(app) }
        if (!canSend()) {
            Toast.makeText(context, ChatFailureText.SCREEN_EXPIRED, Toast.LENGTH_SHORT).show()
            return null
        }
        val session = ChatSession.shared(context)
        val result = session.send(prompt, jpeg, showUserMessage = draft != null) { endpoint, key ->
            model.matches(endpoint, key) && canSend()
        }
        if (result != ChatSendResult.STARTED) {
            val hint = if (result == ChatSendResult.BUSY) "蓝鳍还在回复，请稍后再看屏。"
            else "请先配置支持图片输入的模型。"
            Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
            return null
        }
        if (draft != null) ChatEntry.completeScreenDraft(draft)
        return session.snapshot()
    }

    fun routeReply(context: Context, userText: String?, snapshot: ChatSnapshot, overlay: Boolean, fromChat: Boolean) {
        if (overlay) {
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).post {
                if (onOverlayReply?.invoke(userText.orEmpty(), snapshot) != true) ChatEntry.openRecords(app)
            }
        } else if (!fromChat) {
            ChatEntry.openRecords(context.applicationContext)
        }
    }

    fun recordSummary(text: String, isCurrent: () -> Boolean) {
        latestSummary = text
        summaryIsCurrent = isCurrent
        observedAt = SystemClock.elapsedRealtime()
        succeededAt = System.currentTimeMillis()
    }

    fun markChecked() {
        checkedAt = System.currentTimeMillis()
    }

    fun resetObservation() {
        checkedAt = null
        succeededAt = null
        clearSummary()
    }

    fun summaryFor(text: String): String? {
        if (!recentScreenReference.containsMatchIn(text)) return null
        if (SystemClock.elapsedRealtime() - observedAt > 5 * 60_000L) return null
        return latestSummary?.takeIf { summaryIsCurrent?.invoke() == true }
    }

    fun clearSummary() {
        latestSummary = null
        summaryIsCurrent = null
    }

    private val recentScreenReference = Regex(
        "(刚才|刚刚|上次|现在|最近).{0,12}(屏幕|画面|看到|在看)|(?:屏幕|画面)上.{0,12}(什么|哪)",
    )
    private val lookCommand = Regex(
        "^(?:(?:蓝鳍|鳍遇)[，,：:\\s]*)?(?:请)?(?:帮我|可以帮我|你能)?(?:看看|看一下|看一眼|看)(?:一下|一眼)?(?:我的|我|现在的|当前的|当前|这个)?(?:手机)?(?:屏幕|画面)(?:吧|吗)?(?:[，,。！!？?\\s].*)?$",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun isLookCommand(text: String): Boolean = lookCommand.matches(text.trim())

    fun interval(context: Context): Int = prefs(context).getInt("interval_seconds", DEFAULT_INTERVAL)
        .coerceIn(MIN_INTERVAL, MAX_INTERVAL)

    fun saveInterval(context: Context, seconds: Int) {
        prefs(context).edit().putInt("interval_seconds", seconds).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("screen_awareness", Context.MODE_PRIVATE)
}

/** Keeps the selected destination and credential in memory for the life of a screen request. */
internal class ScreenModel private constructor(val endpoint: ModelEndpoint, private val key: String) {
    fun matches(endpoint: ModelEndpoint, key: String) = this.endpoint == endpoint && this.key == key

    fun isCurrent(context: Context) =
        ApiKeyVault.readForEndpoint(context, endpoint) == key

    fun observe(
        client: ChatCompletionsClient,
        jpeg: ByteArray,
        persona: String,
        lastCommentedScene: String?,
        canSend: () -> Boolean,
    ) = client.observeScreen(endpoint, key, jpeg, persona, lastCommentedScene, canSend)

    companion object {
        fun read(context: Context): ScreenModel? {
            val endpoint = ModelEndpointStore.load(context)
            val key = ApiKeyVault.readForEndpoint(context, endpoint)
            if (key.isNullOrBlank() || !EndpointAddressPolicy.isConfigured(endpoint.url, endpoint.model, true, endpoint.format)) {
                return null
            }
            return ScreenModel(endpoint, key)
        }
    }
}
