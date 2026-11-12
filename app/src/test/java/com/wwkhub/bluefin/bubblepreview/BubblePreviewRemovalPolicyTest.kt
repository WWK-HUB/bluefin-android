package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fake-port tests for the bounded cleanup loop. They prove that an already
 * system-detached root is success (not an orphan), a transient failure is
 * retried at most once, a persistent failure is retained after exactly two
 * attempts, and a VM Error is never caught.
 */
class BubblePreviewRemovalPolicyTest {
    private class FakePort(
        var attached: Boolean,
        var failuresBeforeSuccess: Int = 0,
        val detachOnFailure: Boolean = false,
    ) : BubblePreviewRemovalPort {
        var calls = 0
        var lastError: Exception? = null

        override fun isManaged(): Boolean = attached

        override fun removeOnce() {
            calls += 1
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess -= 1
                val error = IllegalStateException("remove $calls")
                lastError = error
                if (detachOnFailure) attached = false
                throw error
            }
            attached = false
        }
    }

    @Test
    fun alreadyDetachedRootIsIdempotentSuccessWithoutARemoveCall() {
        val port = FakePort(attached = false)
        val outcome = BubblePreviewRemovalPolicy.remove(port)
        assertTrue(outcome.removed)
        assertEquals(0, outcome.attempts)
        assertEquals(0, port.calls)
        assertNull(outcome.lastError)
    }

    @Test
    fun oneSuccessfulRemoveDetachesImmediately() {
        val port = FakePort(attached = true)
        val outcome = BubblePreviewRemovalPolicy.remove(port)
        assertTrue(outcome.removed)
        assertEquals(1, outcome.attempts)
        assertEquals(1, port.calls)
        assertFalse(port.isManaged())
    }

    @Test
    fun transientFailureThatDetachesIsStillSuccessWithOneAttempt() {
        val port = FakePort(attached = true, failuresBeforeSuccess = 1, detachOnFailure = true)
        val outcome = BubblePreviewRemovalPolicy.remove(port)
        assertTrue(outcome.removed)
        assertEquals(1, outcome.attempts)
        assertEquals(1, port.calls)
        assertTrue(outcome.lastError is IllegalStateException)
    }

    @Test
    fun persistentFailureIsBoundedToTwoAttemptsAndRetainsTheRoot() {
        val port = FakePort(attached = true, failuresBeforeSuccess = Int.MAX_VALUE)
        val outcome = BubblePreviewRemovalPolicy.remove(port)
        assertFalse(outcome.removed)
        assertEquals(BubblePreviewRemovalPolicy.MAX_ATTEMPTS, outcome.attempts)
        assertEquals(2, port.calls)
        assertEquals("java.lang.IllegalStateException", outcome.lastError?.javaClass?.name)
        assertTrue(port.isManaged())
    }

    @Test
    fun managedBeforeFirstAttachIsNotMistakenForAnOrphan() {
        // A successful addView acquires a parent before the first traversal, so
        // isAttachedToWindow is still false. Cleanup must remove that managed
        // root instead of declaring idempotent success and leaking the window.
        var parented = true
        var attachedToWindow = false
        val port = object : BubblePreviewRemovalPort {
            override fun isManaged(): Boolean = parented || attachedToWindow

            override fun removeOnce() {
                parented = false
                attachedToWindow = false
            }
        }
        val outcome = BubblePreviewRemovalPolicy.remove(port)
        assertTrue(outcome.removed)
        assertEquals(1, outcome.attempts)
        assertFalse(parented)
    }

    @Test
    fun vmErrorIsNeverCaughtByTheCleanupLoop() {
        val error = StackOverflowError("synthetic")
        val port = object : BubblePreviewRemovalPort {
            override fun isManaged(): Boolean = true
            override fun removeOnce() {
                throw error
            }
        }
        try {
            BubblePreviewRemovalPolicy.remove(port)
            fail("StackOverflowError must propagate")
        } catch (caught: StackOverflowError) {
            assertSame(error, caught)
        }
    }
}
