package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayReattachPolicyTest {
    @Test
    fun detachRetriesAreBounded() {
        assertEquals(OverlayReattachPolicy.Detach.Retry, OverlayReattachPolicy.onDetach(0))
        assertEquals(
            OverlayReattachPolicy.Detach.Retry,
            OverlayReattachPolicy.onDetach(OverlayReattachPolicy.MAX_ATTEMPTS - 1),
        )
        assertEquals(
            OverlayReattachPolicy.Detach.Exhausted,
            OverlayReattachPolicy.onDetach(OverlayReattachPolicy.MAX_ATTEMPTS),
        )
        assertEquals(OverlayReattachPolicy.Detach.Exhausted, OverlayReattachPolicy.onDetach(99))
    }

    @Test
    fun retryOnlyWhileEnabledGrantedAndHidden() {
        assertEquals(
            OverlayReattachPolicy.Retry.Reattach,
            OverlayReattachPolicy.onRetry(enabled = true, permissionGranted = true, showing = false),
        )
        assertEquals(
            OverlayReattachPolicy.Retry.Skip,
            OverlayReattachPolicy.onRetry(enabled = false, permissionGranted = true, showing = false),
        )
        assertEquals(
            OverlayReattachPolicy.Retry.Skip,
            OverlayReattachPolicy.onRetry(enabled = true, permissionGranted = true, showing = true),
        )
        assertEquals(
            OverlayReattachPolicy.Retry.PermissionLost,
            OverlayReattachPolicy.onRetry(enabled = true, permissionGranted = false, showing = false),
        )
        assertEquals(
            OverlayReattachPolicy.Retry.PermissionLost,
            OverlayReattachPolicy.onRetry(enabled = false, permissionGranted = false, showing = false),
        )
    }

    @Test
    fun retryDelaysAreBackedOffAndBounded() {
        val delays = (0 until OverlayReattachPolicy.MAX_ATTEMPTS).map(OverlayReattachPolicy::delayForAttempt)
        assertEquals(0L, delays.first())
        assertTrue(delays.zipWithNext().all { (previous, next) -> next >= previous })
        assertTrue(delays.last() > 0)
        assertEquals(0L, OverlayReattachPolicy.delayForAttempt(-1))
        assertEquals(delays.last(), OverlayReattachPolicy.delayForAttempt(99))
    }
}
