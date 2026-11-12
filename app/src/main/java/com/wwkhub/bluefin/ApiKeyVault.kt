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
    private const val KEY_SECRET = "api_key"

    @Volatile
    private var cached: SharedPreferences? = null

    fun save(context: Context, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        val prefs = secure(context)
        if (prefs != null) {
            prefs.edit().putString(KEY_SECRET, trimmed).apply()
        } else {
            // Extremely rare fallback on devices with a broken Keystore. The file
            // is still app-private and is removed with the app.
            fallback(context).edit().putString(KEY_SECRET, trimmed).apply()
        }
        ModelEndpointStore.markKeySaved(context, true)
    }

    fun read(context: Context): String? {
        val encrypted = secure(context)?.getString(KEY_SECRET, null)
        if (!encrypted.isNullOrBlank()) return encrypted
        return fallback(context).getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        secure(context)?.edit()?.remove(KEY_SECRET)?.apply()
        fallback(context).edit().remove(KEY_SECRET).apply()
        ModelEndpointStore.markKeySaved(context, false)
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
        } catch (error: Exception) {
            Log.w(TAG, "encrypted key storage unavailable", error)
            null
        }
    }

    private fun fallback(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("bluefin_key_fallback", Context.MODE_PRIVATE)
}
