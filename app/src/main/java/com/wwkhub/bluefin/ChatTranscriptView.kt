package com.wwkhub.bluefin

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnNextLayout

/** Updates message rows in place so streaming preserves scroll and text selection. */
internal class ChatTranscriptView(
    private val scroll: ScrollView,
    private val messages: LinearLayout,
    private val inOverlay: Boolean = false,
) {
    private val context = messages.context
    private var shown: List<ChatLine> = emptyList()
    private var emptyView: TextView? = null
    private var followAfterLayout = false

    fun showLatest() {
        if (followAfterLayout) return
        followAfterLayout = true
        messages.doOnNextLayout {
            followAfterLayout = false
            scroll.scrollTo(0, messages.bottom)
        }
        messages.requestLayout()
    }

    fun render(lines: List<ChatLine>, generating: Boolean) {
        val showEmpty = lines.isEmpty() && !generating
        if (lines == shown && showEmpty == (emptyView != null)) return
        val followLatest = !scroll.canScrollVertically(1)
        emptyView?.let { messages.removeView(it) }
        emptyView = null
        lines.forEachIndexed { index, line ->
            val previous = shown.getOrNull(index)
            if (previous == line) return@forEachIndexed
            val view = if (previous?.role == line.role) {
                messages.getChildAt(index) as TextView
            } else {
                if (previous != null) messages.removeViewAt(index)
                createBubble(line.role).also { messages.addView(it, index) }
            }
            view.text = label(line)
        }
        if (messages.childCount > lines.size) {
            messages.removeViews(lines.size, messages.childCount - lines.size)
        }
        shown = lines
        if (showEmpty) {
            emptyView = TextView(context).apply {
                text = context.getString(R.string.chat_empty)
                textSize = if (inOverlay) 13f else 15f
                setTextColor(if (inOverlay) 0xFFB8CCE0.toInt() else context.getColor(R.color.muted))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(if (inOverlay) 16 else 48), dp(8), dp(16))
            }.also { messages.addView(it) }
        }
        if (followLatest) showLatest()
    }

    private fun createBubble(role: ChatRole): TextView = TextView(context).apply {
        val assistant = role == ChatRole.ASSISTANT
        textSize = if (inOverlay) 14f else 15f
        setTextColor(if (inOverlay && assistant) Color.WHITE else context.getColor(R.color.ink))
        setBackgroundResource(when {
            inOverlay && assistant -> R.drawable.bg_chat_overlay_bubble
            assistant -> R.drawable.bg_chat_assistant
            else -> R.drawable.bg_chat_user
        })
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextIsSelectable(!inOverlay)
        val viewport = scroll.width - scroll.paddingLeft - scroll.paddingRight
        maxWidth = ((viewport.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels) *
            0.82f).toInt()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = if (assistant) Gravity.START else Gravity.END
            topMargin = dp(6)
        }
    }

    private fun label(line: ChatLine): String {
        val status = when {
            line.stopped -> R.string.chat_stopped
            line.failed -> R.string.chat_failed
            else -> return line.text
        }
        val description = context.getString(status)
        return if (line.text.isBlank()) description else "${line.text}\n（$description）"
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
