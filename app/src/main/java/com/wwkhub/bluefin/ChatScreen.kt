package com.wwkhub.bluefin

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.wwkhub.bluefin.voice.VoiceMicButton
import com.wwkhub.bluefin.voice.tts.TtsReader

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
    private lateinit var voiceMic: VoiceMicButton
    private var voiceStatusOverride: String? = null
    private var lastSnapshot: ChatSnapshot? = null

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
        if (savedInstanceState == null) {
            BluefinMotion.enter(findViewById(R.id.chat_top_bar), y = -8f * resources.displayMetrics.density)
        }
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
        val scroll = findViewById<ScrollView>(R.id.chat_scroll)
        transcript = ChatTranscriptView(
            scroll,
            findViewById(R.id.chat_messages),
        )
        val latest = findViewById<MaterialButton>(R.id.chat_jump_latest)
        latest.setOnClickListener { transcript.showLatest() }
        fun updateLatestVisibility() {
            latest.visibility = if (scroll.canScrollVertically(1)) View.VISIBLE else View.GONE
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateLatestVisibility() }
        scroll.viewTreeObserver.addOnGlobalLayoutListener { updateLatestVisibility() }
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
        findViewById<MaterialButton>(R.id.chat_look_screen).setOnClickListener {
            ScreenAwarenessActivity.look(this, fromChat = true)
        }
        findViewById<MaterialButton>(R.id.chat_screen_settings).setOnClickListener {
            startActivity(ScreenAwarenessActivity.settingsIntent(this))
        }
        send.setOnClickListener { sendMessage() }
        stop.setOnClickListener { session.stop() }
        banner.setOnClickListener { openSettings() }
        voiceMic = VoiceMicButton(this, findViewById(R.id.chat_mic), voiceHost)

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
        voiceMic.attach()
        TtsReader.shared(this).attach(this)
    }

    override fun onStop() {
        voiceMic.detach()
        TtsReader.shared(this).detach(this)
        session.removeListener(listener)
        OverlayAppVisibility.setChatVisible(false)
        OverlayAppVisibility.setForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applyDraft(ChatDraft.state.text)
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
        if (ScreenAwareness.isLookCommand(capture.text)) {
            ScreenAwarenessActivity.look(this, capture, fromChat = true)
            return
        }
        val result = session.send(capture.text)
        val draftOutcome = when (result) {
            ChatSendResult.STARTED -> ChatDraftSendOutcome.STARTED
            ChatSendResult.EMPTY -> ChatDraftSendOutcome.EMPTY
            ChatSendResult.BUSY -> ChatDraftSendOutcome.BUSY
            ChatSendResult.NOT_CONFIGURED -> ChatDraftSendOutcome.NOT_CONFIGURED
        }
        val latest = ChatDraft.state.onSendOutcome(capture, draftOutcome)
        applyDraft(latest.text)
        if (result == ChatSendResult.STARTED) transcript.showLatest()
        if (result == ChatSendResult.NOT_CONFIGURED) ChatEntry.showUnconfigured(this)
        updateSendEnabled()
    }

    private fun render(snapshot: ChatSnapshot) {
        lastSnapshot = snapshot
        val generating = snapshot.phase == ChatPhase.THINKING || snapshot.phase == ChatPhase.STREAMING
        busy = snapshot.workerBusy
        configured = snapshot.configured
        send.visibility = if (generating || busy) View.GONE else View.VISIBLE
        stop.visibility = if (generating || busy) View.VISIBLE else View.GONE
        input.isEnabled = true
        updateSendEnabled()

        renderStatus(snapshot)

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

    private fun renderStatus(snapshot: ChatSnapshot) {
        val override = voiceStatusOverride
        if (override != null) {
            status.visibility = View.VISIBLE
            status.text = override
            return
        }
        status.visibility = if (snapshot.phase == ChatPhase.IDLE) View.GONE else View.VISIBLE
        status.text = when (snapshot.phase) {
            ChatPhase.THINKING -> getString(R.string.chat_thinking)
            ChatPhase.STREAMING -> getString(R.string.chat_streaming)
            ChatPhase.STOPPED -> getString(R.string.chat_stopped)
            ChatPhase.FAILED -> snapshot.lastError.orEmpty()
            ChatPhase.IDLE -> ""
        }
    }

    /** Voice routing reuses the typed send gates; see [sendMessage]. */
    private val voiceHost = object : VoiceMicButton.Host {
        override fun isChatConfigured(): Boolean = configured

        override fun isChatBusy(): Boolean = busy

        override fun voiceStatus(text: String) {
            voiceStatusOverride = text
            status.visibility = View.VISIBLE
            status.text = text
        }

        override fun voiceStatusIdle() {
            voiceStatusOverride = null
            lastSnapshot?.let { renderStatus(it) }
        }

        override fun sendVoiceText(text: String) {
            ChatDraft.state.edit(text)
            val capture = ChatDraft.state.captureSend()
            val result = session.send(capture.text)
            val outcome = when (result) {
                ChatSendResult.STARTED -> ChatDraftSendOutcome.STARTED
                ChatSendResult.EMPTY -> ChatDraftSendOutcome.EMPTY
                ChatSendResult.BUSY -> ChatDraftSendOutcome.BUSY
                ChatSendResult.NOT_CONFIGURED -> ChatDraftSendOutcome.NOT_CONFIGURED
            }
            val latest = ChatDraft.state.onSendOutcome(capture, outcome)
            applyDraft(latest.text)
            if (result == ChatSendResult.STARTED) transcript.showLatest()
            updateSendEnabled()
        }

        override fun voiceLookCommand(text: String) {
            ChatDraft.state.edit(text)
            ScreenAwarenessActivity.look(
                this@ChatScreen,
                ChatDraft.state.captureSend(),
                fromChat = true,
            )
        }

        override fun voiceNotConfigured() {
            ChatEntry.showUnconfigured(this@ChatScreen)
        }

        override fun stashVoiceDraft(text: String) {
            ChatDraft.state.edit(text)
            applyDraft(text)
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
}
