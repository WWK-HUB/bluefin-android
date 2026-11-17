package com.wwkhub.bluefin

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.wwkhub.bluefin.voice.tts.OfflineTtsSpec
import com.wwkhub.bluefin.voice.tts.TtsModelManager

/**
 * Settings 「朗读回复」: the on/off switch, the engine download state and the
 * timbre picker.
 *
 * Turning the switch on is not just a preference write: without the engine there
 * is nothing to speak with, so the switch first asks for the one-time download
 * and only flips to on once the files are in place. This keeps the preference
 * from ever claiming a state the app cannot honour.
 */
internal class SpeechSettingsSection(
    private val activity: MainActivity,
    parent: ViewGroup,
) {
    private val prefs = activity.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)

    private val section: View = LayoutInflater.from(activity)
        .inflate(R.layout.section_speech_settings, parent, true)

    private val toggle: SwitchMaterial = section.findViewById(R.id.speech_switch)
    private val engineStatus: TextView = section.findViewById(R.id.speech_engine_status)
    private val engineAction: MaterialButton = section.findViewById(R.id.speech_engine_action)
    private val engineDelete: MaterialButton = section.findViewById(R.id.speech_engine_delete)
    private val voiceStatus: TextView = section.findViewById(R.id.speech_voice_status)
    private val voiceAbout: TextView = section.findViewById(R.id.speech_voice_about)
    private val voicePick: MaterialButton = section.findViewById(R.id.speech_voice_pick)
    private val voiceImport: MaterialButton = section.findViewById(R.id.speech_voice_import)

    private var syncing = false

    init {
        toggle.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            onToggle(checked)
        }
        engineAction.setOnClickListener { activity.promptSpeechEngineDownload { refresh() } }
        engineDelete.setOnClickListener { confirmEngineDelete() }
        voicePick.setOnClickListener { showVoicePicker() }
        voiceImport.setOnClickListener { activity.promptVoicePackImport { refresh() } }
        refresh()
    }

    fun refresh() {
        val installed = TtsModelManager.isInstalled(activity)
        val enabled = BluefinStore.readSpeechOn(prefs)
        syncing = true
        toggle.isChecked = enabled
        syncing = false
        engineStatus.setText(
            if (installed) R.string.speech_engine_ready else R.string.speech_engine_missing,
        )
        engineAction.visibility = if (installed) View.GONE else View.VISIBLE
        engineDelete.visibility = if (installed) View.VISIBLE else View.GONE
        val pack = currentPack()
        if (pack == null) {
            voiceStatus.setText(R.string.speech_voice_empty)
            voiceAbout.text = ""
            voicePick.isEnabled = false
        } else {
            voiceStatus.text = activity.getString(R.string.speech_voice_status, pack.name)
            voiceAbout.text = pack.about
            voicePick.isEnabled = true
        }
    }

    private fun currentPack(): VoicePack? {
        val id = VoicePackRepository.currentId(activity)
        return VoicePackRepository.find(activity, id)
    }

    private fun onToggle(checked: Boolean) {
        if (!checked) {
            BluefinStore.writeSpeechOn(prefs, false)
            activity.onSpeechPreferenceChanged()
            return
        }
        if (!TtsModelManager.isInstalled(activity)) {
            // Revert immediately: the preference only becomes true once the
            // engine is really there.
            syncing = true
            toggle.isChecked = false
            syncing = false
            activity.promptSpeechEngineDownload {
                BluefinStore.writeSpeechOn(prefs, true)
                activity.onSpeechPreferenceChanged()
                refresh()
            }
            return
        }
        BluefinStore.writeSpeechOn(prefs, true)
        activity.onSpeechPreferenceChanged()
        refresh()
    }

    private fun confirmEngineDelete() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.speech_engine_delete_title)
            .setMessage(
                activity.getString(
                    R.string.speech_engine_delete_message,
                    OfflineTtsSpec.DOWNLOAD_SIZE_HINT_MB,
                ),
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.speech_engine_delete) { _, _ ->
                TtsModelManager.delete(activity)
                BluefinStore.writeSpeechOn(prefs, false)
                activity.onSpeechPreferenceChanged()
                refresh()
            }
            .show()
    }

    private fun showVoicePicker() {
        val packs = VoicePackRepository.list(activity)
        if (packs.isEmpty()) {
            refresh()
            return
        }
        val current = VoicePackRepository.currentId(activity)
        val labels = packs.map { pack ->
            if (pack.id == current) "${pack.name}（当前）" else pack.name
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.speech_voice_pick)
            .setItems(labels) { _, which ->
                val pack = packs.getOrNull(which) ?: return@setItems
                VoicePackRepository.setCurrent(activity, pack.id)
                activity.onVoicePackChanged()
                refresh()
            }
            .setNeutralButton(R.string.speech_voice_import) { _, _ ->
                activity.promptVoicePackImport { refresh() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
