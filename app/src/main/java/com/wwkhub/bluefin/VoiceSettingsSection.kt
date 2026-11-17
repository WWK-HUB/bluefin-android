package com.wwkhub.bluefin

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wwkhub.bluefin.voice.VoiceModelManager
import com.wwkhub.bluefin.voice.VoiceModelSpec

/**
 * Settings section for voice input: model state plus download/delete. The
 * download itself reuses [VoiceSetupActivity] (consent + progress), the same
 * flow the mic button triggers on first use.
 */
internal class VoiceSettingsSection(private val activity: Activity, root: View) {
    private val status: TextView = root.findViewById(R.id.voice_model_status)
    private val action: MaterialButton = root.findViewById(R.id.voice_model_action)
    private val delete: MaterialButton = root.findViewById(R.id.voice_model_delete)

    init {
        action.setOnClickListener {
            VoiceSetupActivity.launch(activity) { refresh() }
        }
        delete.setOnClickListener { confirmDelete() }
        refresh()
    }

    fun refresh() {
        val installed = VoiceModelManager.isInstalled(activity)
        status.setText(
            if (installed) R.string.voice_model_status_ready else R.string.voice_model_status_missing,
        )
        action.visibility = if (installed) View.GONE else View.VISIBLE
        delete.visibility = if (installed) View.VISIBLE else View.GONE
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.voice_model_delete_title)
            .setMessage(
                activity.getString(R.string.voice_model_delete_message, VoiceModelSpec.DOWNLOAD_SIZE_HINT_MB),
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.voice_model_delete) { _, _ ->
                VoiceModelManager.delete(activity)
                refresh()
            }
            .show()
    }
}