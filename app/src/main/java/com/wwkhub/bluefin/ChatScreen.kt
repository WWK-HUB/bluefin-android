package com.wwkhub.bluefin

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/** Full in-app conversation screen. Shares [ChatSession] with the overlay. */
class ChatScreen : AppCompatActivity() {
    private lateinit var session: ChatSession
    private lateinit var transcript: ChatTranscriptView
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.screen_chat)
        val root = findViewById<View>(R.id.chat_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        session = ChatSession.shared(this)
        transcript = ChatTranscriptView(
            findViewById(R.id.chat_scroll),
            findViewById(R.id.chat_messages),
        )
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
        OverlayAppVisibility.setChatVisible(true)
        bindLatestDraft()
        session.addListener(listener)
    }

    override fun onStop() {
        session.removeListener(listener)
        OverlayAppVisibility.setChatVisible(false)
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

        transcript.render(snapshot.lines, generating)
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
}
