package com.wwkhub.bluefin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wwkhub.bluefin.voice.tts.CloudTtsConfig
import com.wwkhub.bluefin.voice.tts.CloudVoiceDeletion
import com.wwkhub.bluefin.voice.tts.CloudVoiceManager
import com.wwkhub.bluefin.voice.tts.TtsEmotion
import com.wwkhub.bluefin.voice.tts.TtsProviderManager

/** Consent is tied to the exact service and installed pack revision shown in the dialog. */
class VoiceUploadConsentActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = intent.getStringExtra("base").orEmpty()
        val id = intent.getStringExtra("id").orEmpty()
        val revision = intent.getLongExtra("revision", -1)
        val preview = intent.getBooleanExtra("preview", false)
        val pack = VoicePackRepository.find(this, id)
        if (pack == null || !isCurrent(base, id, revision)) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speech_voice_upload_title)
            .setMessage(getString(R.string.speech_voice_upload_message, pack.name, base))
            .setPositiveButton(R.string.speech_voice_upload_confirm) { _, _ ->
                if (isCurrent(base, id, revision) && CloudVoiceManager.grant(this, base, id, revision)) {
                    if (preview) {
                        TtsProviderManager.shared(this).speak(pack, getString(R.string.speech_preview_text), TtsEmotion.NEUTRAL, preview = true)
                    } else {
                        Toast.makeText(this, R.string.speech_voice_upload_granted, Toast.LENGTH_LONG).show()
                    }
                }
                finish()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun isCurrent(base: String, id: String, revision: Long): Boolean =
        base == CloudTtsConfig.baseUrl(this) && !CloudVoiceDeletion.isPending(this, id) &&
            revision == VoicePackRepository.revision(this, id)

    override fun onDestroy() {
        if (isFinishing) showing = false
        super.onDestroy()
    }

    companion object {
        private var showing = false
        private var prompted: String? = null

        internal fun launch(context: Context, base: String, id: String, revision: Long, preview: Boolean): Boolean {
            val key = "$id:$revision:$base"
            if (showing) return true
            if (!preview && prompted == key) return false
            val request = Intent(context, VoiceUploadConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("base", base).putExtra("id", id).putExtra("revision", revision).putExtra("preview", preview)
            return runCatching {
                context.startActivity(request)
                showing = true
                prompted = key
            }.isSuccess
        }
    }
}
