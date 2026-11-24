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
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wwkhub.bluefin.voice.tts.TtsReader
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var capture: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    // Capture-thread state. Keep a surface attached during timed observation
    // so Android can resume mirroring when the selected task comes back.
    private var periodicReader = false
    private var latestImage: Image? = null
    private var width = 0
    private var height = 0
    private val scope = ScreenCaptureScope()
    private var ending = false
    private var periodic = false
    private var interval = ScreenAwareness.DEFAULT_INTERVAL
    private var active = false
    private var sampling = false
    private var readingForManual = false
    private data class ManualRequest(val permit: Long, val callback: (CapturedScreen?) -> Unit)
    private var request: ManualRequest? = null
    private var analysisClient: ChatCompletionsClient? = null
    private var analysisThread: Thread? = null
    private var periodicModel: ScreenModel? = null
    private var lastAnalyzedFrame: ByteArray? = null
    private var lastComment: ScreenObservation? = null
    private val observationVoiceOwner = Any()
    private var frameCallback: ((Frame?) -> Unit)? = null
    private var receiverRegistered = false
    private val sampleAgain = Runnable { samplePeriodically() }
    private val expireManual = Runnable { endSession("屏幕共享已因闲置结束。") }
    private val timeout = Runnable { finishFrame(null) }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            endSession("屏幕已锁定，观察已停止。")
        }
    }
    private val projectionEvents = object : MediaProjection.Callback() {
        override fun onStop() {
            scope.stop()
            main.post { endSession("系统已结束屏幕共享。") }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            resize(width, height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            val wasVisible = scope.permit() != null
            scope.setVisible(isVisible)
            if (!isVisible) {
                discardLatestImage()
                finishFrame(null)
                main.post {
                    if (active) {
                        analysisThread?.interrupt()
                        analysisClient?.cancel()
                        TtsReader.stopScreenReading(observationVoiceOwner)
                        ScreenAwareness.status = "已暂停 · 共享应用当前不可见。"
                    }
                }
            } else if (!wasVisible) {
                main.post {
                    if (isPeriodic() && scope.permit() != null && !ScreenAwareness.reviewVisible) {
                        ScreenAwareness.status = runningStatus()
                        scheduleSample(SETTLE)
                    }
                }
            }
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
        if (active || ending) return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val permission = intent?.getParcelableExtra<Intent>(EXTRA_PERMISSION)
        if (intent == null || permission == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        periodic = intent.getBooleanExtra(EXTRA_PERIODIC, false)
        periodicModel = if (periodic) ScreenAwareness.pendingModel else null
        val approvedModel = ScreenAwareness.pendingModel
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
            if (approvedModel?.isCurrent(this) != true) {
                endSession("模型设置已变化，请重新开启屏幕共享。")
                return START_NOT_STICKY
            }
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
                            ScreenAwareness.status = if (periodic) runningStatus() else "正在获取画面…"
                            if (periodic) {
                                ScreenAwareness.resetObservation()
                                enablePeriodicReader()
                                scheduleSample(SETTLE)
                            }
                            else renewManualSession()
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

    fun currentTargetIncludesForeground(): Boolean = active && scope.permit() != null

    private fun canUse(permit: Long): Boolean =
        scope.allows(permit) && getSystemService(PowerManager::class.java).isInteractive

    internal fun startPeriodic(model: ScreenModel): Boolean {
        if (!active || periodic || ScreenAwareness.starting || !model.isCurrent(this)) return false
        periodic = true
        periodicModel = model
        main.removeCallbacks(expireManual)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        ScreenAwareness.resetObservation()
        ScreenAwareness.status = runningStatus()
        enablePeriodicReader()
        scheduleSample(SETTLE)
        return true
    }

    private fun renewManualSession() {
        main.removeCallbacks(expireManual)
        main.postDelayed(expireManual, MANUAL_IDLE_TIMEOUT)
    }

    fun updateInterval(seconds: Int) {
        if (interval == seconds) return
        interval = seconds
        if (isPeriodic()) {
            scheduleSample(seconds * 1_000L)
            if (analysisThread == null && currentTargetIncludesForeground()) ScreenAwareness.status = runningStatus()
        }
    }

    private fun scheduleSample(delay: Long) {
        main.removeCallbacks(sampleAgain)
        main.postDelayed(sampleAgain, delay)
    }

    private fun runningStatus() = "观察中 · 每 ${interval} 秒检查一次。"

    private fun enablePeriodicReader() {
        capture.post {
            periodicReader = true
            if (reader == null) attachReader()
        }
    }

    internal fun captureOnce(callback: (CapturedScreen?) -> Unit) {
        val permit = scope.permit()
        if (!active || request != null || permit == null || !canUse(permit)) {
            callback(null)
            return
        }
        request = ManualRequest(permit, callback)
        if (!sampling) takeFrame(permit, manual = true)
    }

    private fun samplePeriodically() {
        if (!isPeriodic()) return
        if (periodicModel?.isCurrent(this) != true) {
            endSession("模型设置已变化，请重新开启定时观察。")
            return
        }
        scheduleSample(interval * 1_000L)
        val permit = scope.permit() ?: return
        if (canUse(permit) && !sampling && analysisThread == null && !ScreenAwareness.reviewVisible &&
            ChatSession.peek()?.hasActiveWorker() != true
        ) {
            ScreenAwareness.markChecked()
            ScreenAwareness.status = "正在读取共享画面…"
            takeFrame(permit, manual = false)
        }
    }

    private fun takeFrame(permit: Long, manual: Boolean) {
        sampling = true
        readingForManual = manual
        if (manual) ScreenAwareness.hideOverlayForCapture?.invoke(true)
        // Allow the consent, settings and overlay surfaces to leave the compositor.
        val (width, height) = displaySize()
        capture.postDelayed({
            if (display == null || !canUse(permit)) {
                main.post { onFrame(permit, null) }
                return@postDelayed
            }
            if (Build.VERSION.SDK_INT < 34) resize(width, height)
            frameCallback = { frame -> main.post { onFrame(permit, frame) }; Unit }
            capture.postDelayed(timeout, 3_500)
            if (reader == null) attachReader()
            latestImage?.let { image ->
                runCatching { encode(image) }.getOrNull()?.let(::finishFrame)
            }
        }, SETTLE)
    }

    private fun onFrame(permit: Long, frame: Frame?) {
        if (!active) return
        sampling = false
        val currentFrame = frame?.takeIf { canUse(permit) }
        if (readingForManual) ScreenAwareness.hideOverlayForCapture?.invoke(false)
        if (readingForManual) {
            val manual = request
            request = null
            if (!periodic && canUse(permit)) {
                renewManualSession()
                ScreenAwareness.status = "屏幕共享已就绪，仅在你主动请求时看屏。"
            }
            manual?.callback?.invoke(currentFrame?.jpeg?.let { CapturedScreen(it) { canUse(permit) } })
            return
        }
        request?.let {
            takeFrame(it.permit, manual = true)
            return
        }
        if (ScreenAwareness.reviewVisible || !canUse(permit)) return
        if (currentFrame == null) {
            ScreenAwareness.status = "本次未取得画面，下个周期会重试。"
            return
        }
        analyze(permit, currentFrame)
    }

    private fun analyze(permit: Long, frame: Frame) {
        if (lastAnalyzedFrame?.contentEquals(frame.digest) == true) {
            ScreenAwareness.status = runningStatus()
            return
        }
        val model = periodicModel
        if (model == null || !model.isCurrent(this)) {
            endSession("模型设置已变化，请重新开启定时观察。")
            return
        }
        val session = ChatSession.shared(this)
        val generation = session.idleGeneration()
        if (generation == null) {
            ScreenAwareness.status = runningStatus()
            return
        }
        val client = ChatCompletionsClient()
        val canSend = { canUse(permit) && !ScreenAwareness.reviewVisible && model.isCurrent(this) }
        val previous = lastComment
        ScreenAwareness.status = "正在分析画面…"
        analysisClient = client
        analysisThread = Thread({
            val result = runCatching {
                model.observe(client, frame.jpeg, PersonaRepository.read(this), previous?.summary, canSend)
            }
            val interrupted = Thread.currentThread().isInterrupted
            main.post {
                if (analysisClient !== client) return@post
                analysisClient = null
                analysisThread = null
                if (!isPeriodic() || interrupted || !canUse(permit)) return@post
                if (!model.isCurrent(this)) {
                    endSession("模型设置已变化，请重新开启定时观察。")
                    return@post
                }
                if (ScreenAwareness.reviewVisible) {
                    ScreenAwareness.status = runningStatus()
                    return@post
                }
                val observation = result.getOrNull()
                if (observation != null) {
                    ScreenAwareness.recordSummary(observation.summary) { model.isCurrent(this) && canUse(permit) }
                    val remark = observation.remark
                    if (remark == null || remark == previous?.remark) {
                        lastAnalyzedFrame = frame.digest
                    } else if (canSend()) {
                        session.recordScreenRemark(observation, generation)?.let { snapshot ->
                            lastComment = observation
                            lastAnalyzedFrame = frame.digest
                            ScreenAwareness.onOverlayObservation?.invoke(snapshot, canSend)
                            if (canSend()) {
                                TtsReader.readCompletedReply(this, remark, observation.emotion, observationVoiceOwner)
                            }
                        }
                    }
                    ScreenAwareness.status = runningStatus()
                } else {
                    ScreenAwareness.status = when (val error = result.exceptionOrNull()) {
                        null -> "未取得摘要，请检查模型是否支持图片输入。"
                        is ChatHttpStatusException -> if (error.status in setOf(400, 415, 422)) {
                            "模型服务拒绝了截图，请检查当前模型是否支持图片输入。"
                        } else ChatFailureText.forStatus(error.status)
                        is ChatServiceException -> ChatFailureText.forServiceFailure()
                        else -> ChatFailureText.forThrowable(error)
                    }
                }
            }
        }, "bluefin-screen-analysis").apply { isDaemon = true; start() }
    }

    private fun attachReader() {
        try {
            val source = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, if (periodicReader) 3 else 2)
            reader = source
            source.setOnImageAvailableListener({ available ->
                if (available !== reader) return@setOnImageAvailableListener
                val image = runCatching { available.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                if (scope.permit() == null) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                val result = if (periodicReader) {
                    discardLatestImage()
                    latestImage = image
                    if (frameCallback == null) null else runCatching { encode(image) }.getOrNull()
                } else image.use { runCatching { encode(it) }.getOrNull() }
                if (result != null) finishFrame(result)
            }, capture)
            display?.surface = source.surface
        } catch (_: Exception) {
            closeReader()
            finishFrame(null)
        }
    }

    private fun finishFrame(frame: Frame?) {
        capture.removeCallbacks(timeout)
        if (!periodicReader) closeReader()
        val callback = frameCallback
        frameCallback = null
        callback?.invoke(frame)
    }

    private fun closeReader() {
        discardLatestImage()
        val source = reader ?: return
        reader = null
        runCatching { display?.surface = null }
        source.close()
    }

    private fun discardLatestImage() {
        latestImage?.close()
        latestImage = null
    }

    private fun resize(contentWidth: Int, contentHeight: Int) {
        if (contentWidth <= 0 || contentHeight <= 0) return
        val (w, h) = scaledSize(contentWidth, contentHeight)
        if (width == w && height == h) return
        closeReader()
        width = w
        height = h
        val resized = runCatching { display?.resize(w, h, resources.displayMetrics.densityDpi) }.isSuccess
        if (resized && (periodicReader || frameCallback != null && scope.permit() != null)) attachReader()
    }

    private data class Frame(val jpeg: ByteArray, val digest: ByteArray)

    private fun encode(image: Image): Frame? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val stride = plane.rowStride
        val pixel = plane.pixelStride
        var anyColor = false
        for (index in 0 until 16 * 16) {
            val x = (2 * (index % 16) + 1) * image.width / 32
            val y = (2 * (index / 16) + 1) * image.height / 32
            val offset = y * stride + x * pixel
            val r = buffer.get(offset).toInt() and 255
            val g = buffer.get(offset + 1).toInt() and 255
            val b = buffer.get(offset + 2).toInt() and 255
            if (r != 0 || g != 0 || b != 0) {
                anyColor = true
                break
            }
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
                val jpeg = output.toByteArray()
                return Frame(jpeg, MessageDigest.getInstance("SHA-256").digest(jpeg))
            } finally {
                if (bitmap !== padded) bitmap.recycle()
            }
        } finally {
            padded.recycle()
        }
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
        .setContentTitle(if (periodic) "蓝鳍正在持续看屏" else "蓝鳍屏幕共享已开启")
        .setContentText(if (periodic) "点击可调整观察间隔" else "仅在你主动请求时取一帧")
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
        if (ending) return
        ending = true
        scope.stop()
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
            main.removeCallbacks(expireManual)
            analysisThread?.interrupt()
            analysisClient?.cancel()
            TtsReader.stopScreenReading(observationVoiceOwner)
            analysisClient = null
            analysisThread = null
            periodicModel = null
            if (sampling && readingForManual) ScreenAwareness.hideOverlayForCapture?.invoke(false)
            sampling = false
            val callback = request
            request = null
            callback?.callback?.invoke(null)
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
        private const val MANUAL_IDLE_TIMEOUT = 10 * 60_000L
    }
}
