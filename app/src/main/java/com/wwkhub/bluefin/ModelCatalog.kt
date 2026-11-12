package com.wwkhub.bluefin

/**
 * Provider presets for the OpenAI-compatible Chat Completions protocol.
 *
 * These values are only prefill suggestions. The user can always edit both the
 * address and the model name. Nothing here contains or implies an official key.
 */
internal data class ModelProvider(
    val id: String,
    val label: String,
    val mainlandUrl: String,
    val internationalUrl: String?,
    val defaultModel: String,
    val modelHint: String,
) {
    val hasRegionChoice: Boolean get() = internationalUrl != null
}

internal data class ProviderPrefill(
    val url: String,
    val model: String,
)

internal object ModelCatalog {
    enum class Region {
        MAINLAND,
        INTERNATIONAL,
    }

    const val DEFAULT_PROVIDER_ID = "deepseek"

    val CUSTOM = ModelProvider(
        id = "custom",
        label = "自定义",
        mainlandUrl = "",
        internationalUrl = null,
        defaultModel = "",
        modelHint = "填写模型名",
    )

    val DEEPSEEK = ModelProvider(
        id = "deepseek",
        label = "DeepSeek",
        mainlandUrl = "https://api.deepseek.com",
        internationalUrl = null,
        defaultModel = "deepseek-flash",
        modelHint = "deepseek-flash",
    )

    val QWEN = ModelProvider(
        id = "qwen",
        label = "通义千问",
        mainlandUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        internationalUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen3.8-flash",
        modelHint = "qwen3.8-flash",
    )

    val KIMI = ModelProvider(
        id = "kimi",
        label = "Kimi",
        mainlandUrl = "https://api.moonshot.cn/v1",
        internationalUrl = "https://api.moonshot.ai/v1",
        defaultModel = "kimi-k3",
        modelHint = "kimi-k3",
    )

    val GLM = ModelProvider(
        id = "glm",
        label = "智谱 GLM",
        mainlandUrl = "https://open.bigmodel.cn/api/paas/v4",
        internationalUrl = "https://api.z.ai/api/paas/v4",
        defaultModel = "glm-5.3-flash",
        modelHint = "glm-5.3-flash",
    )

    val OPENAI = ModelProvider(
        id = "openai",
        label = "OpenAI",
        mainlandUrl = "https://api.openai.com/v1",
        internationalUrl = null,
        defaultModel = "",
        modelHint = "填写模型名",
    )

    val all: List<ModelProvider> = listOf(CUSTOM, DEEPSEEK, QWEN, KIMI, GLM, OPENAI)

    fun provider(id: String?): ModelProvider =
        all.firstOrNull { it.id == id } ?: provider(DEFAULT_PROVIDER_ID)

    fun prefill(providerId: String?, region: Region): ProviderPrefill {
        val provider = provider(providerId)
        val url = if (region == Region.INTERNATIONAL) {
            provider.internationalUrl ?: provider.mainlandUrl
        } else {
            provider.mainlandUrl
        }
        return ProviderPrefill(url = url, model = provider.defaultModel)
    }

    fun regionFor(providerId: String?, region: Region): Region =
        if (provider(providerId).hasRegionChoice) region else Region.MAINLAND

    /**
     * Applies a provider or region switch without clobbering user edits.
     *
     * The URL/model are replaced only when they are blank or still exactly equal
     * to the previous preset. The API key is never touched here.
     */
    fun switchPrefill(
        currentUrl: String,
        currentModel: String,
        previousProviderId: String?,
        previousRegion: Region,
        nextProviderId: String?,
        nextRegion: Region,
    ): ProviderPrefill {
        val previous = prefill(previousProviderId, regionFor(previousProviderId, previousRegion))
        val next = prefill(nextProviderId, regionFor(nextProviderId, nextRegion))
        val url = if (currentUrl.isBlank() || currentUrl.trim() == previous.url) next.url else currentUrl
        val model = if (currentModel.isBlank() || currentModel.trim() == previous.model) {
            next.model
        } else {
            currentModel
        }
        return ProviderPrefill(url = url, model = model)
    }
}
