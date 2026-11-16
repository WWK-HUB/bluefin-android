package com.wwkhub.bluefin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user-supplied API key in app-private, Keystore-backed storage.
 *
 * This object is intentionally lazy: it is only touched when the user saves,
 * tests, clears, or sends. Opening the overlay never constructs it.
 */
internal object ApiKeyVault {
    private const val TAG = "BluefinKeyVault"
    private const val FILE = "bluefin_key_vault"
    private const val LEGACY_FILE = "bluefin_key_fallback"
    private const val KEY_SECRET = "api_key"

    @Volatile
    private var cached: SharedPreferences? = null

    @Synchronized
    fun save(context: Context, key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        val prefs = secure(context) ?: return false
        if (!runCatching { prefs.edit().putString(KEY_SECRET, trimmed).commit() }.getOrDefault(false)) return false
        legacy(context).edit().remove(KEY_SECRET).apply()
        ModelEndpointStore.markKeySaved(context, true)
        return true
    }

    @Synchronized
    fun read(context: Context): String? {
        val encrypted = runCatching { secure(context)?.getString(KEY_SECRET, null) }.getOrNull()
        val old = legacy(context).getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() }
        if (!encrypted.isNullOrBlank()) {
            if (old != null) legacy(context).edit().remove(KEY_SECRET).apply()
            return encrypted
        }
        return old?.takeIf { save(context, it) }
    }

    @Synchronized
    fun clear(context: Context): Boolean {
        val app = context.applicationContext
        val removedSecure = app.deleteSharedPreferences(FILE)
        cached = null
        val removedLegacy = app.deleteSharedPreferences(LEGACY_FILE)
        if (!removedSecure || !removedLegacy) return false
        ModelEndpointStore.markKeySaved(context, false)
        return true
    }

    private fun secure(context: Context): SharedPreferences? {
        cached?.let { return it }
        return try {
            val app = context.applicationContext
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                app,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            cached = prefs
            prefs
        } catch (_: Exception) {
            Log.w(TAG, "encrypted key storage unavailable")
            null
        }
    }

    private fun legacy(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
}
