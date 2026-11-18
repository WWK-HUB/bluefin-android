package com.wwkhub.bluefin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun vendorKeyAndDirectVendorAccessNeverExistClientSide() {
        val root = File("src/main")
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension in textExtensions }
            .toList()
        assertFalse(files.isEmpty())
        files.forEach { file ->
            val text = file.readText()
            assertFalse("${file.path} references the vendor secret name", text.contains("FISH_API_KEY"))
            // The app talks ONLY to the worker proxy, never to Fish Audio directly.
            assertFalse("${file.path} calls the vendor API directly", text.contains("api.fish.audio"))
        }
    }

    @Test
    fun cloudSpeechClientNeverLogsCredentialsOrText() {
        val client = File("src/main/java/com/wwkhub/bluefin/voice/tts/CloudTtsClient.kt").readText()
        assertFalse(client.contains("Log."))
        val identity = File("src/main/java/com/wwkhub/bluefin/voice/tts/CloudIdentity.kt").readText()
        assertFalse(identity.contains("Log.i"))
        assertFalse(identity.contains("Log.d"))
        assertFalse(identity.contains("Log.w(TAG, token"))
    }

    @Test
    fun noModelWeightsAreBundledInTheApk() {
        val assets = File("src/main/assets")
        val weights = assets.walkTopDown().filter {
            it.isFile && it.extension in setOf("onnx", "bin", "pt", "pth", "tflite")
        }.toList()
        assertTrue("bundled model weights: $weights", weights.isEmpty())
    }
}
