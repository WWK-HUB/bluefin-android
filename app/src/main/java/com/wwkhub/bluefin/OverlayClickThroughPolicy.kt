package com.wwkhub.bluefin

/**
 * Decides whether the pet gets a native hit window.
 *
 * Click-through is the positive switch: when it is on, the pet layer is
 * dropped and taps or swipes over the pet go to the app underneath. When it is
 * off, a visible pet with published hits keeps the rc2 poke/drag target.
 */
internal object OverlayClickThroughPolicy {
    fun shouldAttachHit(clickThrough: Boolean, hasHits: Boolean): Boolean =
        !clickThrough && hasHits
}
