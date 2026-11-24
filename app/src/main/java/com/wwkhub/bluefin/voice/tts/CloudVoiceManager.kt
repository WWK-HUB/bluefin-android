package com.wwkhub.bluefin.voice.tts

import android.content.Context
import com.wwkhub.bluefin.BluefinStore
import com.wwkhub.bluefin.VoicePack
import com.wwkhub.bluefin.VoicePackFormat
import com.wwkhub.bluefin.VoicePackRepository
import java.util.concurrent.CancellationException

internal object CloudVoiceManager {
    const val CONSENT_REQUIRED = "voice_consent_required"
    private val locks = Array(32) { Any() }

    fun <T> withPackLock(id: String, block: () -> T): T =
        synchronized(locks[id.hashCode().ushr(1) % locks.size], block)

    fun ensureVoice(context: Context, pack: VoicePack, client: CloudTtsClient, revision: Long) {
        if (VoicePackFormat.isBuiltInId(pack.id)) return
        withPackLock(pack.id) {
            if (CloudVoiceDeletion.isPending(context, pack.id) ||
                VoicePackRepository.revision(context, pack.id) != revision ||
                VoicePackRepository.find(context, pack.id) == null
            ) {
                throw CancellationException("voice was removed or replaced")
            }
            val prefs = CloudVoiceDeletion.prefs(context)
            val ready = readyKey(client.baseUrl, pack.id)
            if (prefs.getLong(ready, -1L) == revision) return@withPackLock
            val prepared = CloudIdentity.withToken(context, client) { token ->
                val oldPrefs = context.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
                val legacy = oldPrefs.getLong("cloud_voice_ready_${pack.id}", -1L) == revision
                when {
                    pack.fishReferenceId != null -> {
                        val binding = track(context, pack.id, client.baseUrl)
                        client.linkVoice(token, pack.id, pack.fishReferenceId, pack.name, binding).optString("state") == "trained"
                    }
                    legacy && existingMapping(client, token, pack.id) -> {
                        track(context, pack.id, client.baseUrl)
                        true
                    }
                    else -> {
                        if (prefs.getLong(consentKey(client.baseUrl, pack.id), -1L) != revision) {
                            throw TtsFailure(TtsCategory.INVALID_VOICE, CONSENT_REQUIRED, "请确认上传参考录音。")
                        }
                        val bytes = VoicePackRepository.referenceBytes(context, pack)
                            ?: throw TtsFailure(TtsCategory.INVALID_VOICE, "no_reference", "音色参考音频缺失。")
                        val binding = track(context, pack.id, client.baseUrl)
                        client.uploadVoice(token, pack.id, pack.name, bytes, pack.referenceText, binding).optString("state") == "trained"
                    }
                }
            }
            if (!prepared) {
                throw TtsFailure(TtsCategory.INVALID_VOICE, "training", "音色尚未训练完成，请稍后再试。")
            }
            prefs.edit().putLong(ready, revision).apply()
        }
    }

    private fun track(context: Context, id: String, base: String): String {
        val binding = CloudVoiceDeletion.trackBinding(context, id, base)
        if (binding != null) return binding
        if (CloudVoiceDeletion.isPending(context, id)) throw CancellationException("voice is being deleted")
        throw TtsFailure(TtsCategory.UNKNOWN_ERROR, "voice_storage", "无法保存云端音色状态。")
    }

    fun grant(context: Context, base: String, id: String, revision: Long) =
        CloudVoiceDeletion.prefs(context).edit().putLong(consentKey(base, id), revision).commit()

    fun invalidate(context: Context, base: String, id: String) {
        CloudVoiceDeletion.prefs(context).edit().remove(readyKey(base, id)).apply()
    }

    private fun existingMapping(client: CloudTtsClient, token: String, voiceId: String): Boolean {
        val voices = client.listVoices(token)
        return (0 until voices.length()).any { i ->
            val item = voices.optJSONObject(i)
            item?.optString("voiceId") == voiceId && item.optString("state") == "trained"
        }
    }

    private fun readyKey(base: String, id: String): String = "ready:$id:$base"
    private fun consentKey(base: String, id: String): String = "consent:$id:$base"
}
