package com.wwkhub.bluefin

import android.content.Context

internal data class ModelEndpoint(
    val providerId: String,
    val region: ModelCatalog.Region,
    val url: String,
    val model: String,
    val temperature: Double?,
    val maxOutputTokens: Int,
    val extraHeaders: String,
) {
    val provider: ModelProvider get() = ModelCatalog.provider(providerId)
    val modelHint: String get() = provider.modelHint
}

/**
 * App-private endpoint settings. The API key is deliberately not part of this
 * object; it lives in [ApiKeyVault] and only a boolean "saved" flag is kept here.
 */
internal object ModelEndpointStore {
    const val PREFS = "bluefin_model_endpoint"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_REGION = "region"
    private const val KEY_URL = "url"
    private const val KEY_MODEL = "model"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_MAX_TOKENS = "max_output_tokens"
    private const val KEY_HEADERS = "extra_headers"
    private const val KEY_HAS_KEY = "has_key"

    const val DEFAULT_MAX_TOKENS = 8192

    fun load(context: Context): ModelEndpoint {
        val prefs = prefs(context)
        val providerId = prefs.getString(KEY_PROVIDER, ModelCatalog.DEFAULT_PROVIDER_ID)
            ?: ModelCatalog.DEFAULT_PROVIDER_ID
        val region = runCatching {
            ModelCatalog.Region.valueOf(
                prefs.getString(KEY_REGION, ModelCatalog.Region.MAINLAND.name)
                    ?: ModelCatalog.Region.MAINLAND.name,
            )
        }.getOrDefault(ModelCatalog.Region.MAINLAND)
        val fallback = ModelCatalog.prefill(providerId, region)
        val url = prefs.getString(KEY_URL, fallback.url) ?: fallback.url
        val model = prefs.getString(KEY_MODEL, fallback.model) ?: fallback.model
        val temperature = prefs.getString(KEY_TEMPERATURE, null)?.toDoubleOrNull()
        val maxTokens = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
            .takeIf { it > 0 }
            ?: DEFAULT_MAX_TOKENS
        return ModelEndpoint(
            providerId = ModelCatalog.provider(providerId).id,
            region = ModelCatalog.regionFor(providerId, region),
            url = url,
            model = model,
            temperature = temperature,
            maxOutputTokens = maxTokens,
            extraHeaders = prefs.getString(KEY_HEADERS, "").orEmpty(),
        )
    }

    fun save(context: Context, endpoint: ModelEndpoint) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, ModelCatalog.provider(endpoint.providerId).id)
            .putString(KEY_REGION, ModelCatalog.regionFor(endpoint.providerId, endpoint.region).name)
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
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
