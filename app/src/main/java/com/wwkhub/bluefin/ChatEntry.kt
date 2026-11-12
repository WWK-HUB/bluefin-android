package com.wwkhub.bluefin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Entry point for both the top-bar chat button and the overlay panel. The
 * endpoint check is a cheap prefs read; no key, Keystore, or persona access
 * happens until the user actually sends a message.
 */
internal object ChatEntry {
    const val EXTRA_DRAFT_TOKEN = "com.wwkhub.bluefin.extra.CHAT_DRAFT_TOKEN"
    const val EXTRA_OPEN_MODEL_SETTINGS = "com.wwkhub.bluefin.extra.OPEN_MODEL_SETTINGS"
    const val EXTRA_OPEN_CHARACTER_SETTINGS = "com.wwkhub.bluefin.extra.OPEN_CHARACTER_SETTINGS"

    fun request(context: Context, draft: String = "") {
        // The existing overlay full-chat button can still supply a one-shot
        // draft. C3 replaces this with the guarded overlay draft watcher; the
        // screen itself always reads the process owner, never Intent text.
        if (draft.isNotEmpty() && draft != ChatDraft.state.text) {
            ChatDraft.state.edit(draft)
        }
        if (ModelEndpointStore.isReady(context)) {
            openScreen(context, ChatDraft.state.issueHandoff().token)
            return
        }
        val activity = context as? Activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            openModelSettings(context)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setMessage("还没接通模型，要去设置里填写吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("去设置") { _, _ ->
                (activity as? MainActivity)?.openModelSettings()
                    ?: openModelSettings(activity)
            }
            .show()
    }

    /**
     * Opens the full conversation even when no endpoint is configured, so the
     * existing history remains readable. It deliberately has no readiness
     * redirect or settings dialog; C3 uses it for the overlay record action.
     */
    fun openRecords(context: Context) {
        openScreen(context, ChatDraft.state.issueHandoff().token)
    }

    fun showUnconfigured(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setMessage("还没接通模型，要去设置里填写吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("去设置") { _, _ ->
                (activity as? MainActivity)?.openModelSettings()
                    ?: openModelSettings(activity)
            }
            .show()
    }

    fun toastSendResult(context: Context, result: ChatSendResult) {
        when (result) {
            ChatSendResult.NOT_CONFIGURED ->
                Toast.makeText(context, "还没接通模型，请先在设置里填写。", Toast.LENGTH_SHORT).show()
            ChatSendResult.BUSY -> Unit
            ChatSendResult.EMPTY -> Unit
            ChatSendResult.STARTED -> Unit
        }
    }

    private fun openScreen(context: Context, token: Long) {
        val intent = Intent(context, ChatScreen::class.java)
            .putExtra(EXTRA_DRAFT_TOKEN, token)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openModelSettings(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = EXTRA_OPEN_MODEL_SETTINGS
            putExtra(EXTRA_OPEN_MODEL_SETTINGS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }

    fun openCharacterSettings(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = EXTRA_OPEN_CHARACTER_SETTINGS
            putExtra(EXTRA_OPEN_CHARACTER_SETTINGS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
}
