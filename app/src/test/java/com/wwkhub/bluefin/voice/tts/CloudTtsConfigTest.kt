package com.wwkhub.bluefin.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudTtsConfigTest {
    @Test
    fun canonicalBaseHasNoCredentialOrRequestParameters() {
        assertEquals("https://worker.example/api", CloudTtsConfig.normalizeOverride("HTTPS://WORKER.Example:443/x/../api/"))
        assertEquals("http://10.0.2.2:8787", CloudTtsConfig.normalizeOverride("http://10.0.2.2:8787/"))
        assertNull(CloudTtsConfig.normalizeOverride("https://user@worker.example"))
        assertNull(CloudTtsConfig.normalizeOverride("https://worker.example/?mode=debug"))
        assertNull(CloudTtsConfig.normalizeOverride("https://worker.example/#preview"))
    }
}
