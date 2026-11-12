package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePreviewProcessGuardTest {

    @Test
    fun unresolvedRootBlocksEveryLaterControllerForTheProcessLifetime() {
        val guard = BubblePreviewProcessGuard()
        assertFalse(guard.isBlocked())

        val root = Any()
        guard.retainUnresolved(root, operation = "REMOVE", exceptionType = "IllegalStateException")
        assertTrue(guard.isBlocked())
        val first = guard.snapshot()
        assertEquals("REMOVE", first.operation)
        assertEquals("IllegalStateException", first.exceptionType)
        assertEquals(1, first.unresolvedCount)

        // Retaining the same owner is idempotent, never a new count.
        guard.retainUnresolved(root, operation = "ADD", exceptionType = "RuntimeException")
        assertTrue(guard.isBlocked())
        assertEquals(1, guard.snapshot().unresolvedCount)
        assertEquals("REMOVE", guard.snapshot().operation)

        guard.retainUnresolved(Any(), operation = "ADD", exceptionType = "NullPointerException")
        assertEquals(2, guard.snapshot().unresolvedCount)
        assertTrue(guard.isBlocked())
    }

    @Test
    fun explicitChatEntryChecksTheProcessBlockBeforeCreatingACircuit() {
        val entry = java.io.File(
            "src/main/java/com/wwkhub/bluefin/bubblepreview/BubblePreviewModule.kt",
        ).readText()
        val method = entry.substringAfter("fun failureCircuitOrNull(): BubblePreviewFailureCircuit? =")
            .substringBefore("}\n}")
        assertTrue(method.contains("BubblePreviewModule.whenEnabled(BubblePreviewModule.MASTER_GATE_ENABLED)"))
        assertTrue(method.contains("BubblePreviewProcessSession.isBlocked()"))
        assertTrue(method.indexOf("isBlocked()") < method.indexOf("forExplicitUserChat()"))
    }
}
