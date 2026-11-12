package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BubblePreviewBoundaryTest {
    private val contained = listOf(
        IllegalArgumentException("window token"),
        NullPointerException("programmer npe"),
        IllegalStateException("programmer state"),
        ClassCastException("programmer cast"),
        IndexOutOfBoundsException("programmer index"),
        RuntimeException("generic runtime"),
        SecurityException("revoked"),
    )

    @Test
    fun everyBoundaryOperationContainsEveryExceptionFamily() {
        val operations = listOf(
            BubblePreviewOperation.CREATE,
            BubblePreviewOperation.SNAPSHOT,
            BubblePreviewOperation.RENDER,
            BubblePreviewOperation.ADD,
            BubblePreviewOperation.UPDATE,
            BubblePreviewOperation.REMOVE,
            BubblePreviewOperation.LISTENER,
            BubblePreviewOperation.SCHEDULED,
            BubblePreviewOperation.CLEANUP,
        )
        operations.forEach { operation ->
            contained.forEach { error ->
                val circuit = BubblePreviewFailureCircuit()
                val attempt = circuit.beginDisplayAttempt(1L)!!
                val faults = mutableListOf<BubblePreviewFault>()
                val boundary = BubblePreviewBoundary { fault -> faults.add(fault) }
                val result = boundary.run(operation, attempt, 2L) { throw error }
                val recovered = result as BubblePreviewBoundaryResult.Recovered
                assertEquals(operation, recovered.fault.operation)
                assertEquals(error.javaClass.name, recovered.fault.exceptionType)
                assertTrue(recovered.fault.countedAgainstAttempt)
                assertEquals(1, faults.size)
                assertEquals(1, circuit.consecutiveFailures())
            }
        }
    }

    @Test
    fun oneAttemptCountsAtMostOneFailureAcrossNoisyCallbacks() {
        val circuit = BubblePreviewFailureCircuit()
        val attempt = circuit.beginDisplayAttempt(0L)!!
        val boundary = BubblePreviewBoundary()
        var invoked = 0

        val first = boundary.run(BubblePreviewOperation.ADD, attempt, 1L) {
            invoked += 1
            throw NullPointerException("first")
        }
        assertTrue((first as BubblePreviewBoundaryResult.Recovered).fault.countedAgainstAttempt)
        assertEquals(1, circuit.consecutiveFailures())

        val second = boundary.run(BubblePreviewOperation.UPDATE, attempt, 2L) {
            invoked += 1
            throw IllegalStateException("second")
        }
        assertTrue(second is BubblePreviewBoundaryResult.Skipped)
        assertEquals(1, invoked)
        assertEquals(1, circuit.consecutiveFailures())

        val third = boundary.run(BubblePreviewOperation.LISTENER, attempt, 3L) {
            invoked += 1
            throw ClassCastException("third")
        }
        assertTrue(third is BubblePreviewBoundaryResult.Skipped)
        assertEquals(1, invoked)
        assertEquals(1, circuit.consecutiveFailures())
    }

    @Test
    fun failedRoundCannotRepeatAttachButCanStillRemove() {
        val circuit = BubblePreviewFailureCircuit()
        val attempt = circuit.beginDisplayAttempt(0L)!!
        val boundary = BubblePreviewBoundary()
        boundary.run(BubblePreviewOperation.ADD, attempt, 1L) {
            throw IndexOutOfBoundsException("attach")
        }

        var attachCalls = 0
        val repeat = boundary.run(BubblePreviewOperation.ADD, attempt, 2L) {
            attachCalls += 1
        }
        assertTrue(repeat is BubblePreviewBoundaryResult.Skipped)
        assertEquals(0, attachCalls)

        var removeCalls = 0
        val remove = boundary.run(BubblePreviewOperation.REMOVE, attempt, 3L) {
            removeCalls += 1
        }
        assertTrue(remove is BubblePreviewBoundaryResult.Success)
        assertEquals(1, removeCalls)
        assertEquals(1, circuit.consecutiveFailures())
    }

    @Test
    fun threeIndependentBoundaryFailuresTripTheProcessCircuit() {
        val circuit = BubblePreviewFailureCircuit()
        val boundary = BubblePreviewBoundary()
        repeat(3) { index ->
            val attempt = circuit.beginDisplayAttempt(index.toLong())!!
            val result = boundary.run(BubblePreviewOperation.CREATE, attempt, index.toLong()) {
                throw IllegalStateException("attempt $index")
            }
            assertTrue(result is BubblePreviewBoundaryResult.Recovered)
        }
        assertTrue(circuit.isTripped())
        assertEquals(3, circuit.consecutiveFailures())
        assertEquals(null, circuit.beginDisplayAttempt(4L))
    }

    @Test
    fun cleanupContainsEveryOptionalExceptionAndAlwaysRunsHostCleanupInFinally() {
        contained.forEach { error ->
            val boundary = BubblePreviewBoundary()
            var hostCleanupRan = 0
            val result = boundary.cleanup(
                optionalCleanup = { throw error },
                hostCleanup = { hostCleanupRan += 1 },
            )
            assertEquals(1, hostCleanupRan)
            assertTrue(result.hostCleanupRan)
            assertEquals(BubblePreviewOperation.CLEANUP, result.optionalFault?.operation)
            assertEquals(error.javaClass.name, result.optionalFault?.exceptionType)
        }
    }

    @Test
    fun hostCleanupOwnExceptionIsNotSwallowed() {
        val boundary = BubblePreviewBoundary()
        val hostError = IllegalStateException("host cleanup failed")
        try {
            boundary.cleanup(
                optionalCleanup = { throw NullPointerException("optional failure") },
                hostCleanup = { throw hostError },
            )
            fail("host cleanup exception must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(hostError, caught)
        }
    }

    @Test
    fun vmErrorIsNotCaughtButHostCleanupStillRuns() {
        val boundary = BubblePreviewBoundary()
        var hostCleanupRan = 0
        val error = StackOverflowError("synthetic")
        try {
            boundary.cleanup(
                optionalCleanup = { throw error },
                hostCleanup = { hostCleanupRan += 1 },
            )
            fail("VM error must propagate")
        } catch (caught: StackOverflowError) {
            assertSame(error, caught)
        }
        assertEquals(1, hostCleanupRan)
    }

    @Test
    fun allExceptionsAreContainedButVmErrorsAreNotCaughtByTheOperationBoundary() {
        val boundary = BubblePreviewBoundary()
        contained.forEach { error ->
            val result = boundary.run(BubblePreviewOperation.RENDER) { throw error }
            val recovered = result as BubblePreviewBoundaryResult.Recovered
            assertEquals(error.javaClass.name, recovered.fault.exceptionType)
        }

        val overflow = StackOverflowError("synthetic")
        try {
            boundary.run(BubblePreviewOperation.SNAPSHOT) { throw overflow }
            fail("StackOverflowError must propagate")
        } catch (caught: StackOverflowError) {
            assertSame(overflow, caught)
        }
    }

    @Test
    fun classifierContainsEveryExceptionTypeWithoutAWhitelist() {
        contained.forEach { error ->
            assertTrue(BubblePreviewExceptionPolicy.isContained(error))
        }
        assertTrue(BubblePreviewExceptionPolicy.isContainedTypeName("java.lang.NullPointerException"))
        assertTrue(BubblePreviewExceptionPolicy.isContainedTypeName("java.lang.IllegalStateException"))
        assertTrue(BubblePreviewExceptionPolicy.isContainedTypeName("java.lang.ClassCastException"))
        assertFalse(BubblePreviewExceptionPolicy.isContainedTypeName(""))
    }

    @Test
    fun faultSinkReceivesSanitizedTypeOnlyAndCannotBreakTheBoundary() {
        val faults = mutableListOf<BubblePreviewFault>()
        val boundary = BubblePreviewBoundary { fault -> faults.add(fault) }
        val result = boundary.run(BubblePreviewOperation.CREATE) {
            throw NullPointerException("secret-token-must-not-be-logged")
        }
        assertTrue(result is BubblePreviewBoundaryResult.Recovered)
        assertEquals(1, faults.size)
        assertEquals("java.lang.NullPointerException", faults[0].exceptionType)
        assertFalse(faults[0].toString().contains("secret-token"))
    }

    @Test
    fun everySinkExceptionFamilyIsContained() {
        contained.forEach { sinkError ->
            val throwingSink = BubblePreviewBoundary { throw sinkError }
            val recovered = throwingSink.run(BubblePreviewOperation.ADD) {
                throw IllegalStateException("block failure")
            }
            assertTrue(recovered is BubblePreviewBoundaryResult.Recovered)
        }
    }
}
