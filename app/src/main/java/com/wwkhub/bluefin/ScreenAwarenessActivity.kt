package com.wwkhub.bluefin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class ScreenAwarenessActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private var periodicRequest = false
    private var ownsConsent = false
    private var prompt = ScreenAwareness.DEFAULT_PROMPT
    private val statusRefresh = object : Runnable {
        override fun run() {
            val status = dialog?.findViewById<TextView>(STATUS_ID) ?: return
            status.text = statusText()
            dialog?.findViewById<MaterialSwitch>(SWITCH_ID)?.isChecked =
                ScreenAwareness.service?.isPeriodic() == true
            handler.postDelayed(this, 1_000)
        }
    }
    private val projectionReady: (Boolean) -> Unit = { ready ->
        ScreenAwareness.onProjectionReady = null
        ownsConsent = false
        if (!ready) fail(ScreenAwareness.status)
        else if (periodicRequest) finish()
        else captureOnce()
    }
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            ScreenAwareness.starting = false
            ownsConsent = false
            fail("已取消屏幕共享。")
        } else {
            ScreenAwareness.onProjectionReady = projectionReady
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ScreenCaptureService::class.java)
                        .putExtra(ScreenCaptureService.EXTRA_PERMISSION, data)
                        .putExtra(ScreenCaptureService.EXTRA_PERIODIC, periodicRequest),
                )
            } catch (_: Exception) {
                ScreenAwareness.onProjectionReady = null
                ScreenAwareness.starting = false
                ownsConsent = false
                fail("无法开始屏幕共享，请重试。")
            }
        }
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) finish()
        else Thread({
            val jpeg = runCatching { readPickedImage(uri) }.getOrNull()
            handler.post {
                if (!isFinishing && !isDestroyed) {
                    if (jpeg == null) fail("无法读取这张截图。") else showPreview(jpeg)
                }
            }
        }, "bluefin-screen-picker").start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prompt = intent.getStringExtra(EXTRA_PROMPT)?.take(4000)?.ifBlank { null } ?: ScreenAwareness.DEFAULT_PROMPT
        periodicRequest = savedInstanceState?.getBoolean(STATE_PERIODIC) ?: false
        ownsConsent = savedInstanceState?.getBoolean(STATE_CONSENT) ?: false
        if (savedInstanceState != null && ownsConsent) {
            if (ScreenAwareness.onProjectionReady != null) ScreenAwareness.onProjectionReady = projectionReady
            return
        }
        when (intent.action) {
            ACTION_LOOK -> requestLook()
            else -> showSettings()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PERIODIC, periodicRequest)
        outState.putBoolean(STATE_CONSENT, ownsConsent)
        super.onSaveInstanceState(outState)
    }

    private fun requestLook() {
        if (!checkModel()) return
        val current = ScreenAwareness.service
        if (current?.isPeriodic() == true && !ScreenAwareness.starting) {
            if (current.currentTargetIncludesForeground()) captureOnce()
            else fail("定时观察共享的应用当前不可见。请切回该应用，或停止定时观察后重新选择共享范围。")
        } else requestProjection(periodic = false)
    }

    private fun captureOnce() {
        val service = ScreenAwareness.service
        if (service == null) {
            fail("屏幕共享已结束，请重试。")
            return
        }
        service.captureOnce { jpeg ->
            if (!isFinishing && !isDestroyed) {
                if (jpeg == null) fail("当前画面不可用。可以改用「选截图」。")
                else showPreview(jpeg)
            }
        }
    }

    private fun requestProjection(periodic: Boolean) {
        if (!checkModel()) return
        if (ScreenAwareness.starting || ScreenAwareness.service != null) {
            fail("已有屏幕共享请求，请稍后再试。")
            return
        }
        periodicRequest = periodic
        ScreenAwareness.starting = true
        ownsConsent = true
        try {
            consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } catch (_: Exception) {
            ScreenAwareness.starting = false
            ownsConsent = false
            fail("设备暂时无法共享屏幕。可以改用「选截图」。")
        }
    }

    private fun showSettings() {
        val toggle = MaterialSwitch(this).apply {
            id = SWITCH_ID
            text = "定时观察"
            isChecked = ScreenAwareness.service?.isPeriodic() == true
        }
        val status = TextView(this).apply {
            id = STATUS_ID
            text = statusText()
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(ScreenAwareness.interval(this@ScreenAwarenessActivity).toString())
        }
        val intervalField = TextInputLayout(this).apply {
            hint = "观察间隔（秒，15–3600）"
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val content = column()
        content.addView(toggle)
        content.addView(intervalField)
        content.addView(TextView(this).apply {
            text = "开启后，画面变化时会把截图发送给当前配置的模型。请只共享你愿意提供给模型的窗口或屏幕。摘要仅在本次共享期间可用。"
            setPadding(0, dp(12), 0, dp(8))
        })
        content.addView(status)
        content.isFocusableInTouchMode = true
        content.requestFocus()
        val settings = MaterialAlertDialogBuilder(this)
            .setTitle("屏幕观察")
            .setView(content)
            .setPositiveButton("保存间隔", null)
            .setNeutralButton("选截图") { _, _ -> pickImage.launch("image/*") }
            .setNegativeButton("关闭") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        fun saveInterval(): Boolean {
            val seconds = input.text?.toString()?.toIntOrNull()
            if (seconds == null || seconds !in ScreenAwareness.MIN_INTERVAL..ScreenAwareness.MAX_INTERVAL) {
                intervalField.error = "请输入 15 到 3600 之间的秒数"
                return false
            }
            intervalField.error = null
            ScreenAwareness.saveInterval(this, seconds)
            ScreenAwareness.service?.updateInterval(seconds)
            return true
        }
        toggle.setOnClickListener {
            if (!toggle.isChecked) {
                ScreenAwareness.service?.endSession("已关闭定时观察。")
                status.text = statusText()
            } else if (saveInterval()) {
                settings.dismiss()
                ScreenAwareness.reviewVisible = false
                requestProjection(periodic = true)
            } else {
                toggle.isChecked = false
            }
        }
        dialog = settings
        settings.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings.show()
        ScreenAwareness.reviewVisible = true
        settings.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (saveInterval()) {
                Toast.makeText(this, "观察间隔已保存。", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        handler.post(statusRefresh)
    }

    private fun showPreview(jpeg: ByteArray) {
        val context = this
        val replyInOverlay = intent.getBooleanExtra(EXTRA_OVERLAY, false)
        val fromChat = intent.getBooleanExtra(EXTRA_FROM_CHAT, false)
        val endpoint = ModelEndpointStore.load(this)
        val preview = ImageView(this).apply {
            contentDescription = "本次要发送的截图"
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        }
        val content = column()
        content.addView(TextView(this).apply {
            text = "这张截图将发送给当前模型 ${endpoint.model}。"
            setPadding(0, 0, 0, dp(12))
        })
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        val review = MaterialAlertDialogBuilder(this)
            .setTitle("发送截图给蓝鳍")
            .setView(content)
            .setPositiveButton("发送", null)
            .setNegativeButton("取消") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog = review
        review.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        review.show()
        ScreenAwareness.reviewVisible = true
        review.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (ModelEndpointStore.load(this) != endpoint) {
                fail("模型设置已变化，请重新选择截图。")
                return@setOnClickListener
            }
            val session = ChatSession.shared(context)
            val result = session.send(prompt, jpeg)
            if (result == ChatSendResult.STARTED) {
                val command = intent.getStringExtra(EXTRA_PROMPT)
                val revision = intent.getLongExtra(EXTRA_REVISION, -1L)
                if (command != null && revision >= 0L) {
                    ChatEntry.completeScreenDraft(ChatDraftCapture(revision, command))
                }
                val snapshot = session.snapshot()
                finish()
                if (replyInOverlay) {
                    val app = applicationContext
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (ScreenAwareness.onOverlayReply?.invoke(prompt, snapshot) != true) {
                            ChatEntry.openRecords(app)
                        }
                    }, 350)
                } else if (!fromChat) {
                    ChatEntry.openRecords(context.applicationContext)
                }
            } else {
                val hint = if (result == ChatSendResult.BUSY) "蓝鳍还在回复，请稍后再发送。" else "请先配置支持图片输入的模型。"
                Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun statusText(): String = listOfNotNull(
        ScreenAwareness.status.ifBlank { "定时观察已关闭。" },
        ScreenAwareness.latestSummary?.let { "最近看到\n$it" },
    ).joinToString("\n\n")

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(4))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun checkModel(): Boolean {
        if (ModelEndpointStore.isReady(this)) return true
        fail("请先在模型设置中配置支持图片输入的模型。")
        return false
    }

    private fun fail(text: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun readPickedImage(uri: android.net.Uri): ByteArray? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1280) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        return try {
            java.io.ByteArrayOutputStream().let { if (bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it)) it.toByteArray() else null }
        } finally {
            bitmap.recycle()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        ScreenAwareness.reviewVisible = false
        if (ScreenAwareness.onProjectionReady === projectionReady) {
            ScreenAwareness.onProjectionReady = null
            ScreenAwareness.service?.endSession("观察已取消。")
        }
        if (ownsConsent && isFinishing) ScreenAwareness.starting = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_LOOK = "look"
        private const val EXTRA_PROMPT = "screen_prompt"
        private const val EXTRA_OVERLAY = "overlay_entry"
        private const val EXTRA_REVISION = "draft_revision"
        private const val EXTRA_FROM_CHAT = "from_chat"
        private const val STATE_PERIODIC = "periodic"
        private const val STATE_CONSENT = "consent"
        private const val STATUS_ID = 0x00ff0001
        private const val SWITCH_ID = 0x00ff0002

        internal fun look(
            context: Context,
            draft: ChatDraftCapture? = null,
            overlay: Boolean = false,
            fromChat: Boolean = false,
        ) {
            context.startActivity(settingsIntent(context, overlay).setAction(ACTION_LOOK)
                .putExtra(EXTRA_PROMPT, draft?.text).putExtra(EXTRA_REVISION, draft?.revision ?: -1L)
                .putExtra(EXTRA_FROM_CHAT, fromChat))
        }

        internal fun settingsIntent(context: Context, overlay: Boolean = false): Intent =
            Intent(context, ScreenAwarenessActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(EXTRA_OVERLAY, overlay)
    }
}
