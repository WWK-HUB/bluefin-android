package com.wwkhub.bluefin

import android.content.Context

internal data class ModelEndpoint(
    val format: ModelApiFormat,
    val url: String,
    val model: String,
    val temperature: Double?,
    val maxOutputTokens: Int,
    val extraHeaders: String,
)

/**
 * App-private endpoint settings. The API key is deliberately not part of this
 * object; it lives in [ApiKeyVault] and only a boolean "saved" flag is kept here.
 */
internal object ModelEndpointStore {
    const val PREFS = "bluefin_model_endpoint"
    private const val KEY_FORMAT = "format"
    private const val KEY_URL = "url"
    private const val KEY_MODEL = "model"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_MAX_TOKENS = "max_output_tokens"
    private const val KEY_HEADERS = "extra_headers"
    private const val KEY_HAS_KEY = "has_key"

    const val DEFAULT_MAX_TOKENS = 8192

    fun load(context: Context): ModelEndpoint {
        val prefs = prefs(context)
        val storedFormat = prefs.getString(KEY_FORMAT, null)
        val format = ModelApiFormat.entries.firstOrNull { it.name == storedFormat } ?: ModelApiFormat.OPENAI
        val legacyProvider = prefs.getString("provider", null)
        val fallback = if (storedFormat == null && (legacyProvider != null || prefs.getBoolean(KEY_HAS_KEY, false))) {
            legacyModelDefaults(legacyProvider, prefs.getString("region", null) == "INTERNATIONAL")
        } else "" to ""
        val url = prefs.getString(KEY_URL, fallback.first) ?: fallback.first
        val model = prefs.getString(KEY_MODEL, fallback.second) ?: fallback.second
        val temperature = prefs.getString(KEY_TEMPERATURE, null)?.toDoubleOrNull()
        val maxTokens = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
            .takeIf { it > 0 }
            ?: DEFAULT_MAX_TOKENS
        return ModelEndpoint(
            format = format,
            url = url,
            model = model,
            temperature = temperature,
            maxOutputTokens = maxTokens,
            extraHeaders = prefs.getString(KEY_HEADERS, "").orEmpty(),
        )
    }

    fun save(context: Context, endpoint: ModelEndpoint) {
        prefs(context).edit()
            .putString(KEY_FORMAT, endpoint.format.name)
            .putString(KEY_URL, endpoint.url.trim())
            .putString(KEY_MODEL, endpoint.model.trim())
            .putString(KEY_TEMPERATURE, endpoint.temperature?.toString())
            .putInt(KEY_MAX_TOKENS, endpoint.maxOutputTokens.coerceAtLeast(1))
            .putString(KEY_HEADERS, endpoint.extraHeaders)
            .apply()
    }

    fun hasSavedKey(context: Context): Boolean = prefs(context).getBoolean(KEY_HAS_KEY, false)

    fun markKeySaved(context: Context, saved: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAS_KEY, saved).apply()
    }

    fun isReady(context: Context): Boolean {
        val endpoint = load(context)
        return EndpointAddressPolicy.isConfigured(
            endpoint.url,
            endpoint.model,
            hasSavedKey(context),
            endpoint.format,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
