package com.wwkhub.bluefin

/**
 * Bounded recovery policy for an overlay that the system detached on its own.
 *
 * A system detach (for example when the notification shade opens) is not a
 * user hide. Recovery may run only while the overlay is still wanted,
 * permission is still granted, and no window is currently showing. After
 * [MAX_ATTEMPTS] the caller must settle the overlay as disabled instead of
 * leaving the preference enabled with no window.
 */
internal object OverlayReattachPolicy {
    const val MAX_ATTEMPTS = 3
    const val STABLE_WINDOW_MS = 2_000L

    private val RETRY_DELAYS_MS = longArrayOf(0L, 250L, 1_000L)

    enum class Detach {
        Retry,
        Exhausted,
    }

    enum class Retry {
        Reattach,
        Skip,
        PermissionLost,
    }

    fun onDetach(attempts: Int): Detach =
        if (attempts < MAX_ATTEMPTS) Detach.Retry else Detach.Exhausted

    fun onRetry(enabled: Boolean, permissionGranted: Boolean, showing: Boolean): Retry = when {
        !permissionGranted -> Retry.PermissionLost
        !enabled || showing -> Retry.Skip
        else -> Retry.Reattach
    }

    fun delayForAttempt(attempt: Int): Long =
        RETRY_DELAYS_MS[attempt.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
}
