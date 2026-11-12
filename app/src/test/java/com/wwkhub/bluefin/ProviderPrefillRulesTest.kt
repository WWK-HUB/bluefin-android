package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPrefillRulesTest {
    @Test
    fun deepSeekIsTheNewInstallDefaultAndUsesAWorkingBaseUrl() {
        assertEquals("deepseek", ModelCatalog.DEFAULT_PROVIDER_ID)
        val prefill = ModelCatalog.prefill("deepseek", ModelCatalog.Region.MAINLAND)
        assertEquals("https://api.deepseek.com", prefill.url)
        assertEquals("deepseek-flash", prefill.model)
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            EndpointAddressPolicy.completionsUrl(prefill.url),
        )
    }

    @Test
    fun glmUsesTheChatCompletionsCompatibleAddress() {
        val mainland = ModelCatalog.prefill("glm", ModelCatalog.Region.MAINLAND)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", mainland.url)
        assertFalse(mainland.url.contains("/api/v1"))
        assertEquals("glm-5.3-flash", mainland.model)
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            EndpointAddressPolicy.completionsUrl(mainland.url),
        )
        val international = ModelCatalog.prefill("glm", ModelCatalog.Region.INTERNATIONAL)
        assertEquals("https://api.z.ai/api/paas/v4", international.url)
    }

    @Test
    fun regionalProvidersOnlyChangeThePrefillUrl() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ModelCatalog.prefill("qwen", ModelCatalog.Region.MAINLAND).url,
        )
        assertEquals(
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            ModelCatalog.prefill("qwen", ModelCatalog.Region.INTERNATIONAL).url,
        )
        assertEquals(
            "https://api.moonshot.cn/v1",
            ModelCatalog.prefill("kimi", ModelCatalog.Region.MAINLAND).url,
        )
        assertEquals(
            "https://api.moonshot.ai/v1",
            ModelCatalog.prefill("kimi", ModelCatalog.Region.INTERNATIONAL).url,
        )
        assertEquals("qwen3.8-flash", ModelCatalog.prefill("qwen", ModelCatalog.Region.INTERNATIONAL).model)
        assertEquals("kimi-k3", ModelCatalog.prefill("kimi", ModelCatalog.Region.INTERNATIONAL).model)
    }

    @Test
    fun openAiLeavesTheModelNameForTheUser() {
        val prefill = ModelCatalog.prefill("openai", ModelCatalog.Region.MAINLAND)
        assertEquals("https://api.openai.com/v1", prefill.url)
        assertEquals("", prefill.model)
        assertEquals("填写模型名", ModelCatalog.OPENAI.modelHint)
    }

    @Test
    fun providerSwitchReplacesOnlyUntouchedOrEmptyPrefills() {
        val untouched = ModelCatalog.switchPrefill(
            currentUrl = "https://api.deepseek.com",
            currentModel = "deepseek-flash",
            previousProviderId = "deepseek",
            previousRegion = ModelCatalog.Region.MAINLAND,
            nextProviderId = "qwen",
            nextRegion = ModelCatalog.Region.MAINLAND,
        )
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", untouched.url)
        assertEquals("qwen3.8-flash", untouched.model)

        val empty = ModelCatalog.switchPrefill(
            currentUrl = "",
            currentModel = "",
            previousProviderId = "custom",
            previousRegion = ModelCatalog.Region.MAINLAND,
            nextProviderId = "kimi",
            nextRegion = ModelCatalog.Region.MAINLAND,
        )
        assertEquals("https://api.moonshot.cn/v1", empty.url)
        assertEquals("kimi-k3", empty.model)
    }

    @Test
    fun providerSwitchDoesNotOverwriteUserEditedValues() {
        val edited = ModelCatalog.switchPrefill(
            currentUrl = "https://my.gateway.example/v1",
            currentModel = "my-model",
            previousProviderId = "deepseek",
            previousRegion = ModelCatalog.Region.MAINLAND,
            nextProviderId = "glm",
            nextRegion = ModelCatalog.Region.MAINLAND,
        )
        assertEquals("https://my.gateway.example/v1", edited.url)
        assertEquals("my-model", edited.model)

        val halfEdited = ModelCatalog.switchPrefill(
            currentUrl = "https://my.gateway.example/v1",
            currentModel = "deepseek-flash",
            previousProviderId = "deepseek",
            previousRegion = ModelCatalog.Region.MAINLAND,
            nextProviderId = "glm",
            nextRegion = ModelCatalog.Region.MAINLAND,
        )
        assertEquals("https://my.gateway.example/v1", halfEdited.url)
        assertEquals("glm-5.3-flash", halfEdited.model)
    }

    @Test
    fun customStartsWithBlankPrefills() {
        val custom = ModelCatalog.prefill("custom", ModelCatalog.Region.MAINLAND)
        assertEquals("", custom.url)
        assertEquals("", custom.model)
        assertFalse(ModelCatalog.CUSTOM.hasRegionChoice)
    }
}
