package com.wwkhub.bluefin

/** Reconstructs missing fields from preferences written before the format selector. */
internal fun legacyModelDefaults(provider: String?, international: Boolean): Pair<String, String> = when (provider) {
    "custom" -> "" to ""
    "openai" -> "https://api.openai.com/v1" to ""
    "qwen" -> (if (international) "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        else "https://dashscope.aliyuncs.com/compatible-mode/v1") to "qwen3.8-flash"
    "kimi" -> (if (international) "https://api.moonshot.ai/v1" else "https://api.moonshot.cn/v1") to "kimi-k3"
    "glm" -> (if (international) "https://api.z.ai/api/paas/v4"
        else "https://open.bigmodel.cn/api/paas/v4") to "glm-5.3-flash"
    else -> "https://api.deepseek.com" to "deepseek-flash"
}
