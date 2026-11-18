package com.wwkhub.bluefin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wwkhub.bluefin.voice.tts.LocalEngineVerifier
import com.wwkhub.bluefin.voice.tts.OfflineTtsSpec
import com.wwkhub.bluefin.voice.tts.TtsModelManager
import com.wwkhub.bluefin.voice.tts.TtsProviderManager

/**
 * Consent + download + verification flow for the ZipVoice fallback engine.
 *
 * A dedicated translucent activity (the VoiceSetupActivity idiom) so the
 * prompt is visible no matter which screen is in front — main, chat, or the
 * overlay. Nothing downloads before the explicit consent here; declining
 * starts a 24h cooldown so the same dialog never nags reply after reply.
 * Text chat is never blocked by this flow.
 */
class FallbackSetupActivity : AppCompatActivity() {
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null
    private var userCancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            FallbackSetupGate.take()
            finish()
            return
        }
        showing = true
        when {
            LocalEngineVerifier.isReady(this) -> succeed()
            TtsModelManager.isInstalled(this) -> verify()
            else -> consent()
        }
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        showing = false
        super.onDestroy()
    }

    private fun consent() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speech_fallback_title)
            .setMessage(
                getString(
                    R.string.speech_fallback_message,
                    OfflineTtsSpec.DOWNLOAD_SIZE_HINT_MB,
                    OfflineTtsSpec.DOWNLOAD_SIZE_HINT_MB + 10,
                ),
            )
            .setNegativeButton(R.string.cancel) { _, _ -> decline() }
            .setOnCancelListener { decline() }
            .setPositiveButton(R.string.speech_fallback_download) { _, _ -> download() }
            .show()
    }

    private fun decline() {
        TtsProviderManager.shared(this).userDeclinedFallback()
        fail(null)
    }

    private fun download() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(this).apply {
            text = getString(R.string.speech_fallback_downloading, 0)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
            addView(
                label,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(12) },
            )
        }
        progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speech_fallback_title)
            .setView(box)
            .setCancelable(false)
            .setNeutralButton(R.string.speech_fallback_pause) { _, _ ->
                userCancelled = true
                TtsModelManager.pause()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                userCancelled = true
                TtsModelManager.cancel()
            }
            .show()
        TtsModelManager.install(
            this,
            onProgress = { percent ->
                runOnUiThread {
                    bar.isIndeterminate = false
                    bar.progress = percent
                    label.text = getString(R.string.speech_fallback_downloading, percent)
                }
            },
            onDone = { result ->
                runOnUiThread {
                    progressDialog?.dismiss()
                    progressDialog = null
                    when (result) {
                        TtsModelManager.InstallResult.Success -> verify()
                        TtsModelManager.InstallResult.Paused ->
                            fail(getString(R.string.speech_fallback_paused))
                        TtsModelManager.InstallResult.Cancelled -> fail(null)
                        is TtsModelManager.InstallResult.Failed ->
                            fail(if (userCancelled) null else result.message)
                    }
                }
            },
        )
    }

    private fun verify() {
        progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speech_fallback_title)
            .setMessage(R.string.speech_fallback_verifying)
            .setCancelable(false)
            .show()
        LocalEngineVerifier.verify(this) { ok ->
            runOnUiThread {
                progressDialog?.dismiss()
                progressDialog = null
                if (ok) {
                    TtsProviderManager.shared(this).notifyLocalReady()
                    succeed()
                } else {
                    fail(getString(R.string.speech_fallback_verify_failed))
                }
            }
        }
    }

    private fun succeed() {
        FallbackSetupGate.take()?.invoke(true)
        finish()
    }

    private fun fail(message: String?) {
        FallbackSetupGate.take()?.invoke(false)
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        /** True while the flow is on screen; suppresses duplicate prompts. */
        @Volatile
        var showing = false
            private set

        fun launch(context: Context, onReady: (Boolean) -> Unit = {}) {
            if (showing) return
            FallbackSetupGate.pending = onReady
            val intent = Intent(context, FallbackSetupActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

/** One-shot callback handoff into [FallbackSetupActivity]. */
internal object FallbackSetupGate {
    @Volatile
    var pending: ((Boolean) -> Unit)? = null

    fun take(): ((Boolean) -> Unit)? {
        val callback = pending
        pending = null
        return callback
    }
}