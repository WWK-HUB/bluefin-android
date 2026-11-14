package com.wwkhub.bluefin

import android.content.Context
import android.os.SystemClock

/** State shared between the consent activity, capture service and chat entry points. */
internal object ScreenAwareness {
    const val DEFAULT_PROMPT = "看看现在的屏幕，告诉我你看到了什么。"
    const val MIN_INTERVAL = 15
    const val MAX_INTERVAL = 3600
    const val DEFAULT_INTERVAL = 30

    var service: ScreenCaptureService? = null
    var starting = false
    var latestSummary: String? = null
        private set
    private var observedAt = 0L
    private var observedEndpoint: ModelEndpoint? = null
    var status = ""
    var reviewVisible = false
    var onProjectionReady: ((Boolean) -> Unit)? = null
    var hideOverlayForCapture: ((Boolean) -> Unit)? = null
    var onOverlayReply: ((String, ChatSnapshot) -> Boolean)? = null

    fun recordSummary(text: String, endpoint: ModelEndpoint) {
        latestSummary = text
        observedAt = SystemClock.elapsedRealtime()
        observedEndpoint = endpoint
    }

    fun summaryFor(context: Context, text: String): String? {
        if (latestSummary == null || !recentScreenReference.containsMatchIn(text)) return null
        if (SystemClock.elapsedRealtime() - observedAt > 5 * 60_000L) return null
        if (observedEndpoint != ModelEndpointStore.load(context)) return null
        return latestSummary
    }

    fun clearSummary() {
        latestSummary = null
        observedEndpoint = null
    }

    private val recentScreenReference = Regex("(刚才|刚刚|上次).{0,12}(屏幕|画面|看到)")
    private val lookCommand = Regex(
        "^(?:蓝鳍[，,：:\\s]*)?(?:请)?(?:帮我|可以帮我|你能)?(?:看看|看一下|看一眼|看)(?:一下|一眼)?(?:我的|我|现在的|当前的|当前|这个)?(?:手机)?(?:屏幕|画面)(?:吧|吗)?(?:[，,。！!？?\\s].*)?$",
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
