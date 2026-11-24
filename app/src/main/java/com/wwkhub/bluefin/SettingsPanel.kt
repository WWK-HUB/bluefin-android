package com.wwkhub.bluefin

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal enum class SettingsPage(@StringRes val title: Int, @StringRes val help: Int = 0) {
    HOME(R.string.settings_title),
    OVERLAY(R.string.settings_overlay, R.string.settings_overlay_help),
    PET(R.string.settings_pet, R.string.settings_pet_help),
    CHARACTER(R.string.settings_character, R.string.character_import_help),
    MODEL(R.string.settings_model, R.string.model_format_help),
    PERSONA(R.string.persona_title, R.string.persona_help),
    VOICE(R.string.voice_settings_title, R.string.voice_settings_summary),
    SPEECH(R.string.speech_title, R.string.speech_help),
    SCREEN(R.string.screen_awareness),
    GUIDE(R.string.guide_title),
    ABOUT(R.string.settings_about),
}

internal class SettingsPanel(
    private val activity: MainActivity,
    private val onClose: () -> Unit,
    private val buildPage: (SettingsPage, LinearLayout) -> Unit,
    private val onExternalPage: (SettingsPage) -> Unit,
) {
    val view = AppPanel(activity, ::back)
    var page = SettingsPage.HOME
        private set
    private val pages = mutableMapOf<SettingsPage, ScrollView>()
    private var homeScroll = 0
    var fromGuide = false

    fun show(next: SettingsPage) {
        if (page == SettingsPage.HOME) homeScroll = (view.content.getChildAt(0) as? ScrollView)?.scrollY ?: homeScroll
        hideKeyboard()
        page = next
        view.title.setText(next.title)
        ViewCompat.setAccessibilityPaneTitle(view, activity.getString(next.title))
        view.action.visibility = if (next.help == 0) View.GONE else View.VISIBLE
        view.action.setText(R.string.settings_help)
        view.action.setOnClickListener {
            val message = if (page == SettingsPage.OVERLAY) {
                activity.getString(page.help, activity.getString(R.string.overlay_switch_help),
                    activity.getString(R.string.bubble_preview_help))
            } else activity.getString(page.help)
            MaterialAlertDialogBuilder(activity)
                .setTitle(page.title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        view.content.removeAllViews()
        val scroll = if (next == SettingsPage.HOME) {
            view.scroll(view.column().apply { buildIndex(this) })
        } else {
            pages.getOrPut(next) { view.scroll(view.column().apply { buildPage(next, this) }) }
        }
        view.content.addView(scroll)
        scroll.getChildAt(0).requestFocus()
        if (next == SettingsPage.HOME) scroll.post { scroll.scrollTo(0, homeScroll) }
    }

    fun back() {
        if (page == SettingsPage.HOME || fromGuide) onClose() else show(SettingsPage.HOME)
    }

    fun hideKeyboard() {
        val token = view.windowToken ?: return
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(token, 0)
    }

    private fun buildIndex(body: LinearLayout) {
        val enabled = OverlayLauncher.isShowing()
        val endpoint = ModelEndpointStore.load(activity)
        val model = if (ModelEndpointStore.isReady(activity)) endpoint.model else activity.getString(R.string.settings_not_configured)
        val overlayStatus = activity.getString(if (enabled) R.string.settings_enabled else R.string.settings_disabled)
        group(body, R.string.settings_group_pet, listOf(
            SettingsPage.OVERLAY to overlayStatus, SettingsPage.CHARACTER to null, SettingsPage.PET to null,
        ))
        group(body, R.string.settings_group_chat, listOf(
            SettingsPage.MODEL to model, SettingsPage.PERSONA to null, SettingsPage.VOICE to null,
            SettingsPage.SPEECH to null, SettingsPage.SCREEN to null,
        ))
        group(body, R.string.settings_group_support, listOf(SettingsPage.GUIDE to null, SettingsPage.ABOUT to null))
    }

    private fun group(body: LinearLayout, labelRes: Int, entries: List<Pair<SettingsPage, String?>>) = with(view) {
        val heading = label(activity.getString(labelRes), 14, R.color.muted, bold = true)
        ViewCompat.setAccessibilityHeading(heading, true)
        body.addView(heading, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(5)
            bottomMargin = dp(8)
        })
        val group = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            clipToOutline = true
        }
        entries.forEachIndexed { index, (destination, status) ->
            if (index > 0) group.addView(View(activity).apply {
                setBackgroundColor(activity.getColor(R.color.blue_line))
            }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16); marginEnd = dp(16) })
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(55)
                setPadding(dp(16), dp(8), dp(12), dp(8))
                selectable()
                isFocusable = true
                contentDescription = listOfNotNull(activity.getString(destination.title), status).joinToString("，")
                setOnClickListener {
                    when (destination) {
                        SettingsPage.GUIDE, SettingsPage.SCREEN -> onExternalPage(destination)
                        else -> show(destination)
                    }
                }
            }
            row.addView(label(activity.getString(destination.title), 17, R.color.ink).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (status != null) row.addView(label(status, 13, R.color.muted).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                maxLines = 1
                maxWidth = dp(108)
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(ImageView(activity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(R.drawable.ic_settings_chevron)
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
            group.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })
    }
}
