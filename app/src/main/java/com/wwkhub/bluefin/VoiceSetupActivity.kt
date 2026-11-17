package com.wwkhub.bluefin

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wwkhub.bluefin.voice.VoiceModelManager
import com.wwkhub.bluefin.voice.VoiceModelSpec

/**
 * One-time setup for voice input, reachable from both the chat screen and the
 * overlay (which has no activity of its own): microphone rationale + system
 * permission, then the explicit model-download consent with progress.
 *
 * The result travels back through [VoiceSetupGate], the same singleton-handoff
 * idiom ScreenAwarenessActivity uses for projection consent. A recreation
 * after process death drops the flow silently; the user simply taps again.
 */
class VoiceSetupActivity : AppCompatActivity() {
    private var userCancelled = false
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                fail(getString(R.string.voice_permission_denied))
                return@registerForActivityResult
            }
            ensureModel()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            VoiceSetupGate.take()
            finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.voice_permission_title)
                .setMessage(R.string.voice_permission_message)
                .setNegativeButton(R.string.cancel) { _, _ -> fail(null) }
                .setPositiveButton(R.string.voice_permission_allow) { _, _ ->
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                .setOnCancelListener { fail(null) }
                .show()
            return
        }
        ensureModel()
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroy()
    }

    private fun ensureModel() {
        if (VoiceModelManager.isInstalled(this)) {
            succeed()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.voice_model_title)
            .setMessage(getString(R.string.voice_model_message, VoiceModelSpec.DOWNLOAD_SIZE_HINT_MB))
            .setNegativeButton(R.string.cancel) { _, _ -> fail(null) }
            .setPositiveButton(R.string.voice_model_download) { _, _ -> startDownload() }
            .setOnCancelListener { fail(null) }
            .show()
    }

    private fun startDownload() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(this).apply {
            text = getString(R.string.voice_model_downloading_unknown)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
            addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }
        progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.voice_model_title)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ ->
                userCancelled = true
                VoiceModelManager.cancel()
            }
            .show()
        VoiceModelManager.install(
            this,
            onProgress = { percent ->
                runOnUiThread {
                    if (percent >= 0) {
                        bar.isIndeterminate = false
                        bar.progress = percent
                        label.text = getString(R.string.voice_model_downloading, percent)
                    } else {
                        bar.isIndeterminate = true
                        label.text = getString(R.string.voice_model_extracting)
                    }
                }
            },
            onDone = { error ->
                runOnUiThread {
                    progressDialog?.dismiss()
                    progressDialog = null
                    if (error == null) {
                        succeed()
                    } else {
                        fail(if (userCancelled) null else error)
                    }
                }
            },
        )
    }

    private fun succeed() {
        VoiceSetupGate.take()?.invoke(true)
        finish()
    }

    private fun fail(message: String?) {
        VoiceSetupGate.take()?.invoke(false)
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        fun launch(context: Context, onReady: (Boolean) -> Unit) {
            VoiceSetupGate.pending = onReady
            val intent = Intent(context, VoiceSetupActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

/** One-shot callback handoff into [VoiceSetupActivity]; never carries audio. */
internal object VoiceSetupGate {
    @Volatile
    var pending: ((Boolean) -> Unit)? = null

    fun take(): ((Boolean) -> Unit)? {
        val callback = pending
        pending = null
        return callback
    }
}