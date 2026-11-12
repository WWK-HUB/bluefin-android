package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointAddressRulesTest {
    @Test
    fun httpsIsAcceptedForRemoteHosts() {
        assertTrue(EndpointAddressPolicy.check("https://api.deepseek.com") is EndpointAddressPolicy.Check.Valid)
        assertTrue(
            EndpointAddressPolicy.check("https://open.bigmodel.cn/api/paas/v4")
                is EndpointAddressPolicy.Check.Valid,
        )
    }

    @Test
    fun remotePlainHttpIsRejected() {
        val check = EndpointAddressPolicy.check("http://example.com/v1")
        assertTrue(check is EndpointAddressPolicy.Check.Invalid)
        assertTrue(EndpointAddressPolicy.check("ftp://example.com") is EndpointAddressPolicy.Check.Invalid)
        assertTrue(EndpointAddressPolicy.check("not a url") is EndpointAddressPolicy.Check.Invalid)
        assertTrue(EndpointAddressPolicy.check("") is EndpointAddressPolicy.Check.Invalid)
    }

    @Test
    fun localhostAndPrivateLanMayUseHttp() {
        listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:8080",
            "http://10.0.0.8/v1",
            "http://10.255.1.2/v1",
            "http://172.16.0.9/v1",
            "http://172.31.255.1/v1",
            "http://192.168.1.20:1234/v1",
        ).forEach { url ->
            assertTrue(url, EndpointAddressPolicy.check(url) is EndpointAddressPolicy.Check.Valid)
        }
        assertFalse(EndpointAddressPolicy.isPrivateHost("172.32.0.1"))
        assertFalse(EndpointAddressPolicy.isPrivateHost("11.0.0.1"))
        assertFalse(EndpointAddressPolicy.isPrivateHost("example.com"))
        assertTrue(EndpointAddressPolicy.isPrivateHost("localhost"))
    }

    @Test
    fun chatCompletionsPathIsAppendedOnlyOnce() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            EndpointAddressPolicy.completionsUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            EndpointAddressPolicy.completionsUrl("https://api.deepseek.com/"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointAddressPolicy.completionsUrl("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointAddressPolicy.completionsUrl("https://api.deepseek.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointAddressPolicy.completionsUrl("https://api.deepseek.com/v1/chat/completions/"),
        )
    }

    @Test
    fun configuredNeedsAddressModelAndSavedKey() {
        assertTrue(EndpointAddressPolicy.isConfigured("https://api.deepseek.com", "deepseek-flash", true))
        assertFalse(EndpointAddressPolicy.isConfigured("", "deepseek-flash", true))
        assertFalse(EndpointAddressPolicy.isConfigured("https://api.deepseek.com", "", true))
        assertFalse(EndpointAddressPolicy.isConfigured("https://api.deepseek.com", "deepseek-flash", false))
        assertFalse(EndpointAddressPolicy.isConfigured("http://example.com", "m", true))
    }

    @Test
    fun failureCopyPointsAtTheRightFieldWithoutSecrets() {
        assertTrue(ChatFailureText.forStatus(401).contains("API Key"))
        assertTrue(ChatFailureText.forStatus(403).contains("API Key"))
        assertTrue(ChatFailureText.forStatus(404).contains("模型名"))
        assertTrue(ChatFailureText.forStatus(429).contains("限流"))
        assertTrue(ChatFailureText.forStatus(503).contains("服务忙"))
        assertFalse(ChatFailureText.forStatus(401).contains("sk-"))
        assertFalse(ChatFailureText.forThrowable(java.net.UnknownHostException()).contains("Key"))
        assertTrue(ChatFailureText.forThrowable(java.net.SocketTimeoutException()).contains("超时"))
    }
}
