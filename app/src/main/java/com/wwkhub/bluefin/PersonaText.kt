package com.wwkhub.bluefin

/**
 * The product-written default self-description. This is not copied from any
 * third-party project. The exact text is part of the user-visible product copy.
 */
internal object PersonaText {
    const val DEFAULT =
        "你是蓝鳍，住在用户手机里的桌宠，蓝发的鲸鱼少年，陪在屏幕边上。" +
            "说话像朋友，简短自然，不必一次说很长。不知道就说不知道，不要编造。" +
            "用户戳、拖、摸你是在跟你玩，不必每次都当成要办事。" +
            "用用户使用的语言回答，默认中文。"

    private const val OLD_DEFAULT =
        "你是蓝鳍，住在用户手机里的桌宠，蓝发的鲸鱼少年，陪在屏幕边上。" +
            "说话像朋友，简短自然，不必一次说很长。不知道就说不知道，不要编造。" +
            "你现在只能文字对话，不能操作手机、不能看屏幕、也不能出声。" +
            "用户戳、拖、摸你是在跟你玩，不必每次都当成要办事。" +
            "用用户使用的语言回答，默认中文。"

    fun normalize(stored: String?): String {
        val text = stored?.trim().orEmpty()
        return if (text.isEmpty() || text == OLD_DEFAULT) DEFAULT else text
    }

    fun restoreDefault(): String = DEFAULT

    fun isDefault(text: String): Boolean = normalize(text) == DEFAULT
}
