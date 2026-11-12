package com.wwkhub.bluefin.bubblepreview

/**
 * Compile-time master gate for the optional 0.7 bubble-preview module.
 *
 * Stage D releases the module. The gate itself stays a pure predicate so a
 * closed build can be tested without constructing any optional state: a false
 * gate must never invoke its factory (zero preferences/resources/circuit/
 * listeners). No opening-path file references this package.
 */
internal object BubblePreviewModule {
    const val MASTER_GATE_ENABLED: Boolean = true
    const val AUDIT_WINDOW_TITLE: String = "BluefinOverlay:bubble-preview"
    const val FAILURE_THRESHOLD: Int = 3
    const val SUSTAINED_SUCCESS_MS: Long = 2_000L

    fun isEnabled(): Boolean = MASTER_GATE_ENABLED

    /**
     * Pure gate predicate. When [enabled] is false the factory must not run at
     * all, so tests can prove a closed build performs zero optional work.
     */
    fun <T> whenEnabled(enabled: Boolean, factory: () -> T): T? =
        if (enabled) factory() else null
}

/**
 * The only intended host entry point, and only after the user explicitly sent
 * a chat round and the existing session returned `STARTED`.
 *
 * The pure gate is checked before the lazy process state is touched, so a
 * closed build cannot create the circuit, guard or any task.
 */
internal object BubblePreviewExplicitChatEntry {
    fun failureCircuitOrNull(): BubblePreviewFailureCircuit? =
        BubblePreviewModule.whenEnabled(BubblePreviewModule.MASTER_GATE_ENABLED) {
            // An unresolved root blocks every future controller/attach for the
            // rest of the process. No retry is attempted after a cleanup fault.
            if (BubblePreviewProcessSession.isBlocked()) return@whenEnabled null
            BubblePreviewProcessSession.forExplicitUserChat()
        }
}