package com.wwkhub.bluefin

import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Shared preference keys plus the one place that clamps, defaults, and migrates
 * the character's scale, opacity, and orb size.
 *
 * Size used to be stored as the named preset `small` / `medium` / `large`.
 * The canonical value is now the numeric [KEY_SCALE]; [KEY_SIZE] stays readable
 * so an installed copy keeps the user's choice on upgrade. Opacity is always
 * the character drawing's opacity, never a window alpha.
 */
internal object BluefinStore {
    const val PREFS = "bluefin"
    const val KEY_TIPS_DONE = "tips_done"
    const val KEY_SOUND = "sound_on"
    const val KEY_AUTO_REST = "overlay_auto_rest"
    const val KEY_ROAM = "roam_on"
    const val KEY_HIDDEN = "pet_hidden"

    /**
     * When on, the pet's touch window is dropped so taps and swipes over the
     * pet reach the app below. Off keeps the pet poking and draggable.
     */
    const val KEY_CLICK_THROUGH = "click_through"

    /**
     * Persisted user switch for the floating text chat bubble. It is
     * read only from the explicit chat/display path, never from show().
     */
    const val KEY_BUBBLE_PREVIEW = "bubble_preview"

    /** Legacy named-preset key. Read for migration; new writes go to [KEY_SCALE]. */
    const val KEY_SIZE = "pet_size"

    /** Canonical numeric scale in S 0.50–1.30. */
    const val KEY_SCALE = "pet_scale"

    /** Character drawing opacity in 0.00–1.00. */
    const val KEY_OPACITY = "pet_opacity"

    const val KEY_ORB_SIDE = "orb_side"
    const val KEY_ORB_Y_DP = "orb_y_dp"

    /** Function-orb diameter in whole dp. */
    const val KEY_ORB_SIZE_DP = "orb_size_dp"
    const val KEY_ORB_OPACITY = "orb_opacity"
    const val KEY_ORB_CLICK_THROUGH = "orb_click_through"
    const val ORB_OPACITY_MIN_PERCENT = 20

    /** Character appearance id; the built-in pack is [PackFormat.BUILT_IN_ID]. */
    const val KEY_CHARACTER_ID = "character_id"

    /** Per-pack dress-up scheme: one preset id, or axis option ids joined by '-'. */
    const val KEY_CHARACTER_SCHEME_PREFIX = "character_scheme_"

    /**
     * Off by default: reading replies aloud needs a downloaded engine, so it is
     * opt-in and never turns itself on.
     */
    const val KEY_SPEECH_ON = "speech_on"

    /** Selected voice pack id; falls back to the first bundled pack. */
    const val KEY_VOICE_PACK_ID = "voice_pack_id"

    const val KEY_OVERLAY = "overlay_on"
    const val KEY_OVERLAY_AWAITING = "overlay_awaiting_settings"
    const val KEY_OVERLAY_PROMPT_DISMISSED = "overlay_prompt_dismissed"

    const val SCALE_MIN = 0.50f
    const val SCALE_MAX = 1.30f
    const val SCALE_DEFAULT = 0.85f
    const val SCALE_STEP = 0.01f

    /** Stage-height anchors; they are not the ends of the live scale band. */
    const val SCALE_HEIGHT_ANCHOR_LO = 0.70f
    const val SCALE_HEIGHT_ANCHOR_HI = 1.00f

    const val OPACITY_MIN = 0.00f
    const val OPACITY_MAX = 1.00f
    const val OPACITY_DEFAULT = 1.00f
    const val OPACITY_STEP = 0.01f

    const val ORB_SIZE_MIN_DP = 36f
    const val ORB_SIZE_MAX_DP = 64f
    const val ORB_SIZE_DEFAULT_DP = 48f
    const val ORB_SIZE_STEP_DP = 1f

    /**
     * Clamps into [min]..[max]. NaN keeps the caller's default, while infinities
     * map to the nearest bound.
     */
    private fun clampToRange(value: Float, min: Float, max: Float, default: Float): Float {
        return when {
            value.isNaN() -> default
            value == Float.POSITIVE_INFINITY -> max
            value == Float.NEGATIVE_INFINITY -> min
            else -> value.coerceIn(min, max)
        }
    }

    // Round the hundredths count, then divide: multiplying 85 * 0.01f would
    // leave the same 0.84999996 representation this removes.
    private fun snapToHundredths(value: Float): Float =
        (value * 100f).roundToInt() / 100f

    private fun snapToWholeDp(value: Float): Float = value.roundToInt().toFloat()

    fun clampScale(value: Float): Float =
        snapToHundredths(clampToRange(value, SCALE_MIN, SCALE_MAX, SCALE_DEFAULT))

    fun clampOpacity(value: Float): Float =
        snapToHundredths(clampToRange(value, OPACITY_MIN, OPACITY_MAX, OPACITY_DEFAULT))

    fun clampOrbSizeDp(value: Float): Float =
        snapToWholeDp(clampToRange(value, ORB_SIZE_MIN_DP, ORB_SIZE_MAX_DP, ORB_SIZE_DEFAULT_DP))

    /** Continuous range clamp for layout geometry; stored/slider values use [clampScale]. */
    fun clampScaleContinuous(value: Float): Float =
        clampToRange(value, SCALE_MIN, SCALE_MAX, SCALE_DEFAULT)

    /** Maps an old named preset, or an already numeric value, into the live scale band. */
    fun scaleForLegacy(value: String?): Float {
        return when (value?.trim()?.lowercase()) {
            "small" -> 0.70f
            "medium" -> 0.85f
            "large" -> 1.00f
            else -> value?.trim()?.toFloatOrNull()?.let(::clampScale) ?: SCALE_DEFAULT
        }
    }

    /**
     * New numeric value wins when present and valid; otherwise migrate the legacy
     * preset/string. This is intentionally pure so JVM tests cover upgrades.
     */
    fun resolveScale(storedScale: Float?, legacySize: String?): Float {
        if (storedScale != null && storedScale.isFinite()) return clampScale(storedScale)
        return scaleForLegacy(legacySize)
    }

    fun resolveOpacity(storedOpacity: Float?): Float {
        if (storedOpacity != null && storedOpacity.isFinite()) return clampOpacity(storedOpacity)
        return OPACITY_DEFAULT
    }

    fun resolveOrbSizeDp(storedOrbSize: Float?): Float {
        if (storedOrbSize != null) return clampOrbSizeDp(storedOrbSize)
        return ORB_SIZE_DEFAULT_DP
    }

    fun readScale(prefs: SharedPreferences): Float {
        val stored = if (prefs.contains(KEY_SCALE)) prefs.getFloat(KEY_SCALE, SCALE_DEFAULT) else null
        val resolved = resolveScale(stored, prefs.getString(KEY_SIZE, null))
        // Persist the numeric form so the next read no longer depends on the preset.
        if (stored == null || stored != resolved) prefs.edit().putFloat(KEY_SCALE, resolved).apply()
        return resolved
    }

    fun writeScale(prefs: SharedPreferences, value: Float) {
        prefs.edit().putFloat(KEY_SCALE, clampScale(value)).apply()
    }

    fun readOpacity(prefs: SharedPreferences): Float {
        val stored = if (prefs.contains(KEY_OPACITY)) prefs.getFloat(KEY_OPACITY, OPACITY_DEFAULT) else null
        val resolved = resolveOpacity(stored)
        if (stored == null || stored != resolved) prefs.edit().putFloat(KEY_OPACITY, resolved).apply()
        return resolved
    }

    fun writeOpacity(prefs: SharedPreferences, value: Float) {
        prefs.edit().putFloat(KEY_OPACITY, clampOpacity(value)).apply()
    }

    fun readOrbSizeDp(prefs: SharedPreferences): Float {
        val stored = if (prefs.contains(KEY_ORB_SIZE_DP)) {
            prefs.getFloat(KEY_ORB_SIZE_DP, ORB_SIZE_DEFAULT_DP)
        } else {
            null
        }
        val resolved = resolveOrbSizeDp(stored)
        // Persist the whole-dp form so the next read is stable and in band.
        if (stored == null || stored != resolved) {
            prefs.edit().putFloat(KEY_ORB_SIZE_DP, resolved).apply()
        }
        return resolved
    }

    fun writeOrbSizeDp(prefs: SharedPreferences, value: Float) {
        prefs.edit().putFloat(KEY_ORB_SIZE_DP, clampOrbSizeDp(value)).apply()
    }

    fun readOrbOpacityPercent(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_ORB_OPACITY, 100).coerceIn(ORB_OPACITY_MIN_PERCENT, 100)

    fun writeOrbOpacityPercent(prefs: SharedPreferences, percent: Int) {
        prefs.edit().putInt(KEY_ORB_OPACITY, percent.coerceIn(ORB_OPACITY_MIN_PERCENT, 100)).apply()
    }

    fun readOrbClickThrough(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_ORB_CLICK_THROUGH, false)

    fun writeOrbClickThrough(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ORB_CLICK_THROUGH, enabled).apply()
    }

    /** Absent means off: the pet keeps the rc2 poke/drag behavior by default. */
    fun readClickThrough(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_CLICK_THROUGH, false)

    fun writeClickThrough(prefs: SharedPreferences, clickThrough: Boolean) {
        prefs.edit().putBoolean(KEY_CLICK_THROUGH, clickThrough).apply()
    }

    /** Default on for the final release; the compile master gate stays false until D. */
    fun readBubblePreview(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BUBBLE_PREVIEW, true)

    fun writeBubblePreview(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUBBLE_PREVIEW, enabled).apply()
    }

    fun readSpeechOn(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SPEECH_ON, false)

    fun writeSpeechOn(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEECH_ON, enabled).apply()
    }

    fun readVoicePackId(prefs: SharedPreferences): String? =
        prefs.getString(KEY_VOICE_PACK_ID, null)?.takeIf { PackPaths.validId(it) }

    fun writeVoicePackId(prefs: SharedPreferences, id: String?) {
        val editor = prefs.edit()
        if (id == null) editor.remove(KEY_VOICE_PACK_ID) else editor.putString(KEY_VOICE_PACK_ID, id)
        editor.apply()
    }

    /** 0..80 whole-percent steps across S 0.50–1.30; default 0.85 is progress 35. */
    fun scaleToProgress(scale: Float): Int {
        return (((clampScale(scale) - SCALE_MIN) / SCALE_STEP).roundToInt()).coerceIn(0, 80)
    }

    fun progressToScale(progress: Int): Float {
        return clampScale(SCALE_MIN + progress.coerceIn(0, 80) * SCALE_STEP)
    }

    /**
     * 0..100 whole-percent steps with the UI direction reversed: left (0) is
     * fully solid 100%, right (100) is fully transparent 0%. Moving right is fainter.
     */
    fun opacityToProgress(opacity: Float): Int {
        return (((OPACITY_MAX - clampOpacity(opacity)) / OPACITY_STEP).roundToInt()).coerceIn(0, 100)
    }

    fun progressToOpacity(progress: Int): Float {
        return clampOpacity(OPACITY_MAX - progress.coerceIn(0, 100) * OPACITY_STEP)
    }

    /** 0..28 whole-dp steps across 36..64dp; default 48dp is progress 12. */
    fun orbSizeToProgress(orbSizeDp: Float): Int {
        return (((clampOrbSizeDp(orbSizeDp) - ORB_SIZE_MIN_DP) / ORB_SIZE_STEP_DP).roundToInt())
            .coerceIn(0, 28)
    }

    fun progressToOrbSize(progress: Int): Float {
        return clampOrbSizeDp(ORB_SIZE_MIN_DP + progress.coerceIn(0, 28) * ORB_SIZE_STEP_DP)
    }
}
