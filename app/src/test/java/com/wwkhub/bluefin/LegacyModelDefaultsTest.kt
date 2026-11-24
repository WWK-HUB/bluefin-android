package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyModelDefaultsTest {
    @Test
    fun reconstructsSavedProviderPreferencesWhenUrlAndModelAreMissing() {
        assertEquals("https://api.deepseek.com" to "deepseek-flash", legacyModelDefaults(null, false))
        assertEquals("https://open.bigmodel.cn/api/paas/v4" to "glm-5.3-flash", legacyModelDefaults("glm", false))
        assertEquals("https://api.z.ai/api/paas/v4" to "glm-5.3-flash", legacyModelDefaults("glm", true))
        assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1" to "qwen3.8-flash", legacyModelDefaults("qwen", true))
        assertEquals("https://api.moonshot.ai/v1" to "kimi-k3", legacyModelDefaults("kimi", true))
        assertEquals("https://api.openai.com/v1" to "", legacyModelDefaults("openai", false))
        assertEquals("" to "", legacyModelDefaults("custom", false))
    }
}
