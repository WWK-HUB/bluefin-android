package com.wwkhub.bluefin.voice.tts

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * The device's cloud identity: a random UUID plus a short-lived HMAC session
 * token issued by the worker. This is NOT the vendor API key — it is a
 * per-device credential that can be revoked server-side, and it is the only
 * credential the app ever holds for speech.
 *
 * Storage prefers Keystore-backed encrypted prefs (same idiom as ApiKeyVault)
 * and falls back to app-private prefs when the Keystore is unavailable; the
 * token grants nothing beyond this device's own quota either way.
 */
internal object CloudIdentity {
    private const val TAG = "BluefinCloudId"
    private const val FILE = "bluefin_cloud_identity"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_EXPIRES = "session_expires"
    private const val REFRESH_MARGIN_SECONDS = 120L

    @Volatile
    private var cached: SharedPreferences? = null

    fun deviceId(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_DEVICE, null)?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE, created).apply()
        return created
    }

    /**
     * Returns a usable session token, registering or refreshing as needed.
     * Runs on the caller's worker thread; serialized so two sentences never
     * race a registration.
     */
    @Synchronized
    fun ensureToken(context: Context, client: CloudTtsClient): String {
        val prefs = prefs(context)
        val now = System.currentTimeMillis() / 1000
        if (prefs.contains(KEY_TOKEN) || prefs.contains(KEY_EXPIRES)) {
            prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).apply()
        }
        val tokenKey = sessionKey(client, KEY_TOKEN)
        val expiryKey = sessionKey(client, KEY_EXPIRES)
        val token = prefs.getString(tokenKey, null)
        val expires = prefs.getLong(expiryKey, 0L)
        if (token != null && expires > now + REFRESH_MARGIN_SECONDS) return token
        if (token != null) {
            runCatching {
                val refreshed = client.refresh(token)
                return store(prefs, client, refreshed)
            }
            // Refresh failed (expired beyond grace, revoked, network): fall
            // through to a fresh registration, which the server gates again.
        }
        val registered = client.register(deviceId(context))
        return store(prefs, client, registered)
    }

    /** Drops the session so the next call re-registers (used after a 401). */
    @Synchronized
    private fun invalidate(context: Context, client: CloudTtsClient, token: String) {
        val prefs = prefs(context)
        val key = sessionKey(client, KEY_TOKEN)
        if (prefs.getString(key, null) == token) {
            prefs.edit().remove(key).remove(sessionKey(client, KEY_EXPIRES)).apply()
        }
    }

    /**
     * Runs [block] with a valid token; on a device-auth failure re-registers
     * once and retries. Revocation is NOT retried away — the server refuses
     * re-registration for revoked device ids.
     */
    fun <T> withToken(context: Context, client: CloudTtsClient, block: (String) -> T): T {
        val token = ensureToken(context, client)
        try {
            return block(token)
        } catch (failure: TtsFailure) {
            if (failure.category != TtsCategory.AUTH_ERROR || failure.code == "device_revoked") throw failure
            invalidate(context, client, token)
            val fresh = ensureToken(context, client)
            return block(fresh)
        }
    }

    private fun sessionKey(client: CloudTtsClient, field: String): String = "$field:${client.baseUrl}"

    private fun store(prefs: SharedPreferences, client: CloudTtsClient, response: org.json.JSONObject): String {
        val token = response.getString("token")
        prefs.edit()
            .putString(sessionKey(client, KEY_TOKEN), token)
            .putLong(sessionKey(client, KEY_EXPIRES), response.getLong("expiresAt"))
            .apply()
        return token
    }

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        val app = context.applicationContext
        return try {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { cached = it }
        } catch (_: Exception) {
            Log.w(TAG, "encrypted identity storage unavailable; using app-private prefs")
            app.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { cached = it }
        }
    }
}