package com.wwkhub.bluefin

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/** Full in-app conversation screen. Shares [ChatSession] with the overlay. */
class ChatScreen : AppCompatActivity() {
    private lateinit var session: ChatSession
    private lateinit var scroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var input: TextInputEditText
    private lateinit var send: MaterialButton
    private lateinit var stop: MaterialButton
    private lateinit var status: TextView
    private lateinit var banner: TextView

    private var pendingDraftToken: Long? = null
    private var applyingDraft = false
    private var busy = false
    private var configured = false

    private val listener: (ChatSnapshot) -> Unit = { snapshot -> render(snapshot) }

    private val draftWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (applyingDraft) return
            val text = s?.toString().orEmpty()
            if (text != ChatDraft.state.text) ChatDraft.state.edit(text)
            updateSendEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_chat)
        session = ChatSession.shared(this)
        scroll = findViewById(R.id.chat_scroll)
        messages = findViewById(R.id.chat_messages)
        input = findViewById(R.id.chat_input)
        send = findViewById(R.id.chat_send)
        stop = findViewById(R.id.chat_stop)
        status = findViewById(R.id.chat_status)
        banner = findViewById(R.id.chat_banner)

        findViewById<MaterialButton>(R.id.chat_back).apply {
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }
        findViewById<MaterialButton>(R.id.chat_settings).setOnClickListener { openSettings() }
        findViewById<MaterialButton>(R.id.chat_clear).setOnClickListener { confirmClear() }
        send.setOnClickListener { sendMessage() }
        stop.setOnClickListener { session.stop() }
        banner.setOnClickListener { openSettings() }

        pendingDraftToken = intent.getLongExtra(ChatEntry.EXTRA_DRAFT_TOKEN, 0L)
            .takeIf { it > 0L }

        // The process draft is authoritative. Disable Android's saved view state
        // so a rotation/recreate replay cannot restore stale text over it.
        input.isSaveEnabled = false
        input.addTextChangedListener(draftWatcher)
        send.isEnabled = false
    }

    override fun onStart() {
        super.onStart()
        // An explicit chat surface is foreground: an existing bubble is
        // suppressed by its own listener. This never constructs a bubble.
        OverlayAppVisibility.setForeground(true)
        bindLatestDraft()
        session.addListener(listener)
    }

    override fun onStop() {
        session.removeListener(listener)
        OverlayAppVisibility.setForeground(false)
        super.onStop()
    }

    private fun bindLatestDraft() {
        val token = pendingDraftToken
        pendingDraftToken = null
        applyDraft(ChatDraft.state.rebind(token).text)
    }

    private fun applyDraft(text: String) {
        if (input.text?.toString() == text) return
        applyingDraft = true
        try {
            input.setText(text)
            input.setSelection(input.text?.length ?: 0)
        } finally {
            applyingDraft = false
        }
        updateSendEnabled()
    }

    private fun updateSendEnabled() {
        send.isEnabled = !busy && configured && input.text?.toString()?.isNotBlank() == true
    }

    private fun sendMessage() {
        val capture = ChatDraft.state.captureSend()
        if (capture.text.trim().isEmpty()) return
        val result = session.send(capture.text)
        val draftOutcome = when (result) {
            ChatSendResult.STARTED -> ChatDraftSendOutcome.STARTED
            ChatSendResult.EMPTY -> ChatDraftSendOutcome.EMPTY
            ChatSendResult.BUSY -> ChatDraftSendOutcome.BUSY
            ChatSendResult.NOT_CONFIGURED -> ChatDraftSendOutcome.NOT_CONFIGURED
        }
        val latest = ChatDraft.state.onSendOutcome(capture, draftOutcome)
        applyDraft(latest.text)
        if (result == ChatSendResult.NOT_CONFIGURED) ChatEntry.showUnconfigured(this)
        updateSendEnabled()
    }

    private fun render(snapshot: ChatSnapshot) {
        val generating = snapshot.phase == ChatPhase.THINKING || snapshot.phase == ChatPhase.STREAMING
        busy = snapshot.workerBusy
        configured = snapshot.configured
        send.visibility = View.VISIBLE
        stop.visibility = if (generating || busy) View.VISIBLE else View.GONE
        input.isEnabled = true
        updateSendEnabled()

        status.visibility = if (snapshot.phase == ChatPhase.IDLE) View.GONE else View.VISIBLE
        status.text = when (snapshot.phase) {
            ChatPhase.THINKING -> getString(R.string.chat_thinking)
            ChatPhase.STREAMING -> getString(R.string.chat_streaming)
            ChatPhase.STOPPED -> getString(R.string.chat_stopped)
            ChatPhase.FAILED -> snapshot.lastError.orEmpty()
            ChatPhase.IDLE -> ""
        }

        val bannerText = when {
            !snapshot.configured -> getString(R.string.chat_unconfigured)
            snapshot.consecutiveFailures >= 5 && !snapshot.lastError.isNullOrBlank() ->
                "连续 ${snapshot.consecutiveFailures} 次失败：${snapshot.lastError}  ${getString(R.string.chat_open_settings)}"
            else -> ""
        }
        banner.visibility = if (bannerText.isEmpty()) View.GONE else View.VISIBLE
        banner.text = bannerText

        messages.removeAllViews()
        if (snapshot.lines.isEmpty() && !generating) {
            messages.addView(
                TextView(this).apply {
                    text = getString(R.string.chat_empty)
                    textSize = 15f
                    setTextColor(getColor(R.color.muted))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(48), 0, dp(24))
                },
            )
        }
        snapshot.lines.forEach { line -> messages.addView(bubble(line)) }
        if (snapshot.phase == ChatPhase.THINKING) {
            messages.addView(
                TextView(this).apply {
                    text = getString(R.string.chat_thinking)
                    textSize = 13f
                    setTextColor(getColor(R.color.muted))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                },
            )
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(line: ChatLine): TextView {
        val assistant = line.role == ChatRole.ASSISTANT
        val label = when {
            line.stopped -> if (line.text.isBlank()) {
                getString(R.string.chat_stopped)
            } else {
                line.text + "\n（${getString(R.string.chat_stopped)}）"
            }
            line.failed -> line.text + "\n（${getString(R.string.chat_failed)}）"
            else -> line.text
        }
        return TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(getColor(R.color.ink))
            setBackgroundResource(
                if (assistant) R.drawable.bg_chat_assistant else R.drawable.bg_chat_user,
            )
            setPadding(dp(12), dp(9), dp(12), dp(9))
            typeface = if (assistant) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                width = (resources.displayMetrics.widthPixels * 0.82f).toInt()
                gravity = if (assistant) Gravity.START else Gravity.END
                topMargin = dp(6)
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_clear_title)
            .setMessage(R.string.chat_clear_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chat_clear_confirm) { _, _ -> session.clearHistory() }
            .show()
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = ChatEntry.EXTRA_OPEN_MODEL_SETTINGS
                putExtra(ChatEntry.EXTRA_OPEN_MODEL_SETTINGS, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
