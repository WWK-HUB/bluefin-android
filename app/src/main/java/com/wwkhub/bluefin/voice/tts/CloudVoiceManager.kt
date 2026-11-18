package com.wwkhub.bluefin.voice.tts

import android.content.Context
import com.wwkhub.bluefin.BluefinStore
import com.wwkhub.bluefin.VoicePack
import com.wwkhub.bluefin.VoicePackRepository

/**
 * Binds an internal character voice pack to its cloud identity.
 *
 * One pack, two engine bindings: the local reference clip + transcript stay
 * the ZipVoice binding, while the cloud side resolves to a Fish Audio model
 * id managed by the worker. Resolution order:
 *  1. a ready flag cached for this pack revision (no network on the hot path),
 *  2. an explicit fishReferenceId in voice.json → server-verified link,
 *  3. an existing global/device mapping on the worker (built-ins are
 *     owner-provisioned globally),
 *  4. zero-shot clone: upload the pack's own reference clip once.
 *
 * A pack is never silently substituted with another voice: if its binding
 * cannot be prepared, the failure carries INVALID_VOICE and the provider
 * decides what to do.
 */
internal object CloudVoiceManager {
    sealed class Result {
        object Ready : Result()
        data class Failed(val failure: TtsFailure) : Result()
    }

    fun ensureVoice(context: Context, pack: VoicePack, client: CloudTtsClient): Result {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
        val revision = VoicePackRepository.revision(app, pack.id)
        if (prefs.getLong(readyKey(pack.id), -1L) == revision) return Result.Ready
        return try {
            val prepared = CloudIdentity.withToken(app, client) { token ->
                when {
                    pack.fishReferenceId != null -> {
                        val linked = client.linkVoice(token, pack.id, pack.fishReferenceId, pack.name)
                        linked.optString("state") == "trained"
                    }
                    existingMapping(client, token, pack.id) -> true
                    else -> {
                        val bytes = VoicePackRepository.referenceBytes(app, pack)
                            ?: throw TtsFailure(TtsCategory.INVALID_VOICE, "no_reference", "音色参考音频缺失。")
                        val uploaded = client.uploadVoice(token, pack.id, pack.name, bytes, pack.referenceText)
                        uploaded.optString("state") == "trained"
                    }
                }
            }
            if (prepared) {
                prefs.edit().putLong(readyKey(pack.id), revision).apply()
                Result.Ready
            } else {
                Result.Failed(TtsFailure(TtsCategory.INVALID_VOICE, "training", "音色尚未训练完成，请稍后再试。"))
            }
        } catch (failure: TtsFailure) {
            Result.Failed(failure)
        } catch (_: Exception) {
            Result.Failed(TtsFailure.network())
        }
    }

    fun invalidate(context: Context, packId: String) {
        context.applicationContext
            .getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
            .edit().remove(readyKey(packId)).apply()
    }

    private fun existingMapping(client: CloudTtsClient, token: String, voiceId: String): Boolean {
        val voices = client.listVoices(token)
        for (i in 0 until voices.length()) {
            val item = voices.optJSONObject(i) ?: continue
            if (item.optString("voiceId") == voiceId && item.optString("state") == "trained") return true
        }
        return false
    }

    private fun readyKey(packId: String): String = "cloud_voice_ready_$packId"
}