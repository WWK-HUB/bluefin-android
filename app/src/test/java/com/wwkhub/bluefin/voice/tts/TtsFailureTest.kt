package com.wwkhub.bluefin.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worker's normalized category is authoritative; raw HTTP guessing is
 * only a fallback for non-worker responses. Unknown wires degrade safely.
 */
class TtsFailureTest {

    @Test
    fun parsesWorkerCategoryAndRetryAfter() {
        val failure = TtsFailure.fromJson(
            """{"error":"quota_exceeded","category":"QUOTA_EXHAUSTED","message":"今日朗读额度已用完，明天再试。","retryAfterSeconds":3600}""",
        )
        assertEquals(TtsCategory.QUOTA_EXHAUSTED, failure.category)
        assertEquals("quota_exceeded", failure.code)
        assertEquals(3600, failure.retryAfterSeconds)
        assertTrue(failure.isPersistent)
        assertTrue(failure.isDefinitive)
    }

    @Test
    fun freeModelUnavailableIsDefinitiveButNotQuota() {
        val failure = TtsFailure.fromJson(
            """{"error":"free_model_unavailable","category":"MODEL_UNAVAILABLE","message":"x"}""",
        )
        assertEquals(TtsCategory.MODEL_UNAVAILABLE, failure.category)
        assertTrue(failure.isDefinitive)
    }

    @Test
    fun temporaryCategoriesAreNotPersistent() {
        for (wire in listOf("TEMPORARY_NETWORK_ERROR", "RATE_LIMITED", "SERVICE_UNAVAILABLE")) {
            val failure = TtsFailure.fromJson("""{"error":"x","category":"$wire","message":"x"}""")
            assertFalse(failure.isPersistent)
            assertFalse(failure.isDefinitive)
        }
    }

    @Test
    fun unknownWireDegradesToUnknownError() {
        val failure = TtsFailure.fromJson("""{"error":"x","category":"SOMETHING_NEW","message":"x"}""")
        assertEquals(TtsCategory.UNKNOWN_ERROR, failure.category)
    }

    @Test
    fun malformedBodyDegradesSafely() {
        val failure = TtsFailure.fromJson("<html>proxy error</html>")
        assertEquals(TtsCategory.UNKNOWN_ERROR, failure.category)
        assertTrue(failure.message.isNotEmpty())
    }

    @Test
    fun httpFallbackOnlyForNonWorkerResponses() {
        assertEquals(TtsCategory.AUTH_ERROR, TtsFailure.fromHttp(401).category)
        assertEquals(TtsCategory.MODEL_UNAVAILABLE, TtsFailure.fromHttp(402).category)
        assertEquals(TtsCategory.RATE_LIMITED, TtsFailure.fromHttp(429).category)
        assertEquals(TtsCategory.SERVICE_UNAVAILABLE, TtsFailure.fromHttp(503).category)
        assertEquals(TtsCategory.UNKNOWN_ERROR, TtsFailure.fromHttp(418).category)
    }

    @Test
    fun networkFailureIsTemporary() {
        val failure = TtsFailure.network()
        assertEquals(TtsCategory.TEMPORARY_NETWORK_ERROR, failure.category)
        assertFalse(failure.isPersistent)
    }
}