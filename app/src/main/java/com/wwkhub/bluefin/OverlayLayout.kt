package com.wwkhub.bluefin

import kotlin.math.roundToInt

/**
 * Geometry and toggle policy for the system overlay.
 *
 * Stage height is extrapolated linearly from the two anchors S 0.70 -> 220 CSS
 * px and S 1.00 -> 290 CSS px (so S 0.85 -> 255, S 0.50 -> 173, and S 1.30 ->
 * 360). This mirrors `stageHeight()` in `native/bridge.js`. The pack's own
 * floor is `stageHeight - 40`, so the floor stays 40px above the stage bottom.
 * Width is at least 360dp when the screen can hold it.
 *
 * Flag values are the platform constants. [OverlayWindow] checks them
 * against `WindowManager.LayoutParams` before the window is added.
 */
internal object OverlayLayout {
    const val MIN_STAGE_WIDTH_DP = 360
    // Anchor heights at S 0.70 and S 1.00; the live scale band extends beyond them.
    const val MIN_STAGE_HEIGHT_DP = 220
    const val MAX_STAGE_HEIGHT_DP = 290
    const val CHROME_DP = 48
    const val FLOOR_INSET_PX = 40
    const val INITIAL_BOTTOM_MARGIN_DP = 48
    const val ORB_SIZE_DP = 48
    const val ORB_MARGIN_DP = 12
    const val ORB_SAFE_TOP_DP = 48
    const val ORB_ROAM_CLEARANCE_DP = 330
    const val ORB_PANEL_WIDTH_DP = 292
    const val ORB_PANEL_MAX_HEIGHT_DP = 460
    const val ORB_PANEL_MIN_HEIGHT_DP = 96

    const val TYPE_APPLICATION_OVERLAY = 2038
    const val FLAG_NOT_FOCUSABLE = 8
    const val FLAG_NOT_TOUCHABLE = 16
    const val FLAG_NOT_TOUCH_MODAL = 32
    const val FLAG_LAYOUT_IN_SCREEN = 256
    const val FLAG_HARDWARE_ACCELERATED = 16_777_216

    const val WINDOW_FLAGS =
        FLAG_NOT_FOCUSABLE or
            FLAG_NOT_TOUCH_MODAL or
            FLAG_LAYOUT_IN_SCREEN or
            FLAG_HARDWARE_ACCELERATED

    // Android 12's InputDispatcher allows pass-through touches only while the
    // combined opacity of untrusted system-alert windows at the touch point is
    // <= the device's maximum obscuring opacity. The platform default is 0.8;
    // API 31+ exposes the effective value through
    // InputManager.getMaximumObscuringOpacityForTouch().
    const val MAX_OBSCURING_OPACITY_FOR_TOUCH = 0.8f

    const val WINDOW_TITLE_STAGE = "BluefinOverlay:stage"
    const val WINDOW_TITLE_PET = "BluefinOverlay:pet"
    const val WINDOW_TITLE_ORB = "BluefinOverlay:orb"

    // Shown: full-screen render stage, pet hit target, function orb/panel.
    const val EXPECTED_WINDOW_COUNT = 3

    /**
     * The full-screen stage is NOT_TOUCHABLE so input reaches the app below it.
     * On Android 12+ the stage still counts as an obscuring system-alert window,
     * so its alpha must not exceed the maximum reported by
     * InputManager.getMaximumObscuringOpacityForTouch(). Pre-12 has no such
     * check.
     */
    fun stageAlpha(
        sdkInt: Int,
        maximumObscuringOpacityForTouch: Float = MAX_OBSCURING_OPACITY_FOR_TOUCH,
    ): Float {
        if (sdkInt < 31) return 1f
        return maximumObscuringOpacityForTouch
            .takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f)
            ?: MAX_OBSCURING_OPACITY_FOR_TOUCH
    }

    fun effectivePetOpacity(requestedOpacity: Float, stageAlpha: Float): Float =
        minOf(BluefinStore.clampOpacity(requestedOpacity), stageAlpha)

    // The compositor multiplies the character and window opacities.
    fun characterOpacity(requestedOpacity: Float, stageAlpha: Float): Float =
        if (stageAlpha > 0f) effectivePetOpacity(requestedOpacity, stageAlpha) / stageAlpha else 0f

    enum class Toggle {
        Show,
        Hide,
        NeedPermission,
    }

    fun stageHeightDp(scale: Float): Int {
        val s = BluefinStore.clampScaleContinuous(scale)
        val anchorLo = BluefinStore.SCALE_HEIGHT_ANCHOR_LO
        val anchorSpan = BluefinStore.SCALE_HEIGHT_ANCHOR_HI - anchorLo
        val heightSpan = (MAX_STAGE_HEIGHT_DP - MIN_STAGE_HEIGHT_DP).toFloat()
        return (MIN_STAGE_HEIGHT_DP + (s - anchorLo) / anchorSpan * heightSpan).roundToInt()
    }

    fun chromePx(density: Float): Int = (CHROME_DP * density.coerceAtLeast(0.5f)).roundToInt().coerceAtLeast(1)

    fun windowSizePx(
        scale: Float,
        density: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): Pair<Int, Int> {
        val d = density.coerceAtLeast(0.5f)
        val screenW = screenWidthPx.coerceAtLeast(1)
        val screenH = screenHeightPx.coerceAtLeast(1)
        val widthDp = minOf(MIN_STAGE_WIDTH_DP.toFloat(), screenW / d)
        var heightDp = stageHeightDp(scale) + CHROME_DP.toFloat()
        val screenHeightDp = screenH / d
        // Display-only clamp: the saved scale is not rewritten, so rotating back
        // to a taller screen restores the requested stage height.
        if (heightDp > screenHeightDp) heightDp = screenHeightDp
        val widthPx = (widthDp * d).roundToInt().coerceIn(1, screenW)
        val heightPx = (heightDp * d).roundToInt().coerceIn(1, screenH)
        return widthPx to heightPx
    }

    fun defaultOrbYDp(screenHeightPx: Int, density: Float): Float {
        val d = density.coerceAtLeast(0.5f)
        val screenHeightDp = screenHeightPx.coerceAtLeast(1) / d
        return (screenHeightDp * 0.30f).coerceAtLeast(ORB_MARGIN_DP.toFloat())
    }

    fun orbSafeBottomDp(
        screenHeightPx: Int,
        density: Float,
        orbSizeDp: Float = ORB_SIZE_DP.toFloat(),
    ): Float {
        val d = density.coerceAtLeast(0.5f)
        val sizeDp = BluefinStore.clampOrbSizeDp(orbSizeDp)
        val screenHeightDp = screenHeightPx.coerceAtLeast(1) / d
        return (screenHeightDp - ORB_ROAM_CLEARANCE_DP).coerceAtLeast(
            ORB_SAFE_TOP_DP + sizeDp + ORB_MARGIN_DP.toFloat(),
        )
    }

    fun clampOrbYDp(
        yDp: Float,
        safeBottomDp: Float,
        orbSizeDp: Float = ORB_SIZE_DP.toFloat(),
    ): Float {
        val sizeDp = BluefinStore.clampOrbSizeDp(orbSizeDp)
        val maxY = (safeBottomDp - sizeDp).coerceAtLeast(ORB_MARGIN_DP.toFloat())
        val value = if (yDp.isFinite()) yDp else ORB_MARGIN_DP.toFloat()
        return value.coerceIn(ORB_MARGIN_DP.toFloat(), maxY)
    }

    fun isLandscape(screenWidthPx: Int, screenHeightPx: Int): Boolean =
        screenWidthPx > screenHeightPx

    /**
     * Height of the open function panel. Landscape uses the screen's short side
     * minus a small top inset and bottom margin, and never subtracts the portrait
     * roam-band clearance (which would leave only the action row visible).
     * Portrait keeps the old 460dp cap but may use the full height too.
     */
    fun orbPanelHeightPx(screenHeightPx: Int, density: Float, landscape: Boolean): Int {
        val d = density.coerceAtLeast(0.5f)
        val screenH = screenHeightPx.coerceAtLeast(1)
        val top = (ORB_SAFE_TOP_DP * d).roundToInt()
        val bottom = (ORB_MARGIN_DP * d).roundToInt()
        val available = (screenH - top - bottom).coerceAtLeast(1)
        if (landscape) return available
        val cap = (ORB_PANEL_MAX_HEIGHT_DP * d).roundToInt().coerceAtLeast(1)
        return minOf(cap, available)
    }

    fun orbPanelTopPx(
        screenHeightPx: Int,
        density: Float,
        landscape: Boolean,
        panelHeightPx: Int,
        orbYDp: Float,
    ): Int {
        val d = density.coerceAtLeast(0.5f)
        val screenH = screenHeightPx.coerceAtLeast(1)
        val topInset = (ORB_SAFE_TOP_DP * d).roundToInt()
        val bottom = (ORB_MARGIN_DP * d).roundToInt()
        val maxTop = (screenH - panelHeightPx.coerceAtLeast(1) - bottom).coerceAtLeast(0)
        if (landscape) return topInset.coerceAtMost(maxTop)
        val orbY = if (orbYDp.isFinite()) (orbYDp * d).roundToInt() else topInset
        return minOf(maxOf(topInset, orbY), maxTop)
    }

    fun orbPanelWidthPx(screenWidthPx: Int, density: Float, orbSizeDp: Float): Int {
        val d = density.coerceAtLeast(0.5f)
        val size = (BluefinStore.clampOrbSizeDp(orbSizeDp) * d).roundToInt()
        val margin = (ORB_MARGIN_DP * d).roundToInt()
        val available = (screenWidthPx.coerceAtLeast(1) - size - margin * 2).coerceAtLeast(1)
        val preferred = (ORB_PANEL_WIDTH_DP * d).roundToInt().coerceAtLeast(1)
        return minOf(preferred, available)
    }

    /**
     * Same formula as `preferredScale()` in `assets/bluefin/index.html`.
     * Inputs are CSS pixels. With a device-width viewport those match dp.
     */
    fun preferredScale(stageWidthCss: Float, stageHeightCss: Float): Float {
        if (stageHeightCss < 40f || stageWidthCss < 40f) return 1f
        val raw = minOf((stageHeightCss - 28f) / 262f, (stageWidthCss - 12f) / 190f)
        val clamped = raw.coerceIn(0.48f, 1.35f)
        return (clamped * 100f).roundToInt() / 100f
    }

    fun clamp(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> {
        val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
        val maxY = (screenHeight - windowHeight).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    fun initialPosition(
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
    ): Pair<Int, Int> {
        val margin = (INITIAL_BOTTOM_MARGIN_DP * density.coerceAtLeast(0.5f)).roundToInt()
        val x = ((screenWidth - windowWidth) / 2).coerceAtLeast(0)
        val y = (screenHeight - windowHeight - margin).coerceAtLeast(0)
        return clamp(x, y, windowWidth, windowHeight, screenWidth, screenHeight)
    }

    fun decideToggle(showing: Boolean, canDrawOverlays: Boolean): Toggle = when {
        showing -> Toggle.Hide
        canDrawOverlays -> Toggle.Show
        else -> Toggle.NeedPermission
    }
}

/**
 * Permission handoff that can be saved and restored.
 *
 * Opening system settings arms a single re-check. Declining in the app, or
 * coming back without permission, dismisses the guide until Settings resets it.
 */
internal class OverlayPermissionSession(
    awaiting: Boolean = false,
    dismissed: Boolean = false,
) {
    var awaitingSettings: Boolean = awaiting
        private set

    var promptDismissed: Boolean = dismissed
        private set

    fun shouldOfferGuide(): Boolean = !promptDismissed

    fun onOpenSettings() {
        awaitingSettings = true
    }

    fun onDeclinedInApp() {
        awaitingSettings = false
        promptDismissed = true
    }

    fun onResetPrompt() {
        awaitingSettings = false
        promptDismissed = false
    }

    fun onResume(canDraw: Boolean): Result {
        if (!awaitingSettings) return Result.Idle
        awaitingSettings = false
        return if (canDraw) {
            promptDismissed = false
            Result.Granted
        } else {
            promptDismissed = true
            Result.Denied
        }
    }

    enum class Result {
        Idle,
        Granted,
        Denied,
    }
}
