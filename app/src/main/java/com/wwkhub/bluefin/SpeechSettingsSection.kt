package com.wwkhub.bluefin

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.wwkhub.bluefin.voice.tts.CloudTtsConfig
import android.content.SharedPreferences
import com.wwkhub.bluefin.voice.tts.CloudVoiceDeletion
import com.wwkhub.bluefin.voice.tts.EngineState
import com.wwkhub.bluefin.voice.tts.LocalEngineVerifier
import com.wwkhub.bluefin.voice.tts.OfflineTtsSpec
import com.wwkhub.bluefin.voice.tts.TtsCache
import com.wwkhub.bluefin.voice.tts.TtsEmotion
import com.wwkhub.bluefin.voice.tts.TtsModelManager
import com.wwkhub.bluefin.voice.tts.TtsProviderManager
import java.util.Locale

/**
 * Settings 「朗读回复」: cloud-first speech with an on-demand local fallback.
 *
 * The normal path shows only the cloud engine, the switches, the timbre picker
 * and cache management. ZipVoice download/delete controls stay hidden inside
 * 「离线备用设置」 and surface automatically when the provider has fallen back
 * to local — ordinary users are never asked to prepare a local engine.
 */
internal class SpeechSettingsSection(
    private val activity: MainActivity,
    parent: ViewGroup,
) {
    private val prefs = activity.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
    private val manager = TtsProviderManager.shared(activity)
    private val cache = TtsCache(activity)

    private val section: View = LayoutInflater.from(activity)
        .inflate(R.layout.section_speech_settings, parent, true)

    private val engineStatus: TextView = section.findViewById(R.id.speech_engine_status)
    private val toggle: SwitchMaterial = section.findViewById(R.id.speech_switch)
    private val autoToggle: SwitchMaterial = section.findViewById(R.id.speech_auto_switch)
    private val speedSlider: SeekBar = section.findViewById(R.id.speech_speed_slider)
    private val speedValue: TextView = section.findViewById(R.id.speech_speed_value)
    private val voiceStatus: TextView = section.findViewById(R.id.speech_voice_status)
    private val voiceAbout: TextView = section.findViewById(R.id.speech_voice_about)
    private val voicePick: MaterialButton = section.findViewById(R.id.speech_voice_pick)
    private val voiceImport: MaterialButton = section.findViewById(R.id.speech_voice_import)
    private val voiceCreate: MaterialButton = section.findViewById(R.id.speech_voice_create)
    private val voiceManage: MaterialButton = section.findViewById(R.id.speech_voice_manage)
    private val deletionStatus: TextView = section.findViewById(R.id.speech_voice_deletion_status)
    private val voicePreview: MaterialButton = section.findViewById(R.id.speech_voice_preview)
    private val cacheStatus: TextView = section.findViewById(R.id.speech_cache_status)
    private val cacheClear: MaterialButton = section.findViewById(R.id.speech_cache_clear)
    private val offlineToggle: MaterialButton = section.findViewById(R.id.speech_offline_toggle)
    private val offlinePanel: View = section.findViewById(R.id.speech_offline_panel)
    private val localStatus: TextView = section.findViewById(R.id.speech_local_status)
    private val localDownload: MaterialButton = section.findViewById(R.id.speech_local_download)
    private val localDelete: MaterialButton = section.findViewById(R.id.speech_local_delete)
    private val cloudRetry: MaterialButton = section.findViewById(R.id.speech_cloud_retry)
    private val cloudAddr: TextView = section.findViewById(R.id.speech_cloud_addr)
    private val cloudEdit: MaterialButton = section.findViewById(R.id.speech_cloud_edit)

    private var syncing = false
    private var offlineExpanded = false
    private val stateListener = { refresh() }
    private val deletionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("pending:") == true) refreshDeletionStatus()
    }

    init {
        manager.addListener(stateListener)
        CloudVoiceDeletion.prefs(activity).registerOnSharedPreferenceChangeListener(deletionListener)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            BluefinStore.writeSpeechOn(prefs, checked)
            if (!checked) manager.onSpeechDisabled()
            activity.onSpeechPreferenceChanged()
            refresh()
        }
        autoToggle.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            BluefinStore.writeAutoSpeech(prefs, checked)
        }
        speedSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val speed = 0.5f + progress / 100f
                BluefinStore.writeSpeechSpeed(prefs, speed)
                speedValue.text = String.format(Locale.US, "%.1f×", speed)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        voicePick.setOnClickListener { showVoicePicker() }
        voiceImport.setOnClickListener { activity.promptVoicePackImport { refresh() } }
        voiceCreate.setOnClickListener { activity.openVoicePackCreator() }
        voiceManage.setOnClickListener { showVoicePackManager() }
        voicePreview.setOnClickListener { previewVoice() }
        bindDisclosure(R.id.speech_voice_actions_toggle, R.id.speech_voice_actions)
        bindDisclosure(R.id.speech_service_options_toggle, R.id.speech_service_options)
        cacheClear.setOnClickListener {
            cache.clear()
            Toast.makeText(activity, R.string.speech_cache_cleared, Toast.LENGTH_SHORT).show()
            refresh()
        }
        offlineToggle.setOnClickListener {
            offlineExpanded = !offlineExpanded
            refresh()
        }
        localDownload.setOnClickListener {
            FallbackSetupActivity.launch(activity) { refresh() }
        }
        localDelete.setOnClickListener { confirmLocalDelete() }
        cloudRetry.setOnClickListener {
            manager.retryCloudNow()
            refresh()
        }
        cloudEdit.setOnClickListener { showCloudAddressEditor() }
        refresh()
    }

    private fun bindDisclosure(toggle: Int, panel: Int) {
        val button = section.findViewById<MaterialButton>(toggle)
        val content = section.findViewById<View>(panel)
        fun setExpanded(expanded: Boolean) {
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            ViewCompat.setStateDescription(button, activity.getString(
                if (expanded) R.string.settings_expanded else R.string.settings_collapsed,
            ))
        }
        button.setOnClickListener { setExpanded(content.visibility != View.VISIBLE) }
        setExpanded(false)
    }

    /** Unregisters listeners when the settings surface closes. */
    fun dispose() {
        manager.removeListener(stateListener)
        CloudVoiceDeletion.prefs(activity).unregisterOnSharedPreferenceChangeListener(deletionListener)
    }

    fun refresh() {
        refreshDeletionStatus()
        val state = manager.currentState()
        val configured = CloudTtsConfig.isConfigured(activity)
        engineStatus.setText(
            when {
                state == EngineState.LOCAL_FALLBACK -> R.string.speech_engine_local
                configured -> R.string.speech_engine_cloud
                else -> R.string.speech_engine_unconfigured
            },
        )
        syncing = true
        toggle.isChecked = BluefinStore.readSpeechOn(prefs)
        autoToggle.isChecked = BluefinStore.readAutoSpeech(prefs)
        val speed = BluefinStore.readSpeechSpeed(prefs)
        speedSlider.progress = ((speed - 0.5f) * 100).toInt().coerceIn(0, 150)
        speedValue.text = String.format(Locale.US, "%.1f×", speed)
        syncing = false

        val pack = currentPack()
        if (pack == null) {
            voiceStatus.setText(R.string.speech_voice_empty)
            voiceAbout.text = ""
            voicePick.isEnabled = false
            voicePreview.isEnabled = false
        } else {
            voiceStatus.text = activity.getString(R.string.speech_voice_status, pack.name)
            voiceAbout.text = pack.about
            voicePick.isEnabled = true
            voicePreview.isEnabled = true
        }
        voiceAbout.visibility = if (voiceAbout.text.isBlank()) View.GONE else View.VISIBLE

        cacheStatus.text = activity.getString(
            R.string.speech_cache_status,
            android.text.format.Formatter.formatFileSize(activity, cache.sizeBytes()),
        )

        val installed = TtsModelManager.isInstalled(activity)
        val verified = LocalEngineVerifier.isReady(activity)
        val showPanel = offlineExpanded || state == EngineState.LOCAL_FALLBACK || installed
        offlinePanel.visibility = if (showPanel) View.VISIBLE else View.GONE
        localStatus.text = when {
            installed && verified -> activity.getString(R.string.speech_local_status_ready)
            installed -> activity.getString(R.string.speech_local_status_unverified)
            TtsModelManager.hasPartialDownload(activity) -> activity.getString(R.string.speech_local_status_paused)
            else -> activity.getString(R.string.speech_local_status_missing, OfflineTtsSpec.DOWNLOAD_SIZE_HINT_MB)
        }
        localDownload.setText(
            when {
                installed && !verified -> R.string.speech_local_verify
                TtsModelManager.hasPartialDownload(activity) -> R.string.speech_local_resume
                else -> R.string.speech_local_download
            },
        )
        localDownload.visibility = if (installed && verified) View.GONE else View.VISIBLE
        localDelete.visibility = if (installed) View.VISIBLE else View.GONE
        cloudRetry.visibility = if (state == EngineState.LOCAL_FALLBACK) View.VISIBLE else View.GONE

        val base = CloudTtsConfig.baseUrl(activity)
        cloudAddr.text = activity.getString(
            if (base.isEmpty()) R.string.speech_cloud_addr_none else R.string.speech_cloud_addr,
            base,
        )
    }

    private fun refreshDeletionStatus() {
        val count = CloudVoiceDeletion.pendingIds(activity).size
        deletionStatus.visibility = if (count == 0) View.GONE else View.VISIBLE
        deletionStatus.text = activity.getString(R.string.speech_voice_delete_pending_count, count)
    }

    private fun currentPack(): VoicePack? {
        val id = VoicePackRepository.currentId(activity)
        return VoicePackRepository.find(activity, id)
    }

    private fun previewVoice() {
        val pack = currentPack() ?: return
        manager.speak(pack, activity.getString(R.string.speech_preview_text), TtsEmotion.NEUTRAL, preview = true)
    }

    private fun confirmLocalDelete() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.speech_local_delete_title)
            .setMessage(
                activity.getString(R.string.speech_local_delete_message, OfflineTtsSpec.DOWNLOAD_SIZE_HINT_MB),
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.speech_local_delete) { _, _ ->
                TtsModelManager.delete(activity)
                manager.notifyLocalDeleted()
                refresh()
            }
            .show()
    }

    private fun showCloudAddressEditor() {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.speech_cloud_edit_hint)
            setText(CloudTtsConfig.baseUrl(activity))
            setSingleLine()
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.speech_cloud_edit_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) {
                    BluefinStore.writeCloudTtsBase(prefs, null)
                    manager.onCloudAddressChanged()
                    refresh()
                    return@setPositiveButton
                }
                val normalized = CloudTtsConfig.normalizeOverride(raw)
                if (normalized == null) {
                    Toast.makeText(activity, R.string.speech_cloud_edit_invalid, Toast.LENGTH_LONG).show()
                } else {
                    BluefinStore.writeCloudTtsBase(prefs, normalized)
                    manager.onCloudAddressChanged()
                    refresh()
                }
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

    /**
     * Manage the packs this device created or imported. Built-ins are absent on
     * purpose: they ship inside the APK and cannot be removed.
     */
    private fun showVoicePackManager() {
        val packs = VoicePackRepository.listImported(activity)
        if (packs.isEmpty()) {
            Toast.makeText(activity, R.string.speech_voice_manage_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val current = VoicePackRepository.currentId(activity)
        val labels = packs.map { pack ->
            if (pack.id == current) "${pack.name}（当前）" else pack.name
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.speech_voice_manage)
            .setItems(labels) { _, which ->
                val pack = packs.getOrNull(which) ?: return@setItems
                confirmDeletePack(pack)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeletePack(pack: VoicePack) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.speech_voice_delete_title)
            .setMessage(activity.getString(R.string.speech_voice_delete_message, pack.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.speech_voice_delete_confirm) { _, _ ->
                manager.stop()
                if (!CloudVoiceDeletion.request(activity, pack.id)) {
                    Toast.makeText(activity, R.string.speech_voice_delete_failed, Toast.LENGTH_LONG)
                        .show()
                    return@setPositiveButton
                }
                activity.onVoicePackChanged()
                Toast.makeText(
                    activity,
                    if (CloudVoiceDeletion.isPending(activity, pack.id)) {
                        activity.getString(R.string.speech_voice_delete_pending)
                    } else {
                        activity.getString(R.string.speech_voice_deleted, pack.name)
                    },
                    Toast.LENGTH_SHORT,
                ).show()
                refresh()
            }
            .show()
    }
}
