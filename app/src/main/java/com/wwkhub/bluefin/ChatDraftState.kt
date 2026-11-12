package com.wwkhub.bluefin

/** Immutable value of the single process-scoped unsent draft. */
data class ChatDraftSnapshot(
    val text: String,
    val revision: Long,
) {
    val isEmpty: Boolean get() = text.isEmpty()
}

/**
 * One-use handoff capability. It deliberately contains no draft text: an old
 * Intent/EXTRA can carry a token, but can never overwrite the latest edit.
 */
data class ChatDraftHandoff(
    val token: Long,
    val revision: Long,
)

/** Revision/text pair captured when a send attempt starts. */
data class ChatDraftCapture(
    val revision: Long,
    val text: String,
)

enum class ChatDraftSendOutcome {
    STARTED,
    EMPTY,
    BUSY,
    NOT_CONFIGURED,
}

/**
 * Pure process-scoped draft state, with no Android, persistence, callback,
 * coroutine or timer dependency. The host keeps exactly one instance and calls
 * it from the UI thread.
 *
 * Every explicit [edit] is a new monotonic revision. Only a successful STARTED
 * result clears, and only when the captured revision is still current. Failed
 * results never mutate the draft. Handoff tokens are one-use and carry no text.
 */
class ChatDraftState(
    private val maxChars: Int = MAX_DRAFT_CHARS,
) {
    init {
        require(maxChars >= 2) { "maxChars must be at least 2" }
    }

    private var currentText: String = ""
    private var currentRevision: Long = 0L
    private var nextToken: Long = 0L
    private var pendingHandoffToken: Long? = null

    val text: String get() = currentText
    val revision: Long get() = currentRevision
    val isEmpty: Boolean get() = currentText.isEmpty()

    /** Accept the user's latest edit; never an older-wins merge. */
    fun edit(value: String): ChatDraftSnapshot {
        currentText = clamp(value)
        currentRevision = bumped(currentRevision)
        return snapshot()
    }

    fun snapshot(): ChatDraftSnapshot = ChatDraftSnapshot(currentText, currentRevision)

    /** Issue a fresh one-use token; an earlier unconsumed token is invalidated. */
    fun issueHandoff(): ChatDraftHandoff {
        val handoff = ChatDraftHandoff(++nextToken, currentRevision)
        pendingHandoffToken = handoff.token
        return handoff
    }

    /**
     * Consume a token at most once and always return the latest draft. A stale
     * token, an empty Intent or a rotation replay cannot restore old text.
     */
    fun consumeHandoff(token: Long): ChatDraftSnapshot {
        if (pendingHandoffToken == token) pendingHandoffToken = null
        return snapshot()
    }

    /** Reopen/rebind entry point. It may consume a token but never edits. */
    fun rebind(token: Long? = null): ChatDraftSnapshot {
        if (token != null) consumeHandoff(token)
        return snapshot()
    }

    fun captureSend(): ChatDraftCapture = ChatDraftCapture(currentRevision, currentText)

    /**
     * STARTED clears only the exact captured revision/text. EMPTY, BUSY and
     * NOT_CONFIGURED leave the draft untouched for the next attempt.
     */
    fun onSendOutcome(capture: ChatDraftCapture, outcome: ChatDraftSendOutcome): ChatDraftSnapshot {
        if (outcome == ChatDraftSendOutcome.STARTED &&
            capture.revision == currentRevision &&
            capture.text == currentText &&
            currentText.isNotEmpty()
        ) {
            currentText = ""
            currentRevision = bumped(currentRevision)
            pendingHandoffToken = null
        }
        return snapshot()
    }

    fun onSendOutcome(capturedRevision: Long, outcome: ChatDraftSendOutcome): ChatDraftSnapshot =
        onSendOutcome(ChatDraftCapture(capturedRevision, currentText), outcome)

    private fun clamp(value: String): String {
        if (value.length <= maxChars) return value
        var end = maxChars
        if (Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])
        ) {
            end -= 1
        }
        return value.substring(0, end)
    }

    private fun bumped(revision: Long): Long =
        if (revision == Long.MAX_VALUE) revision else revision + 1L

    companion object {
        const val MAX_DRAFT_CHARS: Int = 4_000
    }
}

/**
 * Process-lifetime owner of the single unsent draft.
 *
 * The state is lazy so app startup, the overlay show/constructor path and the
 * optional bubble module never create or touch a draft. Only the explicit chat
 * entry (and the chat screen it opens) may access this object.
 */
internal object ChatDraft {
    val state: ChatDraftState by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChatDraftState()
    }
}
