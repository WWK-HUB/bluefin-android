package com.wwkhub.bluefin.voice.tts

import android.content.Context
import android.content.SharedPreferences
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.wwkhub.bluefin.BluefinStore
import com.wwkhub.bluefin.BuildConfig
import com.wwkhub.bluefin.VoicePackFormat
import com.wwkhub.bluefin.VoicePackRepository
import java.io.File
import java.util.UUID

/** Records which services received a pack and journals deletion before touching its files. */
internal object CloudVoiceDeletion {
    const val PREFS = "bluefin_cloud_voice"
    private const val PENDING = "pending:"
    private const val BOUND = "bound:"
    private val lock = Any()

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pendingIds(context: Context): List<String> =
        prefs(context).all.keys.filter { it.startsWith(PENDING) }.map { it.removePrefix(PENDING) }

    fun isPending(context: Context, id: String): Boolean = prefs(context).contains(PENDING + id)

    fun trackBinding(context: Context, id: String, base: String): String? = synchronized(lock) {
        val store = prefs(context)
        if (store.contains(PENDING + id)) return@synchronized null
        val key = BOUND + id
        val bindingKey = bindingKey(id, base)
        val binding = store.getString(bindingKey, null) ?: UUID.randomUUID().toString()
        val saved = store.edit()
            .putString(bindingKey, binding)
            .putStringSet(key, store.getStringSet(key, emptySet()).orEmpty() + base)
            .commit()
        binding.takeIf { saved }
    }

    private fun bindingKey(id: String, base: String): String = "binding:$id:$base"

    /** Called while the repository install/delete lock is held. */
    private fun journal(context: Context, id: String): Boolean = synchronized(lock) {
        val store = prefs(context)
        if (store.contains(PENDING + id)) return@synchronized true
        val targets = store.getStringSet(BOUND + id, emptySet()).orEmpty().toMutableSet()
        val oldPrefs = context.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
        if (oldPrefs.contains("cloud_voice_ready_$id")) {
            // Old builds recorded readiness without a service. Try the configured service
            // and the distribution service using fresh, service-bound authentication.
            targets.addAll(listOfNotNull(
                CloudTtsConfig.baseUrl(context).takeIf { it.isNotEmpty() },
                CloudTtsConfig.normalizeOverride(BuildConfig.CLOUD_TTS_BASE_URL),
            ))
        }
        store.edit().putStringSet(PENDING + id, targets).commit()
    }

    fun request(context: Context, id: String): Boolean {
        if (VoicePackFormat.isBuiltInId(id)) return false
        val removed = synchronized(VoicePackRepository) {
            if (!journal(context, id)) return false
            removeLocal(context, id)
        }
        val store = prefs(context)
        if (removed && store.getStringSet(PENDING + id, emptySet()).orEmpty().isEmpty()) {
            synchronized(lock) { finish(context, id, emptySet()) }
        }
        if (isPending(context, id)) schedule(context, id)
        return removed
    }

    fun resume(context: Context) = pendingIds(context).forEach { schedule(context, it) }

    private fun schedule(context: Context, id: String) {
        val constraints = Constraints.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // A reachable custom Worker can be on a LAN or VPN even when Android
            // has not validated access to its public connectivity probe.
            val reachableNetwork = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            constraints.setRequiredNetworkRequest(reachableNetwork, NetworkType.CONNECTED)
        } else {
            constraints.setRequiredNetworkType(NetworkType.CONNECTED)
        }
        val request = OneTimeWorkRequestBuilder<CloudVoiceDeleteWorker>()
            .setConstraints(constraints.build())
            .setInputData(androidx.work.workDataOf("voiceId" to id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("cloud-voice-delete:$id", ExistingWorkPolicy.KEEP, request)
    }

    private fun removeLocal(context: Context, id: String): Boolean {
        val root = VoicePackRepository.packRoot(context)
        if (File(root, id).exists() && !VoicePackRepository.delete(context, id)) return false
        val cache = TtsCache(context)
        cache.removeVoice(id)
        cache.removeLegacyEntries()
        return true
    }

    fun complete(context: Context, id: String): Boolean = CloudVoiceManager.withPackLock(id) {
        if (!isPending(context, id)) return@withPackLock true
        if (!synchronized(VoicePackRepository) { removeLocal(context, id) }) return@withPackLock false
        val store = prefs(context)
        val targets = store.getStringSet(PENDING + id, emptySet()).orEmpty().toSet()
        val remaining = targets.filter { base ->
            val client = CloudTtsClient(base)
            val binding = store.getString(bindingKey(id, base), null)
            runCatching { CloudIdentity.withToken(context, client) { client.deleteVoice(it, id, binding) } }.isFailure
        }.toSet()
        synchronized(lock) { finish(context, id, remaining) }
    }

    private fun finish(context: Context, id: String, remaining: Set<String>): Boolean {
        val store = prefs(context)
        val change = store.edit()
        if (remaining.isEmpty()) {
            change.remove(PENDING + id).remove(BOUND + id)
            store.all.keys.filter {
                it.startsWith("ready:$id:") || it.startsWith("consent:$id:") || it.startsWith("binding:$id:")
            }.forEach { change.remove(it) }
            context.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
                .edit().remove("cloud_voice_ready_$id").apply()
        } else {
            change.putStringSet(PENDING + id, remaining)
        }
        return change.commit() && remaining.isEmpty()
    }
}

class CloudVoiceDeleteWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString("voiceId") ?: return Result.failure()
        if (!id.matches(Regex("[a-z0-9][a-z0-9-]{0,31}")) || VoicePackFormat.isBuiltInId(id)) return Result.failure()
        return if (CloudVoiceDeletion.complete(applicationContext, id)) Result.success() else Result.retry()
    }
}
