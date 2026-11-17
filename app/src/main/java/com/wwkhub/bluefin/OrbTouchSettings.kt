package com.wwkhub.bluefin

import android.content.SharedPreferences
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial

/** The same size-independent orb controls are available in both settings surfaces. */
internal class OrbTouchSettings(
    root: View,
    private val prefs: SharedPreferences,
    onClickThroughChanged: (Boolean) -> Unit = {},
) {
    private val opacity = root.findViewById<SeekBar>(R.id.orb_opacity_slider)
    private val value = root.findViewById<TextView>(R.id.orb_opacity_value)
    private val clickThrough = root.findViewById<SwitchMaterial>(R.id.orb_touch_through_switch)
    private var syncing = false

    init {
        opacity.max = 100 - BluefinStore.ORB_OPACITY_MIN_PERCENT
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val percent = 100 - progress
                showOpacity(percent)
                BluefinStore.writeOrbOpacityPercent(prefs, percent)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        clickThrough.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            BluefinStore.writeOrbClickThrough(prefs, checked)
            onClickThroughChanged(checked)
        }
        sync()
    }

    fun sync() {
        syncing = true
        val percent = BluefinStore.readOrbOpacityPercent(prefs)
        opacity.progress = 100 - percent
        showOpacity(percent)
        clickThrough.isChecked = BluefinStore.readOrbClickThrough(prefs)
        syncing = false
    }

    private fun showOpacity(percent: Int) {
        value.text = value.context.getString(R.string.orb_opacity_percent, percent)
    }
}
