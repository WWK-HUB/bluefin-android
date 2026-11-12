package com.wwkhub.bluefin

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class NoBuiltInCredentialTest {
    private val textExtensions = setOf("kt", "java", "xml", "json", "js", "md", "html", "properties")

    @Test
    fun productionSourcesAndResourcesContainNoOfficialApiKey() {
        val root = File("src/main")
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension in textExtensions }
            .toList()
        assertFalse(files.isEmpty())
        val tokenPattern = Regex("""sk-[A-Za-z0-9_\-]{16,}""")
        val bearerPattern = Regex("""Bearer\s+[A-Za-z0-9_\-\.]{16,}""")
        files.forEach { file ->
            val text = file.readText()
            assertFalse("${file.path} contains an sk- token", tokenPattern.containsMatchIn(text))
            assertFalse("${file.path} contains a hardcoded Bearer token", bearerPattern.containsMatchIn(text))
        }
    }

    @Test
    fun keyStorageIsAppPrivateAndNotLogged() {
        val vault = File("src/main/java/com/wwkhub/bluefin/ApiKeyVault.kt").readText()
        assertFalse(vault.contains("getExternalFilesDir"))
        assertFalse(vault.contains("Log.i(TAG, key"))
        assertFalse(vault.contains("Log.d(TAG, key"))
        assertFalse(vault.contains("Log.w(TAG, key"))
        val client = File("src/main/java/com/wwkhub/bluefin/ChatCompletionsClient.kt").readText()
        assertFalse(client.contains("Log."))
    }
}
