package com.wwkhub.bluefin.bubblepreview

/**
 * Operations that cross the optional module boundary.
 *
 * The boundary is intentionally tiny and pure JVM: the host calls these
 * wrappers around creation, immutable-snapshot conversion, rendering, window
 * add/update/remove, listener delivery, scheduled callbacks and cleanup. Any
 * Exception is reported once for the display attempt instead of escaping into
 * the pet/orb/stage code. Error/Throwable are never caught.
 */
internal enum class BubblePreviewOperation {
    CREATE,
    SNAPSHOT,
    RENDER,
    ADD,
    UPDATE,
    REMOVE,
    LISTENER,
    SCHEDULED,
    CLEANUP,
}

/**
 * Sanitized failure record. It deliberately carries only the operation and the
 * exception class name: never a response body, chat text, API key or URL.
 */
internal data class BubblePreviewFault(
    val operation: BubblePreviewOperation,
    val exceptionType: String,
    val countedAgainstAttempt: Boolean,
)

internal sealed class BubblePreviewBoundaryResult<out T> {
    data class Success<T>(val value: T) : BubblePreviewBoundaryResult<T>()

    data class Recovered<T>(
        val fault: BubblePreviewFault,
    ) : BubblePreviewBoundaryResult<T>()

    data class Skipped(
        val operation: BubblePreviewOperation,
        val reason: String,
    ) : BubblePreviewBoundaryResult<Nothing>()
}

internal data class BubblePreviewCleanupResult(
    val optionalFault: BubblePreviewFault?,
    val hostCleanupRan: Boolean,
)

internal fun interface BubblePreviewFaultSink {
    fun onFault(fault: BubblePreviewFault)
}

/**
 * The optional module contains every `Exception`, including programmer mistakes
 * such as NullPointerException, ClassCastException, index errors and
 * IllegalStateException. Hiding those from the host is required because the
 * bubble is optional: a sanitized type-only fault plus diagnostics/tests keeps
 * the mistake visible without crashing the session, launcher or another
 * listener.
 *
 * `Error`/`Throwable` are not `Exception`; the boundary never catches them, so
 * out-of-memory, stack exhaustion and other VM failures still reach the process.
 */
internal object BubblePreviewExceptionPolicy {
    fun isContained(@Suppress("UNUSED_PARAMETER") error: Exception): Boolean = true

    /** Pure type-name form used by source/JVM tests. */
    fun isContainedTypeName(typeName: String): Boolean = typeName.isNotBlank()
}

/**
 * The single exception boundary for the optional module.
 *
 * [attempt] is optional for work that is not part of a display attempt (for
 * example teardown after the user explicitly hid the overlay). Duplicate
 * callbacks from one attempt still count at most once because the attempt
 * token owns the circuit's one-shot failure state.
 */
internal class BubblePreviewBoundary(
    private val faultSink: BubblePreviewFaultSink? = null,
) {
    fun <T> run(
        operation: BubblePreviewOperation,
        attempt: BubblePreviewDisplayAttempt? = null,
        nowElapsedMs: Long = 0L,
        block: () -> T,
    ): BubblePreviewBoundaryResult<T> {
        if (attempt != null && attempt.isFailed() && operation != BubblePreviewOperation.REMOVE) {
            return BubblePreviewBoundaryResult.Skipped(operation, "attempt already failed")
        }
        return try {
            BubblePreviewBoundaryResult.Success(block())
        } catch (error: Exception) {
            val counted = attempt?.recordFailure(nowElapsedMs) ?: false
            val fault = BubblePreviewFault(operation, error.javaClass.name, counted)
            report(fault)
            BubblePreviewBoundaryResult.Recovered(fault)
        }
    }

    /**
     * Runs optional cleanup, then always runs the original host cleanup in a
     * finally block. Every optional `Exception` is reported and contained. The
     * host's own cleanup exception is not an optional failure: it is never
     * swallowed and propagates after the optional work had its chance to run.
     * VM errors are never caught.
     */
    fun cleanup(
        attempt: BubblePreviewDisplayAttempt? = null,
        nowElapsedMs: Long = 0L,
        optionalCleanup: () -> Unit,
        hostCleanup: () -> Unit,
    ): BubblePreviewCleanupResult {
        var fault: BubblePreviewFault? = null
        try {
            try {
                optionalCleanup()
            } catch (error: Exception) {
                val counted = attempt?.recordFailure(nowElapsedMs) ?: false
                fault = BubblePreviewFault(
                    BubblePreviewOperation.CLEANUP,
                    error.javaClass.name,
                    counted,
                )
                report(fault)
            }
        } finally {
            hostCleanup()
        }
        return BubblePreviewCleanupResult(fault, hostCleanupRan = true)
    }

    private fun report(fault: BubblePreviewFault) {
        val sink = faultSink ?: return
        try {
            sink.onFault(fault)
        } catch (error: Exception) {
            // A reporting sink is itself part of the optional module. Any
            // Exception it throws is contained; Error still propagates.
        }
    }
}