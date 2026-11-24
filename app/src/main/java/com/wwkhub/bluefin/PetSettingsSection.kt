package com.wwkhub.bluefin

import android.content.SharedPreferences
import android.view.View
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

internal class PetSettingsSection(
    private val root: View,
    private val prefs: SharedPreferences,
    private val canWalk: () -> Boolean,
) {
    private val roam = root.findViewById<CompoundButton>(R.id.roam_switch)
    private val hide = root.findViewById<CompoundButton>(R.id.hide_switch)
    private val sound = root.findViewById<CompoundButton>(R.id.sound_switch)
    private val touchThrough = root.findViewById<CompoundButton>(R.id.pet_touch_through_switch)
    private val autoRest = root.findViewById<CompoundButton>(R.id.auto_rest_switch)
    private val size = root.findViewById<SeekBar>(R.id.size_slider)
    private val opacity = root.findViewById<SeekBar>(R.id.opacity_slider)
    private val orbSize = root.findViewById<SeekBar>(R.id.orb_size_slider)
    private val sizeValue = root.findViewById<TextView>(R.id.size_value)
    private val opacityValue = root.findViewById<TextView>(R.id.opacity_value)
    private val orbSizeValue = root.findViewById<TextView>(R.id.orb_size_value)
    private val orbTouch = OrbTouchSettings(root, prefs)
    private var syncing = false
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in CONTROL_KEYS) refresh()
    }

    init {
        root.findViewById<View>(R.id.orb_touch_help).visibility = View.GONE
        roam.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            if (checked && !canWalk()) {
                Toast.makeText(root.context, R.string.character_cannot_walk, Toast.LENGTH_SHORT).show()
                refresh()
            } else prefs.edit().putBoolean(BluefinStore.KEY_ROAM, checked).apply()
        }
        bindToggle(hide, BluefinStore.KEY_HIDDEN)
        bindToggle(sound, BluefinStore.KEY_SOUND)
        bindToggle(autoRest, BluefinStore.KEY_AUTO_REST)
        touchThrough.setOnCheckedChangeListener { _, checked ->
            if (!syncing) BluefinStore.writeClickThrough(prefs, checked)
        }
        bindSlider(size, 80) { BluefinStore.writeScale(prefs, BluefinStore.progressToScale(it)) }
        bindSlider(opacity, 100) { BluefinStore.writeOpacity(prefs, BluefinStore.progressToOpacity(it)) }
        bindSlider(orbSize, 28) { BluefinStore.writeOrbSizeDp(prefs, BluefinStore.progressToOrbSize(it)) }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
    }

    fun dispose() = prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)

    fun refresh() {
        syncing = true
        val scale = BluefinStore.readScale(prefs)
        val petOpacity = BluefinStore.readOpacity(prefs)
        val orbSizeDp = BluefinStore.readOrbSizeDp(prefs)
        roam.isChecked = prefs.getBoolean(BluefinStore.KEY_ROAM, true) && canWalk()
        hide.isChecked = prefs.getBoolean(BluefinStore.KEY_HIDDEN, false)
        sound.isChecked = prefs.getBoolean(BluefinStore.KEY_SOUND, true)
        autoRest.isChecked = prefs.getBoolean(BluefinStore.KEY_AUTO_REST, true)
        touchThrough.isChecked = BluefinStore.readClickThrough(prefs)
        size.progress = BluefinStore.scaleToProgress(scale)
        sizeValue.text = String.format(Locale.US, "%.2f", scale)
        opacity.progress = BluefinStore.opacityToProgress(petOpacity)
        opacityValue.text = root.context.getString(R.string.orb_opacity_percent, (petOpacity * 100).roundToInt())
        orbSize.progress = BluefinStore.orbSizeToProgress(orbSizeDp)
        orbSizeValue.text = root.context.getString(R.string.settings_size_dp, orbSizeDp.roundToInt())
        orbTouch.sync()
        syncing = false
    }

    private fun bindToggle(view: CompoundButton, key: String) {
        view.setOnCheckedChangeListener { _, checked ->
            if (!syncing) prefs.edit().putBoolean(key, checked).apply()
        }
    }

    private fun bindSlider(slider: SeekBar, max: Int, onProgress: (Int) -> Unit) {
        slider.max = max
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(slider: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onProgress(progress)
            }

            override fun onStartTrackingTouch(slider: SeekBar) = Unit
            override fun onStopTrackingTouch(slider: SeekBar) = Unit
        })
    }

    private companion object {
        val CONTROL_KEYS = setOf(
            BluefinStore.KEY_ROAM, BluefinStore.KEY_HIDDEN, BluefinStore.KEY_SOUND,
            BluefinStore.KEY_AUTO_REST, BluefinStore.KEY_CLICK_THROUGH, BluefinStore.KEY_SCALE,
            BluefinStore.KEY_SIZE, BluefinStore.KEY_OPACITY, BluefinStore.KEY_ORB_SIZE_DP,
            BluefinStore.KEY_ORB_OPACITY, BluefinStore.KEY_ORB_CLICK_THROUGH,
        )
    }
}
