package com.wwkhub.bluefin.bubblepreview

/** Sanitized process-lifetime cleanup state. Never carries chat text or keys. */
internal data class BubblePreviewProcessFatalState(
    val blocked: Boolean,
    val operation: String?,
    val exceptionType: String?,
    val unresolvedCount: Int,
)

/**
 * Pure process guard used by the singleton and by JVM tests.
 *
 * A root whose removal failed is retained for the lifetime of the process.
 * Once an unresolved root/controller exists, every later controller and every
 * later attach is refused: there is deliberately no retry, no reset and no
 * "try a fresh window" path. Only process death clears this state.
 */
internal class BubblePreviewProcessGuard {
    private val unresolved = ArrayList<Any>()
    private var fatalOperation: String? = null
    private var fatalType: String? = null

    fun retainUnresolved(
        owner: Any,
        operation: String?,
        exceptionType: String?,
    ) {
        if (unresolved.none { it === owner }) unresolved.add(owner)
        if (fatalOperation == null) fatalOperation = operation
        if (fatalType == null) fatalType = exceptionType
    }

    fun isBlocked(): Boolean = unresolved.isNotEmpty()

    fun snapshot(): BubblePreviewProcessFatalState = BubblePreviewProcessFatalState(
        blocked = isBlocked(),
        operation = fatalOperation,
        exceptionType = fatalType,
        unresolvedCount = unresolved.size,
    )
}

/**
 * Process-lifetime ownership for the optional preview circuit.
 *
 * The holder is lazy: touching [BubblePreviewExplicitChatEntry] with the gate
 * closed does not initialize it, and nothing in the overlay show/constructor
 * path may call it.
 *
 * There is intentionally no reset API. Rebuilding `OverlayWindow`, hiding and
 * reopening the overlay, restarting the foreground service or toggling a
 * future preview preference must not clear a process trip; only process death
 * resets this state.
 */
internal object BubblePreviewProcessSession {
    private val circuitHolder = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BubblePreviewFailureCircuit()
    }
    private val guardHolder = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BubblePreviewProcessGuard()
    }

    /**
     * Call only after the user explicitly starts a chat round from the overlay.
     */
    fun forExplicitUserChat(): BubblePreviewFailureCircuit = circuitHolder.value

    /** Retains a controller whose live root could not be proven removed. */
    fun retainUnresolvedController(
        controller: Any,
        operation: String?,
        exceptionType: String?,
    ) {
        guardHolder.value.retainUnresolved(controller, operation, exceptionType)
    }

    fun isBlocked(): Boolean = guardHolder.value.isBlocked()

    fun fatalState(): BubblePreviewProcessFatalState = guardHolder.value.snapshot()
}
