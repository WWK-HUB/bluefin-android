package com.wwkhub.bluefin

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var capture: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    @Volatile private var selectedVisible = true
    private var periodic = false
    private var interval = ScreenAwareness.DEFAULT_INTERVAL
    private var active = false
    private var sampling = false
    private var readingForManual = false
    private var request: ((ByteArray?) -> Unit)? = null
    private var analysisClient: ChatCompletionsClient? = null
    private var analysisThread: Thread? = null
    private var periodicEndpoint: ModelEndpoint? = null
    private var lastFingerprint: IntArray? = null
    private var frameCallback: ((Frame?) -> Unit)? = null
    private var receiverRegistered = false
    private val sampleAgain = Runnable { samplePeriodically() }
    private val timeout = Runnable { finishFrame(null) }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            endSession("屏幕已锁定，观察已停止。")
        }
    }
    private val projectionEvents = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { endSession("系统已结束屏幕共享。") }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            resize(width, height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            selectedVisible = isVisible
            if (!isVisible) {
                if (reader != null) closeReader()
                main.post {
                    if (active) {
                        analysisThread?.interrupt()
                        analysisClient?.cancel()
                        lastFingerprint = null
                        ScreenAwareness.clearSummary()
                        ScreenAwareness.status = "共享的应用当前不可见。"
                    }
                }
            }
            if (isVisible && frameCallback != null && reader == null) attachReader()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("bluefin-screen-capture").apply { start() }
        capture = Handler(worker.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (active) endSession("已停止。") else stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val permission = intent?.getParcelableExtra<Intent>(EXTRA_PERMISSION)
        if (intent == null || permission == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        periodic = intent.getBooleanExtra(EXTRA_PERIODIC, false)
        periodicEndpoint = if (periodic) ModelEndpointStore.load(this) else null
        interval = ScreenAwareness.interval(this)
        ScreenAwareness.service = this
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "屏幕观察", NotificationManager.IMPORTANCE_LOW),
            )
            ServiceCompat.startForeground(
                this, NOTIFICATION, notification(),
                if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
            )
            ContextCompat.registerReceiver(
                this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            active = true
            val manager = getSystemService(MediaProjectionManager::class.java)
            val granted = checkNotNull(manager.getMediaProjection(Activity.RESULT_OK, permission))
            projection = granted
            val (width, height) = displaySize()
            val density = resources.displayMetrics.densityDpi
            capture.post {
                val result = runCatching {
                    granted.registerCallback(projectionEvents, capture)
                    val (w, h) = scaledSize(width, height)
                    this.width = w
                    this.height = h
                    display = checkNotNull(granted.createVirtualDisplay(
                        "Bluefin Screen Awareness", w, h, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, capture,
                    ))
                }
                main.post {
                    if (active) {
                        ScreenAwareness.starting = false
                        if (result.isSuccess) {
                            ScreenAwareness.status = if (periodic) "每 ${interval} 秒观察一次。" else "正在获取画面…"
                            if (periodic) main.postDelayed(sampleAgain, SETTLE)
                            ScreenAwareness.onProjectionReady?.invoke(true)
                        } else endSession("无法开始屏幕共享。")
                    }
                }
            }
        } catch (_: Exception) {
            endSession("无法开始屏幕共享。")
        }
        return START_NOT_STICKY
    }

    fun isPeriodic(): Boolean = active && periodic

    fun currentTargetIncludesForeground(): Boolean = selectedVisible

    fun updateInterval(seconds: Int) {
        interval = seconds
        if (isPeriodic()) {
            main.removeCallbacks(sampleAgain)
            main.postDelayed(sampleAgain, seconds * 1_000L)
            if (analysisThread == null) ScreenAwareness.status = "每 ${interval} 秒观察一次。"
        }
    }

    fun captureOnce(callback: (ByteArray?) -> Unit) {
        if (!active || request != null) {
            callback(null)
            return
        }
        request = callback
        if (!sampling) takeFrame(manual = true)
    }

    private fun samplePeriodically() {
        if (!isPeriodic()) return
        main.postDelayed(sampleAgain, interval * 1_000L)
        if (!sampling && analysisThread == null && !ScreenAwareness.reviewVisible) takeFrame(manual = false)
    }

    private fun takeFrame(manual: Boolean) {
        sampling = true
        readingForManual = manual
        if (manual) ScreenAwareness.hideOverlayForCapture?.invoke(true)
        // Allow the consent, settings and overlay surfaces to leave the compositor.
        val (width, height) = displaySize()
        capture.postDelayed({
            if (display == null) {
                main.post { onFrame(null) }
                return@postDelayed
            }
            if (Build.VERSION.SDK_INT < 34) resize(width, height)
            frameCallback = { frame -> main.post { onFrame(frame) }; Unit }
            capture.postDelayed(timeout, 3_500)
            if (selectedVisible) attachReader()
        }, SETTLE)
    }

    private fun onFrame(frame: Frame?) {
        if (!active) return
        sampling = false
        if (readingForManual) ScreenAwareness.hideOverlayForCapture?.invoke(false)
        if (readingForManual) {
            val callback = request
            request = null
            if (!periodic) endSession(if (frame == null) "共享画面不可用。" else "已取得一帧。")
            callback?.invoke(frame?.jpeg)
            return
        }
        if (request != null) takeFrame(manual = true)
        if (ScreenAwareness.reviewVisible) return
        if (frame == null) {
            ScreenAwareness.status = "共享画面暂不可用。"
            return
        }
        if (lastFingerprint?.let { similar(it, frame.fingerprint) } == true) return
        analyze(frame)
    }

    private fun analyze(frame: Frame) {
        val endpoint = ModelEndpointStore.load(this)
        if (endpoint != periodicEndpoint) {
            endSession("模型设置已变化，请重新开启定时观察。")
            return
        }
        val apiKey = ApiKeyVault.read(this)
        if (!EndpointAddressPolicy.isConfigured(endpoint.url, endpoint.model, !apiKey.isNullOrBlank())) {
            ScreenAwareness.status = "请配置支持图片输入的模型。"
            return
        }
        val client = ChatCompletionsClient()
        analysisClient = client
        analysisThread = Thread({
            val result = runCatching { client.observeScreen(endpoint, checkNotNull(apiKey), frame.jpeg) }
            val interrupted = Thread.currentThread().isInterrupted
            main.post {
                if (analysisClient !== client) return@post
                analysisClient = null
                analysisThread = null
                if (!isPeriodic() || interrupted) return@post
                if (ModelEndpointStore.load(this) != endpoint) {
                    endSession("模型设置已变化，请重新开启定时观察。")
                    return@post
                }
                val summary = result.getOrNull()
                if (!summary.isNullOrBlank()) {
                    lastFingerprint = frame.fingerprint
                    ScreenAwareness.recordSummary(summary, endpoint)
                    ScreenAwareness.status = "每 ${interval} 秒观察一次。"
                } else {
                    ScreenAwareness.status = "未取得摘要，请检查模型是否支持图片输入。"
                }
            }
        }, "bluefin-screen-analysis").apply { isDaemon = true; start() }
    }

    private fun attachReader() {
        try {
            val source = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = source
            source.setOnImageAvailableListener({ available ->
                if (available !== reader) return@setOnImageAvailableListener
                val image = runCatching { available.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val result = image.use { runCatching { encode(it) }.getOrNull() }
                if (result != null) finishFrame(result)
            }, capture)
            display?.surface = source.surface
        } catch (_: Exception) {
            finishFrame(null)
        }
    }

    private fun finishFrame(frame: Frame?) {
        capture.removeCallbacks(timeout)
        closeReader()
        val callback = frameCallback
        frameCallback = null
        callback?.invoke(frame)
    }

    private fun closeReader() {
        val source = reader ?: return
        reader = null
        runCatching { display?.surface = null }
        source.close()
    }

    private fun resize(contentWidth: Int, contentHeight: Int) {
        if (contentWidth <= 0 || contentHeight <= 0) return
        val (w, h) = scaledSize(contentWidth, contentHeight)
        if (width == w && height == h) return
        closeReader()
        width = w
        height = h
        val resized = runCatching { display?.resize(w, h, resources.displayMetrics.densityDpi) }.isSuccess
        if (resized && frameCallback != null && selectedVisible) attachReader()
    }

    private data class Frame(val jpeg: ByteArray, val fingerprint: IntArray)

    private fun encode(image: Image): Frame? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val stride = plane.rowStride
        val pixel = plane.pixelStride
        val fingerprint = IntArray(16 * 16)
        var anyColor = false
        for (index in fingerprint.indices) {
            val x = (2 * (index % 16) + 1) * image.width / 32
            val y = (2 * (index / 16) + 1) * image.height / 32
            val offset = y * stride + x * pixel
            val r = buffer.get(offset).toInt() and 255
            val g = buffer.get(offset + 1).toInt() and 255
            val b = buffer.get(offset + 2).toInt() and 255
            anyColor = anyColor || r != 0 || g != 0 || b != 0
            fingerprint[index] = (r * 3 + g * 6 + b) / 10
        }
        if (!anyColor) return null
        val paddedWidth = stride / pixel
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        try {
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            try {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) return null
                return Frame(output.toByteArray(), fingerprint)
            } finally {
                if (bitmap !== padded) bitmap.recycle()
            }
        } finally {
            padded.recycle()
        }
    }

    private fun similar(a: IntArray, b: IntArray): Boolean {
        // Disregard small clock/animation changes while retaining changes to content.
        return a.indices.sumOf { abs(a[it] - b[it]) } <= a.size * 3 &&
            a.indices.count { abs(a[it] - b[it]) >= 24 } <= 3
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> {
        val window = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = window.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        window.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val ratio = minOf(1f, 1280f / maxOf(width, height).coerceAtLeast(1))
        return (width * ratio).roundToInt().coerceAtLeast(1) to
            (height * ratio).roundToInt().coerceAtLeast(1)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle(if (periodic) "蓝鳍正在定时观察屏幕" else "蓝鳍正在看一眼屏幕")
        .setContentText(if (periodic) "点击可调整观察间隔" else "正在获取单张截图")
        .setContentIntent(PendingIntent.getActivity(
            this, 135, ScreenAwarenessActivity.settingsIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
        .addAction(0, "停止观察", PendingIntent.getService(
            this, 136, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    internal fun endSession(status: String) {
        if (ScreenAwareness.service === this) {
            ScreenAwareness.service = null
            ScreenAwareness.starting = false
            ScreenAwareness.status = status
            ScreenAwareness.clearSummary()
            ScreenAwareness.onProjectionReady?.invoke(false)
        }
        if (active) {
            active = false
            main.removeCallbacks(sampleAgain)
            analysisThread?.interrupt()
            analysisClient?.cancel()
            analysisClient = null
            analysisThread = null
            lastFingerprint = null
            if (sampling && readingForManual) ScreenAwareness.hideOverlayForCapture?.invoke(false)
            sampling = false
            val callback = request
            request = null
            callback?.invoke(null)
            val granted = projection
            projection = null
            capture.removeCallbacksAndMessages(null)
            capture.post {
                frameCallback = null
                closeReader()
                display?.release()
                display = null
                granted?.unregisterCallback(projectionEvents)
                granted?.stop()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        endSession("观察已停止。")
        if (receiverRegistered) unregisterReceiver(screenOff)
        worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_PERMISSION = "projection_permission"
        internal const val EXTRA_PERIODIC = "projection_periodic"
        private const val CHANNEL = "screen_capture"
        private const val NOTIFICATION = 202
        private const val ACTION_STOP = "com.wwkhub.bluefin.STOP_SCREEN_CAPTURE"
        private const val SETTLE = 260L
    }
}
