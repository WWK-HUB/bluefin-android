package com.wwkhub.bluefin

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluefinStoreTest {
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var edits = 0

        fun put(key: String, value: Any?) {
            values[key] = value
        }

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor {
            edits += 1
            return Editor()
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                updates[key] = value
                return this
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                updates[key] = values
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                updates[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                updates[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                updates[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                updates[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                updates[key] = REMOVED
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                updates.forEach { (key, value) ->
                    if (value === REMOVED) values.remove(key) else values[key] = value
                }
            }
        }

        companion object {
            private val REMOVED = Any()
        }
    }

    @Test
    fun scaleDefaultsClampsAndMigratesNamedPresets() {
        assertEquals(0.85f, BluefinStore.resolveScale(null, null))
        assertEquals(0.70f, BluefinStore.resolveScale(null, "small"))
        assertEquals(0.85f, BluefinStore.resolveScale(null, "medium"))
        assertEquals(1.00f, BluefinStore.resolveScale(null, "large"))
        assertEquals(0.50f, BluefinStore.resolveScale(null, "0.20"))
        assertEquals(1.30f, BluefinStore.resolveScale(null, "1.50"))
        assertEquals(0.73f, BluefinStore.resolveScale(null, "0.73"))
        assertEquals(0.85f, BluefinStore.resolveScale(null, "not-a-number"))
        assertEquals(0.50f, BluefinStore.clampScale(0.10f))
        assertEquals(1.30f, BluefinStore.clampScale(2f))
        assertEquals(0.85f, BluefinStore.clampScale(Float.NaN))
        assertEquals(1.30f, BluefinStore.clampScale(Float.POSITIVE_INFINITY))
        assertEquals(0.50f, BluefinStore.clampScale(Float.NEGATIVE_INFINITY))
        assertEquals(0.85f, BluefinStore.clampScale(0.854f))
        assertEquals(0.70f, BluefinStore.clampScale(0.70f))
        assertEquals(1.00f, BluefinStore.clampScale(1.00f))
        assertEquals(1.20f, BluefinStore.clampScale(1.20f))
        assertEquals(0.50f, BluefinStore.SCALE_MIN)
        assertEquals(1.30f, BluefinStore.SCALE_MAX)
        assertEquals(0.85f, BluefinStore.SCALE_DEFAULT)
        assertEquals(0.01f, BluefinStore.SCALE_STEP)
        assertEquals(0.70f, BluefinStore.SCALE_HEIGHT_ANCHOR_LO)
        assertEquals(1.00f, BluefinStore.SCALE_HEIGHT_ANCHOR_HI)
    }

    @Test
    fun numericScaleWinsOverTheLegacyPresetAfterMigration() {
        assertEquals(0.78f, BluefinStore.resolveScale(0.78f, "large"))
        assertEquals(0.50f, BluefinStore.resolveScale(0.40f, "large"))
        assertEquals(1.20f, BluefinStore.resolveScale(1.20f, "small"))
        assertEquals(0.85f, BluefinStore.resolveScale(Float.NaN, null))
        assertEquals(1.00f, BluefinStore.resolveScale(Float.NaN, "large"))
    }

    @Test
    fun opacityDefaultsToFullySolidAndAllowsFullTransparency() {
        assertEquals(1.00f, BluefinStore.resolveOpacity(null))
        assertEquals(1.00f, BluefinStore.clampOpacity(1.00f))
        assertEquals(0.30f, BluefinStore.clampOpacity(0.30f))
        assertEquals(0.00f, BluefinStore.clampOpacity(0.00f))
        assertEquals(0.00f, BluefinStore.clampOpacity(-4f))
        assertEquals(1.00f, BluefinStore.clampOpacity(1.80f))
        assertEquals(1.00f, BluefinStore.clampOpacity(Float.NaN))
        assertEquals(1.00f, BluefinStore.clampOpacity(Float.POSITIVE_INFINITY))
        assertEquals(0.00f, BluefinStore.clampOpacity(Float.NEGATIVE_INFINITY))
        assertEquals(0.85f, BluefinStore.clampOpacity(0.854f))
        assertEquals(0.05f, BluefinStore.resolveOpacity(0.05f))
        assertEquals(0.00f, BluefinStore.resolveOpacity(0.00f))
        assertEquals(0.00f, BluefinStore.OPACITY_MIN)
        assertEquals(1.00f, BluefinStore.OPACITY_MAX)
        assertEquals(1.00f, BluefinStore.OPACITY_DEFAULT)
        assertEquals(0.01f, BluefinStore.OPACITY_STEP)
    }

    @Test
    fun orbSizeDefaultsClampsAndSnapsToWholeDp() {
        assertEquals(48f, BluefinStore.resolveOrbSizeDp(null))
        assertEquals(48f, BluefinStore.clampOrbSizeDp(48f))
        assertEquals(36f, BluefinStore.clampOrbSizeDp(35f))
        assertEquals(64f, BluefinStore.clampOrbSizeDp(80f))
        assertEquals(36f, BluefinStore.clampOrbSizeDp(Float.NEGATIVE_INFINITY))
        assertEquals(64f, BluefinStore.clampOrbSizeDp(Float.POSITIVE_INFINITY))
        assertEquals(48f, BluefinStore.clampOrbSizeDp(Float.NaN))
        assertEquals(48f, BluefinStore.clampOrbSizeDp(47.6f))
        assertEquals(37f, BluefinStore.clampOrbSizeDp(36.6f))
        assertEquals(36f, BluefinStore.ORB_SIZE_MIN_DP)
        assertEquals(64f, BluefinStore.ORB_SIZE_MAX_DP)
        assertEquals(48f, BluefinStore.ORB_SIZE_DEFAULT_DP)
        assertEquals(1f, BluefinStore.ORB_SIZE_STEP_DP)
    }

    @Test
    fun readingOrbSizeDefaultsToFortyEightAndOnlyRewritesWhenNeeded() {
        val prefs = FakePrefs()
        assertEquals(48f, BluefinStore.readOrbSizeDp(prefs))
        assertEquals(48f, prefs.getFloat(BluefinStore.KEY_ORB_SIZE_DP, -1f))
        assertEquals(1, prefs.edits)

        val editsAfterFirstRead = prefs.edits
        assertEquals(48f, BluefinStore.readOrbSizeDp(prefs))
        assertEquals(editsAfterFirstRead, prefs.edits)

        prefs.put(BluefinStore.KEY_ORB_SIZE_DP, 80f)
        assertEquals(64f, BluefinStore.readOrbSizeDp(prefs))
        assertEquals(64f, prefs.getFloat(BluefinStore.KEY_ORB_SIZE_DP, -1f))

        prefs.put(BluefinStore.KEY_ORB_SIZE_DP, 52f)
        val editsBeforeInBandRead = prefs.edits
        assertEquals(52f, BluefinStore.readOrbSizeDp(prefs))
        assertEquals(editsBeforeInBandRead, prefs.edits)

        BluefinStore.writeOrbSizeDp(prefs, 70f)
        assertEquals(64f, prefs.getFloat(BluefinStore.KEY_ORB_SIZE_DP, -1f))
    }

    @Test
    fun sliderProgressMapsEveryWholePercentInsideTheBands() {
        assertEquals(0, BluefinStore.scaleToProgress(0.20f))
        assertEquals(0, BluefinStore.scaleToProgress(0.50f))
        assertEquals(35, BluefinStore.scaleToProgress(0.85f))
        assertEquals(70, BluefinStore.scaleToProgress(1.20f))
        assertEquals(80, BluefinStore.scaleToProgress(1.30f))
        assertEquals(80, BluefinStore.scaleToProgress(1.80f))
        assertEquals(0.50f, BluefinStore.progressToScale(0))
        assertEquals(0.85f, BluefinStore.progressToScale(35))
        assertEquals(1.30f, BluefinStore.progressToScale(80))
        assertEquals(0.50f, BluefinStore.progressToScale(-5))
        assertEquals(1.30f, BluefinStore.progressToScale(99))

        assertEquals(0, BluefinStore.opacityToProgress(1.00f))
        assertEquals(90, BluefinStore.opacityToProgress(0.10f))
        assertEquals(100, BluefinStore.opacityToProgress(0.00f))
        assertEquals(1.00f, BluefinStore.progressToOpacity(0))
        assertEquals(0.85f, BluefinStore.progressToOpacity(15))
        assertEquals(0.00f, BluefinStore.progressToOpacity(100))
        assertEquals(1.00f, BluefinStore.progressToOpacity(-1))
        assertEquals(0.00f, BluefinStore.progressToOpacity(101))

        assertEquals(0, BluefinStore.orbSizeToProgress(36f))
        assertEquals(12, BluefinStore.orbSizeToProgress(48f))
        assertEquals(28, BluefinStore.orbSizeToProgress(64f))
        assertEquals(0, BluefinStore.orbSizeToProgress(20f))
        assertEquals(28, BluefinStore.orbSizeToProgress(80f))
        assertEquals(36f, BluefinStore.progressToOrbSize(0))
        assertEquals(48f, BluefinStore.progressToOrbSize(12))
        assertEquals(64f, BluefinStore.progressToOrbSize(28))
        assertEquals(36f, BluefinStore.progressToOrbSize(-1))
        assertEquals(64f, BluefinStore.progressToOrbSize(99))
    }

    @Test
    fun storedScaleAndOpacityValuesRoundTripWithoutBeingForcedToTheEndpoints() {
        val prefs = FakePrefs()
        BluefinStore.writeScale(prefs, 0.85f)
        assertEquals(0.85f, BluefinStore.readScale(prefs))
        BluefinStore.writeScale(prefs, 1.20f)
        assertEquals(1.20f, BluefinStore.readScale(prefs))
        BluefinStore.writeOpacity(prefs, 0.42f)
        assertEquals(0.42f, BluefinStore.readOpacity(prefs))
        BluefinStore.writeOpacity(prefs, 0.00f)
        assertEquals(0.00f, BluefinStore.readOpacity(prefs))
        assertFalse(prefs.values.containsKey("unexpected"))
        assertTrue(prefs.values.containsKey(BluefinStore.KEY_SCALE))
    }

    @Test
    fun clickThroughIsOffByDefaultAndRoundTrips() {
        val prefs = FakePrefs()
        assertEquals("click_through", BluefinStore.KEY_CLICK_THROUGH)
        assertFalse(
            "rc1's touch_enabled key must not be reused",
            BluefinStore.KEY_CLICK_THROUGH == "touch_enabled",
        )
        assertFalse(BluefinStore.readClickThrough(prefs))
        assertFalse(
            "an unconfigured model and a fresh install must stay interactive",
            prefs.contains(BluefinStore.KEY_CLICK_THROUGH),
        )

        BluefinStore.writeClickThrough(prefs, true)
        assertTrue(BluefinStore.readClickThrough(prefs))
        assertTrue(prefs.getBoolean(BluefinStore.KEY_CLICK_THROUGH, false))

        BluefinStore.writeClickThrough(prefs, false)
        assertFalse(BluefinStore.readClickThrough(prefs))
        assertFalse(prefs.getBoolean(BluefinStore.KEY_CLICK_THROUGH, true))
    }
}
