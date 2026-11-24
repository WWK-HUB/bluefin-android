package com.wwkhub.bluefin

import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

internal class WelcomeGuide(
    private val activity: MainActivity,
    start: Int,
    private val onProgress: (Int) -> Unit,
    private val open: (SettingsPage) -> Unit,
    private val finish: () -> Unit,
) {
    private enum class Step(
        @StringRes val title: Int,
        @StringRes val detail: Int,
        val target: SettingsPage? = null,
        @StringRes val action: Int = 0,
    ) {
        PET(R.string.guide_start, R.string.guide_start_body),
        OVERLAY(R.string.guide_overlay_title, R.string.guide_overlay_body, SettingsPage.OVERLAY, R.string.guide_overlay_action),
        CHAT(R.string.guide_chat_title, R.string.guide_chat_body, SettingsPage.MODEL, R.string.guide_chat_action),
        VOICE(R.string.guide_voice_title, R.string.guide_voice_body, SettingsPage.SPEECH, R.string.guide_voice_action),
        SCREEN(R.string.guide_screen_title, R.string.guide_screen_body, SettingsPage.SCREEN, R.string.guide_screen_action),
    }

    val view = AppPanel(activity, ::back)
    var index = start.coerceIn(Step.entries.indices)
        private set
    private val thumbnail by lazy {
        activity.assets.open("bluefin/assets/thumb.png").use(BitmapFactory::decodeStream)
    }

    init {
        view.title.setText(R.string.guide_title)
        view.action.id = R.id.guide_skip
        view.action.setText(R.string.tip_skip)
        view.action.visibility = View.VISIBLE
        view.action.setOnClickListener { finish() }
        render()
    }

    fun back() {
        if (index == 0) finish() else move(index - 1)
    }

    private fun move(to: Int) {
        index = to
        onProgress(index)
        render()
    }

    private fun render() = with(view) {
        val step = Step.entries[index]
        val last = index == Step.entries.lastIndex
        ViewCompat.setAccessibilityPaneTitle(this, activity.getString(step.title))
        val body = column()
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(24))
            background = card()
        }
        val progress = activity.getString(R.string.guide_progress, index + 1, Step.entries.size)
        card.addView(label(progress, 14, R.color.blue_deep, bold = true))
        card.addView(LinearProgressIndicator(activity).apply {
            max = Step.entries.size
            this.progress = index + 1
            trackCornerRadius = dp(3)
            setIndicatorColor(activity.getColor(R.color.accent))
            trackColor = activity.getColor(R.color.blue_mist)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(9); bottomMargin = dp(20) })
        if (step == Step.PET) {
            card.addView(ImageView(activity).apply {
                setImageBitmap(thumbnail)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = card(R.color.ice)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(104), dp(104)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18) })
        }
        card.addView(label(activity.getString(step.title), 26, R.color.ink, bold = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        })
        card.addView(label(activity.getString(step.detail), 17, R.color.muted).apply {
            setLineSpacing(dp(5).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (step.target != null) {
            card.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = R.id.guide_open_feature
                setText(step.action)
                minHeight = dp(48)
                setOnClickListener { open(step.target) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2))
        val next = MaterialButton(activity).apply {
            id = R.id.guide_next
            setText(if (last) R.string.guide_finish else R.string.tip_next)
            minHeight = dp(54)
            setOnClickListener { if (last) finish() else move(index + 1) }
        }
        val stepLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
            addView(FrameLayout(activity).apply {
                setPadding(dp(20), dp(6), dp(20), dp(12))
                addView(next, FrameLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        content.removeAllViews()
        content.addView(stepLayout, FrameLayout.LayoutParams(-1, -1))
        body.requestFocus()
    }
}
