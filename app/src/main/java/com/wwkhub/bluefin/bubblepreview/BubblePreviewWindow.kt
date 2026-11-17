package com.wwkhub.bluefin.bubblepreview

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import androidx.appcompat.widget.AppCompatEditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wwkhub.bluefin.ChatDraftState
import com.wwkhub.bluefin.PackBubbleColors
import com.wwkhub.bluefin.R
import com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewPackPaletteInput
import com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewPalette
import com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewTailDirection
import com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewPaletteSource
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val EXIT_ANIMATION_MILLIS = 80L

/**
 * One bounded removal attempt sequence shared by the live window and the JVM
 * cleanup tests. `isManaged` is the platform's real WindowManager ownership
 * evidence, not merely `View.isAttachedToWindow`: immediately after a
 * successful or partial `addView` the root has a parent before its first
 * traversal, so `isAttachedToWindow` can still be false while the window is
 * fully managed. An already unmanaged root is idempotent success, not an
 * orphan. At most two `removeOnce` calls are made, and an `Error` is never
 * caught.
 */
internal interface BubblePreviewRemovalPort {
    fun isManaged(): Boolean
    fun removeOnce()
}

internal data class BubblePreviewRemovalOutcome(
    val removed: Boolean,
    val attempts: Int,
    val lastError: Exception?,
)

internal object BubblePreviewRemovalPolicy {
    const val MAX_ATTEMPTS: Int = 2

    fun remove(port: BubblePreviewRemovalPort): BubblePreviewRemovalOutcome {
        var attempts = 0
        var lastError: Exception? = null
        while (attempts < MAX_ATTEMPTS) {
            if (!port.isManaged()) {
                return BubblePreviewRemovalOutcome(true, attempts, lastError)
            }
            attempts += 1
            try {
                port.removeOnce()
            } catch (error: Exception) {
                lastError = error
            }
            if (!port.isManaged()) {
                return BubblePreviewRemovalOutcome(true, attempts, lastError)
            }
        }
        return BubblePreviewRemovalOutcome(false, attempts, lastError)
    }
}

/**
 * The one compact, content-sized 0.8 speech window.
 *
 * It renders two faces internally and never creates a second system window:
 * a tailed input balloon with EditText + expand + send, or a tailed character
 * reply with an untailed user chip above it. The window starts
 * NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN; only while the input face
 * is attached and the user is typing does it temporarily become focusable for
 * the IME. It is never full screen, never dims or watches outside touches, and
 * it removes immediately for click-through/invalidation.
 */
internal class BubblePreviewWindow(
    context: Context,
    private val windowManager: WindowManager,
    initialContent: BubbleTurnSnapshot?,
    initialInput: SpeechInputSnapshot?,
    initialFailureText: String,
    initialPlan: BubblePreviewLayoutPlan,
    placement: BubblePreviewPlacement,
    geometry: BubblePreviewGeometrySnapshot,
    private val packBubbleColors: PackBubbleColors?,
    initialAnchor: BubblePreviewPoint?,
    private val onRecord: () -> Unit,
    private val onClose: () -> Unit,
    private val onStop: () -> Unit,
    private val onExpand: () -> Unit,
    private val onInputSend: (String) -> Unit = {},
    private val onInputExpand: (String) -> Unit = {},
    private val onInputDraftChanged: (String) -> Unit = {},
    private val onInputNoSpace: () -> Unit = {},
) {
    private val night = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val packInput = packBubbleColors?.let { colors ->
        if (night) {
            BubblePreviewPackPaletteInput(colors.darkFill, colors.darkText, colors.darkLine)
        } else {
            BubblePreviewPackPaletteInput(colors.lightFill, colors.lightText, colors.lightLine)
        }
    }
    private val packPalette = BubblePreviewPalette.resolve(night, packInput)
    private val density = context.resources.displayMetrics.density.coerceAtLeast(0.5f)
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
        .takeIf { it.isFinite() && it > 0f } ?: density
    private val root = FrameLayout(context)
    private var content: BubbleTurnSnapshot? = initialContent
    private var input: SpeechInputSnapshot? = initialInput
    private var failureText: String = initialFailureText
    private var plan: BubblePreviewLayoutPlan = initialPlan
    private var mode: SpeechBubbleMode =
        if (initialInput != null) SpeechBubbleMode.INPUT else SpeechBubbleMode.REPLY
    private var side: BubblePreviewSide = placement.side
    private var anchor: BubblePreviewPoint? = initialAnchor

    private var bodyView: TextView? = null
    private var chipView: TextView? = null
    private var inputView: EditText? = null
    private var sendView: TextView? = null
    private var primaryReplyAction: ImageView? = null
    private var fullReplyAction: TextView? = null
    private var thinkingDots: ImageView? = null
    private var contentView: View? = null
    private var bodyContainer: View? = null
    private var tailView: View? = null
    private var closingForUser = false
    private var exitRunnable: Runnable? = null
    private var added = false
    private var applyingInputText = false
    private var imeFailureNotified = false
    private var imeBottomPx = 0
    private var pendingKeyboardRequest = false
    private var keyboardAttemptsLeft = 0
    private val requestKeyboard = Runnable { requestKeyboardIfReady() }
    private var unregisterInputBack: (() -> Unit)? = null
    private val visibleDisplayFrame = Rect()

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        // Placement rects are screen pixels; layout against the full display
        // origin rather than a content area shifted by system bars.
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    private val params = WindowManager.LayoutParams(
        plan.windowWidthPx,
        plan.windowHeightPx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        baseFlags,
        PixelFormat.TRANSLUCENT,
    )
    private val displayLeft = geometry.displayBounds.left
    private val displayTop = geometry.displayBounds.top
    private val displayHeight = geometry.displayBounds.height
    private var appliedX = placement.rect.left - displayLeft
    private var appliedY = placement.rect.top - displayTop

    // Default speech tokens. A pack may override only the character reply and
    // its tail; the input balloon, chip, send/expand/stop/close stay default.
    private val inputFill = color(R.color.bf_speech_input_fill)
    private val inputText = color(R.color.bf_speech_input_text)
    private val inputHint = color(R.color.bf_speech_input_hint)
    private val inputStroke = color(R.color.bf_speech_input_stroke)
    private val userFill = color(R.color.bf_speech_user_fill)
    private val userText = color(R.color.bf_speech_user_text)
    private val userStroke = color(R.color.bf_speech_user_stroke)
    private val iconColor = color(R.color.bf_speech_icon)
    private val replyDefaultFill = color(R.color.bf_speech_reply_fill)
    private val replyDefaultText = color(R.color.bf_speech_reply_text)
    private val replyDefaultStroke = color(R.color.bf_speech_reply_stroke)
    private val usePackReply = packPalette.characterSource == BubblePreviewPaletteSource.PACK
    private val replyFill = if (usePackReply) packPalette.character.fill.argb else replyDefaultFill
    private val replyText = if (usePackReply) packPalette.character.text.argb else replyDefaultText
    private val replyStroke = if (usePackReply) packPalette.character.line.argb else replyDefaultStroke

    init {
        root.setBackgroundColor(Color.TRANSPARENT)
        root.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
            if (focused) showPendingKeyboard()
        }
        root.isClickable = true
        root.contentDescription = null
        params.apply {
            x = appliedX
            y = appliedY
            gravity = Gravity.TOP or Gravity.LEFT
            title = BubblePreviewModule.AUDIT_WINDOW_TITLE
            alpha = 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        installImeListener()
        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (mode == SpeechBubbleMode.INPUT && added && !destroyedForCleanup) {
                refreshInputImeInset()
            }
        }
        rebuild(animate = false)
    }

    fun add() {
        // WindowManager must see an editor window on the initial focus transfer.
        if (mode == SpeechBubbleMode.INPUT) focusInputForTyping()
        windowManager.addView(root, params)
        added = true
        root.post {
            if (destroyedForCleanup || closingForUser) return@post
            if (mode == SpeechBubbleMode.REPLY && animationsEnabled()) {
                try {
                    contentView?.startAnimation(
                        AnimationUtils.loadAnimation(root.context, R.anim.bf_speech_enter),
                    )
                } catch (error: Exception) {
                    contentView?.clearAnimation()
                }
            }
        }
    }

    fun isInputMode(): Boolean = mode == SpeechBubbleMode.INPUT

    fun updatePlacement(
        placement: BubblePreviewPlacement,
        nextAnchor: BubblePreviewPoint? = anchor,
    ) {
        if (destroyedForCleanup || closingForUser) return
        val nextX = placement.rect.left - displayLeft
        val nextY = placement.rect.top - displayTop
        val sideChanged = side != placement.side
        val anchorChanged = anchor != nextAnchor
        params.x = nextX
        params.y = nextY
        params.width = plan.windowWidthPx
        params.height = plan.windowHeightPx
        appliedX = nextX
        appliedY = nextY
        side = placement.side
        anchor = nextAnchor
        if (sideChanged) layoutVisual(animate = false)
        else if (anchorChanged) positionTail()
        updateWindow()
    }

    /** Reply face update: one rebuild only when the plan/face changes. */
    fun applyState(
        snapshot: BubbleTurnSnapshot,
        failure: String,
        nextPlan: BubblePreviewLayoutPlan,
        placement: BubblePreviewPlacement,
        nextAnchor: BubblePreviewPoint?,
    ) {
        if (destroyedForCleanup || closingForUser) return
        val faceChanged = mode != SpeechBubbleMode.REPLY ||
            plan.windowWidthPx != nextPlan.windowWidthPx ||
            plan.windowHeightPx != nextPlan.windowHeightPx ||
            plan.showReplyBody != nextPlan.showReplyBody ||
            plan.showActions != nextPlan.showActions ||
            plan.bodyLines != nextPlan.bodyLines
        if (mode != SpeechBubbleMode.REPLY) releaseInputFocus()
        mode = SpeechBubbleMode.REPLY
        content = snapshot
        failureText = failure
        plan = nextPlan
        side = placement.side
        anchor = nextAnchor
        appliedX = placement.rect.left - displayLeft
        appliedY = placement.rect.top - displayTop
        params.width = nextPlan.windowWidthPx
        params.height = nextPlan.windowHeightPx
        params.x = appliedX
        params.y = appliedY
        if (faceChanged) {
            rebuild(animate = true)
        } else {
            updateTextViews()
        }
        updateWindow()
    }

    /** Input face update. Text-only edits never rebuild or touch WindowManager. */
    fun applyInputState(
        state: SpeechInputSnapshot,
        nextPlan: BubblePreviewLayoutPlan,
        placement: BubblePreviewPlacement,
        nextAnchor: BubblePreviewPoint?,
    ) {
        if (destroyedForCleanup || closingForUser) return
        val previousPlan = plan
        input = state
        plan = nextPlan
        side = placement.side
        anchor = nextAnchor
        appliedX = placement.rect.left - displayLeft
        appliedY = placement.rect.top - displayTop
        params.width = nextPlan.windowWidthPx
        params.height = nextPlan.windowHeightPx
        params.x = appliedX
        params.y = appliedY
        if (mode != SpeechBubbleMode.INPUT) {
            mode = SpeechBubbleMode.INPUT
            rebuild(animate = false)
        } else {
            if (previousPlan != nextPlan) {
                contentView?.layoutParams = FrameLayout.LayoutParams(
                    plan.windowWidthPx,
                    plan.windowHeightPx,
                )
                styleInputRow()
                inputView?.apply {
                    maxLines = plan.bodyLines.coerceAtLeast(1)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.bodyTextPx)
                }
                sendView?.setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.bodyTextPx)
            }
            layoutVisual(animate = false)
            updateInputViews()
        }
        updateWindow()
    }

    /** Text-only reply update: no rebuild, no WindowManager call. */
    fun updateText(snapshot: BubbleTurnSnapshot, failure: String) {
        if (destroyedForCleanup || closingForUser) return
        content = snapshot
        failureText = failure
        updateTextViews()
    }

    fun currentX(): Int = appliedX

    fun currentY(): Int = appliedY

    /**
     * Only the input face, only after it is attached and the user asked to
     * type. Removes NOT_FOCUSABLE, uses ADJUST_RESIZE, requestFocus and
     * SHOW_IMPLICIT. No alternate-IME flag and no forced show.
     */
    fun focusInputForTyping() {
        if (destroyedForCleanup || mode != SpeechBubbleMode.INPUT) return
        val view = inputView ?: return
        try {
            pendingKeyboardRequest = true
            keyboardAttemptsLeft = 10
            val inputFlags = baseFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            if (params.flags != inputFlags) {
                params.flags = inputFlags
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                if (added) windowManager.updateViewLayout(root, params)
            }
            view.requestFocus()
            showPendingKeyboard()
        } catch (error: Exception) {
            onInputNoSpace()
        }
    }

    private fun registerInputBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || unregisterInputBack != null) return
        val dispatcher = root.findOnBackInvokedDispatcher() ?: return
        val callback = OnBackInvokedCallback { requestUserClose() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        unregisterInputBack = { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    private fun showPendingKeyboard() {
        if (!pendingKeyboardRequest || !root.hasWindowFocus()) return
        root.removeCallbacks(requestKeyboard)
        root.post(requestKeyboard)
    }

    private fun requestKeyboardIfReady() {
        val view = inputView ?: return
        if (!pendingKeyboardRequest || !added || destroyedForCleanup || closingForUser ||
            mode != SpeechBubbleMode.INPUT || !view.hasWindowFocus()
        ) return
        if (!view.hasFocus()) view.requestFocus()
        registerInputBack()
        val imm = view.context.getSystemService(InputMethodManager::class.java)
        if (view.hasFocus() && imm?.isActive(view) == true &&
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        ) {
            pendingKeyboardRequest = false
            root.requestApplyInsets()
        } else if (--keyboardAttemptsLeft > 0) {
            // Window focus can arrive before the input method binds this editor.
            root.postDelayed(requestKeyboard, 50L)
        } else {
            pendingKeyboardRequest = false
        }
    }

    /**
     * Keyboard first, then clear focus, then restore NOT_FOCUSABLE. Every mode
     * switch, close, click-through, revoke and removal path calls this before
     * the content changes or the window is removed.
     */
    fun releaseInputFocus() {
        pendingKeyboardRequest = false
        root.removeCallbacks(requestKeyboard)
        imeBottomPx = 0
        try {
            unregisterInputBack?.invoke()
        } catch (_: RuntimeException) {
            // The dispatcher may already have detached with the window.
        } finally {
            unregisterInputBack = null
        }
        try {
            val view = inputView
            view?.clearFocus()
            val token = view?.windowToken
            val imm = root.context.getSystemService(InputMethodManager::class.java)
            if (token != null) imm?.hideSoftInputFromWindow(token, 0)
        } catch (error: Exception) {
            // Optional focus cleanup must not escape the removal path.
        }
        try {
            params.flags = baseFlags
            params.softInputMode = 0
            if (added) windowManager.updateViewLayout(root, params)
        } catch (error: Exception) {
            // A detached window is already as unfocusable as it can be.
        }
    }

    /** Hides the root before the bounded removal attempt, so no touch/visual leak remains. */
    fun hideForCleanup() {
        releaseInputFocus()
        destroyedForCleanup = true
        closingForUser = false
        exitRunnable?.let { root.removeCallbacks(it) }
        exitRunnable = null
        stopThinkingDots()
        contentView?.clearAnimation()
        contentView = null
        bodyContainer = null
        root.visibility = View.GONE
        root.isClickable = false
        bodyView = null
        chipView = null
        inputView = null
        sendView = null
        primaryReplyAction = null
        fullReplyAction = null
        tailView = null
    }

    fun removeImmediateOnce() {
        val outcome = BubblePreviewRemovalPolicy.remove(
            object : BubblePreviewRemovalPort {
                override fun isManaged(): Boolean =
                    root.parent != null || root.isAttachedToWindow

                override fun removeOnce() {
                    windowManager.removeViewImmediate(root)
                }
            },
        )
        if (!outcome.removed) {
            throw outcome.lastError ?: IllegalStateException("bubble root still attached")
        }
    }

    fun view(): View = root

    private var destroyedForCleanup = false

    private fun rebuild(animate: Boolean) {
        stopThinkingDots()
        bodyView = null
        chipView = null
        inputView = null
        sendView = null
        thinkingDots = null
        tailView = null
        contentView = null
        bodyContainer = null
        root.removeAllViews()
        val built = if (mode == SpeechBubbleMode.INPUT) {
            buildInputFace(root.context)
        } else {
            buildReplyFace(root.context)
        }
        contentView = built
        root.addView(
            built,
            FrameLayout.LayoutParams(plan.windowWidthPx, plan.windowHeightPx),
        )
        layoutVisual(animate = false)
        if (animate && animationsEnabled()) {
            try {
                built.startAnimation(
                    AnimationUtils.loadAnimation(root.context, R.anim.bf_speech_enter),
                )
            } catch (error: Exception) {
                built.clearAnimation()
            }
        }
    }

    private fun layoutVisual(animate: Boolean) {
        bodyContainer?.let { bodyChild ->
            val body = bodyRect()
            bodyChild.layoutParams = FrameLayout.LayoutParams(
                max(1, body.right - body.left),
                max(1, body.bottom - body.top),
            ).apply {
                leftMargin = body.left
                topMargin = body.top
            }
        }
        positionTail()
        if (animate && animationsEnabled()) {
            contentView?.startAnimation(
                AnimationUtils.loadAnimation(root.context, R.anim.bf_speech_exit),
            )
        }
    }

    private data class BodyRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun bodyRect(): BodyRect {
        val tailShort = ceil(plan.tailBudgetPx.toDouble()).toInt().coerceAtLeast(0)
        val chipHeight = if (mode == SpeechBubbleMode.REPLY) plan.chipHeightPx else 0
        val gap = if (mode == SpeechBubbleMode.REPLY) {
            ceil(plan.zoneGapPx.toDouble()).toInt()
        } else {
            0
        }
        val left = if (side == BubblePreviewSide.LEFT) tailShort else 0
        val right = plan.windowWidthPx -
            if (side == BubblePreviewSide.RIGHT) tailShort else 0
        val topReserve = if (side == BubblePreviewSide.BELOW) tailShort else 0
        val bottomReserve = if (side == BubblePreviewSide.ABOVE) tailShort else 0
        val top = chipHeight + gap + topReserve
        val bottom = plan.windowHeightPx - bottomReserve
        return BodyRect(left, top.coerceAtLeast(0), max(left + 1, right), max(top + 1, bottom))
    }

    private fun buildInputFace(context: Context): FrameLayout {
        val frame = FrameLayout(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val edit = object : AppCompatEditText(context) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK && imeBottomPx == 0) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) requestUserClose()
                    return true
                }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            background = null
            setTextColor(inputText)
            setHintTextColor(inputHint)
            hint = context.getString(R.string.bf_speech_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.bodyTextPx)
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            maxLines = plan.bodyLines.coerceAtLeast(1)
            minLines = 1
            isSingleLine = false
            ellipsize = null
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            isSaveEnabled = false
            isFocusableInTouchMode = true
            showSoftInputOnFocus = true
            setOnClickListener { focusInputForTyping() }
            filters = arrayOf(InputFilter.LengthFilter(ChatDraftState.MAX_DRAFT_CHARS))
            setPadding(dp(2), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        inputView = edit
        edit.setText(input?.text.orEmpty())
        edit.setSelection(edit.text?.length ?: 0)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingInputText) return
                val text = s?.toString().orEmpty()
                val updated = SpeechInputSnapshot(text = text, sendEnabled = text.isNotBlank())
                input = updated
                sendView?.isEnabled = updated.sendEnabled
                onInputDraftChanged(text)
            }
        })
        row.addView(edit)

        val expand = ImageView(context).apply {
            setImageResource(R.drawable.bf_speech_ic_expand)
            setColorFilter(iconColor)
            contentDescription = context.getString(R.string.bf_speech_expand_cd)
            background = ContextCompat.getDrawable(context, R.drawable.bf_speech_btn_expand_bg)
                ?.let { InsetDrawable(it, dp(5)) }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(touchPx(), touchPx()).apply {
                marginStart = dp(4)
            }
            setOnClickListener {
                onInputExpand(edit.text?.toString().orEmpty())
            }
        }
        row.addView(expand)

        val send = TextView(context).apply {
            text = context.getString(R.string.bf_speech_send)
            contentDescription = context.getString(R.string.bf_speech_send)
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.bodyTextPx)
            setTextColor(
                ContextCompat.getColorStateList(
                    context,
                    R.color.bf_speech_send_text_selector,
                ),
            )
            includeFontPadding = false
            background = ContextCompat.getDrawable(context, R.drawable.bf_speech_btn_send)
                ?.let { InsetDrawable(it, 0, dp(5), 0, dp(5)) }
            isClickable = true
            isFocusable = true
            minWidth = touchPx()
            minHeight = touchPx()
            layoutParams = LinearLayout.LayoutParams(
                max(touchPx(), dp(52)),
                touchPx(),
            ).apply { marginStart = dp(4) }
            isEnabled = input?.sendEnabled == true
            setOnClickListener {
                val text = edit.text?.toString().orEmpty()
                if (text.isNotBlank()) onInputSend(text)
            }
        }
        sendView = send
        row.addView(send)

        bodyContainer = row
        styleInputRow()
        frame.addView(row)
        return frame
    }

    private fun styleInputRow() {
        val row = bodyContainer ?: return
        val horizontal = plan.padHorizontalPx.roundToInt()
        val vertical = plan.padVerticalPx.roundToInt()
        row.setPadding(horizontal, vertical, horizontal, vertical)
        row.background = speechDrawable(
            R.drawable.bf_speech_input_body,
            inputFill,
            inputStroke,
            plan.cornerRadiusPx,
        )
    }

    private fun buildReplyFace(context: Context): FrameLayout {
        val frame = FrameLayout(context)
        val snapshot = content
        if (plan.showReplyBody) {
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP or Gravity.START
                setPadding(
                    plan.padHorizontalPx.roundToInt(),
                    plan.padVerticalPx.roundToInt(),
                    plan.padHorizontalPx.roundToInt(),
                    plan.padVerticalPx.roundToInt(),
                )
                background = replyDrawable()
            }
            val text = TextView(context).apply {
                text = bodyText()
                maxLines = plan.bodyLines.coerceAtLeast(1)
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.bodyTextPx)
                setTextColor(replyText)
                includeFontPadding = false
                gravity = Gravity.TOP or Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            }
            bodyView = text
            bodyContainer = body
            body.addView(text)
            if (snapshot?.visibleState == BubbleVisibleState.THINKING) {
                val dots = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(
                        dp(15),
                        dp(3),
                    ).apply { leftMargin = dp(4) }
                }
                thinkingDots = dots
                body.addView(dots)
                startThinkingDots(dots)
            }
            frame.addView(body)
        }
        if (plan.includeUserSummary) {
            val actionWidth = if (plan.showActions) 2 * touchPx() else 0
            val availableWidth = (plan.windowWidthPx - actionWidth).coerceAtLeast(1)
            val chipWidth = min(plan.chipWidthPx.coerceAtLeast(1), availableWidth)
            val chip = TextView(context).apply {
                text = content?.userSummary.orEmpty()
                maxLines = 1
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.labelTextPx)
                setTextColor(userText)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                val chipPad = (12f * (plan.speechScale / 0.85f) * density).roundToInt()
                setPadding(chipPad, 0, chipPad, 0)
                background = speechDrawable(
                    R.drawable.bf_speech_user_chip,
                    userFill,
                    userStroke,
                    14f * (plan.speechScale / 0.85f) * density,
                )
                layoutParams = FrameLayout.LayoutParams(
                    chipWidth,
                    plan.chipHeightPx.coerceAtLeast(1),
                ).apply {
                    leftMargin = availableWidth - chipWidth
                    topMargin = 0
                }
                setOnClickListener { onRecord() }
            }
            chipView = chip
            frame.addView(chip)
        }
        if (plan.showActions && snapshot != null) {
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                layoutParams = FrameLayout.LayoutParams(
                    touchPx() * 2,
                    plan.actionRowPx.coerceAtLeast(1),
                ).apply {
                    leftMargin = plan.windowWidthPx - touchPx() * 2
                }
            }
            val primary = actionIcon(
                context,
                R.drawable.bf_speech_ic_stop,
                R.string.bf_speech_stop_cd,
            ) { onStop() }
            primaryReplyAction = primary
            val fullReply = TextView(context).apply {
                setText(R.string.orb_reply_full_short)
                contentDescription = context.getString(R.string.orb_reply_full)
                setTextColor(iconColor)
                textSize = 12f
                gravity = Gravity.CENTER
                isFocusable = true
                setOnClickListener { onExpand() }
            }
            fullReplyAction = fullReply
            updateReplyAction()
            val close = actionIcon(
                context,
                R.drawable.bf_speech_ic_close,
                R.string.bf_speech_close_cd,
            ) { requestUserClose() }
            val primarySlot = FrameLayout(context).apply {
                addView(primary, FrameLayout.LayoutParams(touchPx(), touchPx()))
                addView(fullReply, FrameLayout.LayoutParams(touchPx(), touchPx()))
            }
            actions.addView(primarySlot, LinearLayout.LayoutParams(touchPx(), touchPx()))
            actions.addView(close, LinearLayout.LayoutParams(touchPx(), touchPx()))
            frame.addView(actions)
        }
        bodyContainer?.apply {
            isFocusable = true
            setOnClickListener { openReplyBody() }
            ViewCompat.addAccessibilityAction(
                this,
                context.getString(R.string.orb_reply_full),
            ) { _, _ ->
                onExpand()
                true
            }
        }
        return frame
    }

    private fun actionIcon(
        context: Context,
        icon: Int,
        description: Int,
        action: () -> Unit,
    ): ImageView = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(iconColor)
        contentDescription = context.getString(description)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12))
        isClickable = true
        isFocusable = true
        background = ContextCompat.getDrawable(context, R.drawable.bf_speech_btn_expand_bg)
            ?.let { InsetDrawable(it, dp(5)) }
        setOnClickListener { action() }
    }

    private fun positionTail() {
        if (plan.tailBudgetPx <= 0f || mode == SpeechBubbleMode.REPLY && !plan.showReplyBody) {
            (tailView?.parent as? FrameLayout)?.removeView(tailView)
            tailView = null
            return
        }
        val direction = tailDirectionFor(side)
        val body = bodyRect()
        val k = plan.speechScale / 0.85f
        val width: Int
        val height: Int
        when (direction) {
            BubblePreviewTailDirection.LEFT,
            BubblePreviewTailDirection.RIGHT,
            -> {
                width = ceil(resourceDp(R.dimen.bf_speech_tail_side_w) * k).toInt().coerceAtLeast(1)
                height = ceil(resourceDp(R.dimen.bf_speech_tail_side_h) * k).toInt().coerceAtLeast(1)
            }
            else -> {
                width = ceil(resourceDp(R.dimen.bf_speech_tail_w) * k).toInt().coerceAtLeast(1)
                height = ceil(resourceDp(R.dimen.bf_speech_tail_h) * k).toInt().coerceAtLeast(1)
            }
        }
        val x: Int
        val y: Int
        val localAnchorX = anchor?.x?.minus(displayLeft + appliedX)
        val localAnchorY = anchor?.y?.minus(displayTop + appliedY)
        val edgePadding = (plan.cornerRadiusPx * 0.65f).roundToInt()
        fun alongEdge(point: Float?, start: Int, end: Int, size: Int): Int {
            val middle = (start + end - size) / 2
            val min = start + edgePadding
            val max = end - edgePadding - size
            return if (max < min || point == null) middle
            else (point.roundToInt() - size / 2).coerceIn(min, max)
        }
        when (direction) {
            BubblePreviewTailDirection.DOWN -> {
                x = alongEdge(localAnchorX, body.left, body.right, width)
                y = body.bottom - dp(1)
            }
            BubblePreviewTailDirection.UP -> {
                x = alongEdge(localAnchorX, body.left, body.right, width)
                y = body.top - height + dp(1)
            }
            BubblePreviewTailDirection.LEFT -> {
                x = body.left - width + dp(1)
                y = alongEdge(localAnchorY, body.top, body.bottom, height)
            }
            BubblePreviewTailDirection.RIGHT -> {
                x = body.right - dp(1)
                y = alongEdge(localAnchorY, body.top, body.bottom, height)
            }
            BubblePreviewTailDirection.NONE -> return
        }
        val parent = contentView as? FrameLayout ?: return
        val existing = tailView
        if (existing != null && existing.tag == direction) {
            val layout = existing.layoutParams as FrameLayout.LayoutParams
            if (layout.leftMargin != x || layout.topMargin != y ||
                layout.width != width || layout.height != height
            ) {
                layout.width = width
                layout.height = height
                layout.leftMargin = x
                layout.topMargin = y
                existing.layoutParams = layout
            }
            return
        }
        if (existing != null) parent.removeView(existing)
        val view: View = if (mode == SpeechBubbleMode.REPLY && usePackReply) {
            BubbleTailView(
                context = root.context,
                direction = direction,
                fillColor = replyFill,
                strokeColor = replyStroke,
                density = density,
            )
        } else {
            ImageView(root.context).apply {
                setImageResource(tailResource(direction))
                scaleType = ImageView.ScaleType.FIT_XY
            }
        }
        tailView = view
        view.tag = direction
        parent.addView(
            view,
            FrameLayout.LayoutParams(width, height).apply {
                leftMargin = x
                topMargin = y
            },
        )
    }

    private fun tailResource(direction: BubblePreviewTailDirection): Int = when (direction) {
        BubblePreviewTailDirection.DOWN -> R.drawable.bf_speech_tail_down
        BubblePreviewTailDirection.UP -> R.drawable.bf_speech_tail_up
        BubblePreviewTailDirection.LEFT -> R.drawable.bf_speech_tail_left
        BubblePreviewTailDirection.RIGHT -> R.drawable.bf_speech_tail_right
        BubblePreviewTailDirection.NONE -> R.drawable.bf_speech_tail_down
    }

    private fun tailDirectionFor(side: BubblePreviewSide): BubblePreviewTailDirection =
        when (side) {
            // The tail points from the bubble toward the pet.
            BubblePreviewSide.ABOVE -> BubblePreviewTailDirection.DOWN
            BubblePreviewSide.BELOW -> BubblePreviewTailDirection.UP
            BubblePreviewSide.RIGHT -> BubblePreviewTailDirection.LEFT
            BubblePreviewSide.LEFT -> BubblePreviewTailDirection.RIGHT
        }

    private fun updateWindow() {
        if (!added || destroyedForCleanup) return
        val maxInputY = if (mode == SpeechBubbleMode.INPUT && imeBottomPx > 0) {
            displayHeight - imeBottomPx - plan.windowHeightPx - dp(12)
        } else {
            appliedY
        }
        if (maxInputY < dp(12) && mode == SpeechBubbleMode.INPUT && imeBottomPx > 0) {
            if (!imeFailureNotified) {
                imeFailureNotified = true
                onInputNoSpace()
            }
            return
        }
        params.y = min(appliedY, maxInputY)
        try {
            windowManager.updateViewLayout(root, params)
        } catch (error: Exception) {
            if (mode == SpeechBubbleMode.INPUT) onInputNoSpace() else throw error
        }
    }

    private fun updateTextViews() {
        val snapshot = content ?: return
        if (plan.showReplyBody) {
            bodyView?.text = bodyText()
            applyBodyDecoration()
            updateThinkingDots()
        }
        chipView?.text = snapshot.userSummary
        updateReplyAction()
    }

    private fun canStopReply(): Boolean =
        content?.phase == BubbleTurnPhase.THINKING || content?.phase == BubbleTurnPhase.STREAMING

    private fun updateReplyAction() {
        val canStop = canStopReply()
        primaryReplyAction?.visibility = if (canStop) View.VISIBLE else View.GONE
        fullReplyAction?.visibility = if (canStop) View.GONE else View.VISIBLE
    }

    private fun openReplyBody() {
        val layout = bodyView?.layout
        val clipped = layout != null && layout.lineCount > 0 && layout.getEllipsisCount(layout.lineCount - 1) > 0
        if (content?.overflow == true || clipped) {
            onExpand()
        } else {
            onRecord()
        }
    }

    private fun updateInputViews() {
        val state = input ?: return
        val edit = inputView ?: return
        if (edit.text?.toString() != state.text) {
            applyingInputText = true
            try {
                edit.setText(state.text)
                edit.setSelection(edit.text?.length ?: 0)
            } finally {
                applyingInputText = false
            }
        }
        sendView?.isEnabled = state.sendEnabled
    }

    private fun applyBodyDecoration() {
        val body = bodyView ?: return
        val snapshot = content ?: return
        val icon = when {
            snapshot.visibleState == BubbleVisibleState.THINKING -> 0
            snapshot.visibleState == BubbleVisibleState.ERROR ||
                snapshot.phase == BubbleTurnPhase.FAILED ->
                R.drawable.bf_speech_ic_error
            snapshot.status == BubbleTurnStatus.STOPPED ||
                snapshot.phase == BubbleTurnPhase.STOPPED ->
                R.drawable.bf_speech_ic_stop
            else -> 0
        }
        if (icon == 0) {
            body.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        } else {
            body.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            body.compoundDrawablePadding = dp(4)
        }
    }

    private fun updateThinkingDots() {
        val dots = thinkingDots ?: return
        val shouldThink = content?.visibleState == BubbleVisibleState.THINKING
        dots.visibility = if (shouldThink) View.VISIBLE else View.GONE
        if (shouldThink) startThinkingDots(dots) else (dots.drawable as? Animatable)?.stop()
    }

    private fun startThinkingDots(dots: ImageView) {
        (dots.drawable as? Animatable)?.stop()
        if (animationsEnabled()) {
            dots.setImageResource(R.drawable.bf_speech_dots)
            (dots.drawable as? Animatable)?.start()
        } else {
            dots.setImageResource(R.drawable.bf_speech_ellipsis_static)
        }
    }

    private fun stopThinkingDots() {
        (thinkingDots?.drawable as? Animatable)?.stop()
        thinkingDots = null
    }

    /** System animator duration 0 or disabled animators use the static dots. */
    private fun animationsEnabled(): Boolean {
        val scale = try {
            Settings.Global.getFloat(
                root.context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        } catch (_: Exception) {
            1f
        }
        return BubblePreviewAnimationPolicy.enabled(
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
            durationScale = scale,
        )
    }

    private fun requestUserClose() {
        if (destroyedForCleanup || closingForUser) return
        closingForUser = true
        releaseInputFocus()
        val finish = Runnable {
            exitRunnable = null
            if (destroyedForCleanup) return@Runnable
            onClose()
        }
        if (!animationsEnabled()) {
            finish.run()
            return
        }
        val child = contentView
        if (child == null) {
            finish.run()
            return
        }
        child.clearAnimation()
        try {
            child.startAnimation(AnimationUtils.loadAnimation(root.context, R.anim.bf_speech_exit))
            exitRunnable = finish
            // Ordinary user close may show the 80 ms exit; click-through,
            // hiding, revoke and round invalidation still remove immediately.
            root.postDelayed(finish, EXIT_ANIMATION_MILLIS)
        } catch (error: Exception) {
            finish.run()
        }
    }

    private fun bodyText(): String {
        val snapshot = content ?: return ""
        val visible = snapshot.visibleText
        val state = snapshot.visibleState
        if (state == BubbleVisibleState.THINKING) {
            return root.context.getString(R.string.bf_speech_thinking)
        }
        if (state == BubbleVisibleState.ERROR || snapshot.phase == BubbleTurnPhase.FAILED) {
            val failure = root.context.getString(R.string.bf_speech_error)
            if (visible.isBlank()) return failureText.ifBlank { failure }
            return "$failure\n$visible"
        }
        if (snapshot.status == BubbleTurnStatus.STOPPED ||
            snapshot.phase == BubbleTurnPhase.STOPPED
        ) {
            val stopped = root.context.getString(R.string.bf_speech_stopped)
            return if (visible.isBlank()) stopped else "$stopped\n$visible"
        }
        if (snapshot.overflow &&
            visible.isNotBlank() && !visible.endsWith("…")
        ) {
            return visible + "…"
        }
        return visible
    }

    private fun replyDrawable(): Drawable {
        if (!usePackReply) {
            val resource = ContextCompat.getDrawable(
                root.context,
                R.drawable.bf_speech_reply_body,
            )?.mutate()
            if (resource is GradientDrawable) {
                resource.cornerRadius = plan.cornerRadiusPx
                return resource
            }
            if (resource != null) return resource
        }
        return rounded(replyFill, replyStroke, plan.cornerRadiusPx)
    }

    private fun speechDrawable(
        resId: Int,
        fill: Int,
        stroke: Int,
        radius: Float,
    ): Drawable {
        val resource = ContextCompat.getDrawable(root.context, resId)?.mutate()
        if (resource is GradientDrawable) {
            resource.cornerRadius = radius
            return resource
        }
        return if (resource != null) resource else rounded(fill, stroke, radius)
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(1, stroke)
            cornerRadius = radius
        }

    private fun installImeListener() {
        try {
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                if (mode == SpeechBubbleMode.INPUT && added && !destroyedForCleanup) {
                    refreshInputImeInset(insets)
                }
                insets
            }
        } catch (error: Exception) {
            // A missing insets callback must not break the add path; the host
            // then falls back to the record panel if the keyboard cannot fit.
        }
    }

    private fun refreshInputImeInset(
        localInsets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(root),
    ) {
        val displayBottom = displayTop + displayHeight
        val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // A compact overlay may receive no local IME overlap once it moves
            // above the keyboard. Keep using display-relative insets.
            try {
                windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.ime()).bottom
            } catch (_: RuntimeException) {
                localInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            }
        } else {
            root.getWindowVisibleDisplayFrame(visibleDisplayFrame)
            if (localInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                visibleDisplayFrame.bottom in (displayTop + 1)..displayBottom
            ) {
                displayBottom - visibleDisplayFrame.bottom
            } else {
                0
            }
        }
        if (this.imeBottomPx == bottom) return
        this.imeBottomPx = bottom
        updateWindow()
    }

    private fun touchPx(): Int = ceil(resourceDp(R.dimen.bf_speech_action_min))
        .toInt()
        .coerceAtLeast(1)

    private fun resourceDp(id: Int): Float = root.context.resources.getDimension(id)

    private fun color(id: Int): Int = ContextCompat.getColor(root.context, id)

    private fun dp(value: Int): Int = (value * density).roundToInt()
}

/**
 * Lightweight Canvas tail for a cached pack palette. The path mirrors the
 * formal bf_speech_tail_* vector (12x6 / 6x12 viewport) but keeps fill and
 * stroke as separate layers, which a single vector tint cannot do.
 */
private class BubbleTailView(
    context: Context,
    private val direction: BubblePreviewTailDirection,
    private val fillColor: Int,
    private val strokeColor: Int,
    density: Float,
) : View(context) {
    private val strokeWidth = density.coerceAtLeast(0.5f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        strokeWidth = this@BubbleTailView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val body = Path()
    private val seam = Path()
    private val outline = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        buildPaths(width, height)
        canvas.drawPath(body, fill)
        canvas.drawPath(seam, fill)
        canvas.drawPath(outline, stroke)
    }

    private fun buildPaths(width: Float, height: Float) {
        body.reset()
        seam.reset()
        outline.reset()
        val sx: Float
        val sy: Float
        when (direction) {
            BubblePreviewTailDirection.LEFT,
            BubblePreviewTailDirection.RIGHT,
            -> {
                sx = width / 6f
                sy = height / 12f
            }
            else -> {
                sx = width / 12f
                sy = height / 6f
            }
        }
        fun x(value: Float): Float = value * sx
        fun y(value: Float): Float = value * sy
        when (direction) {
            BubblePreviewTailDirection.DOWN -> {
                body.moveTo(x(0.5f), y(0f))
                body.lineTo(x(11.5f), y(0f))
                body.lineTo(x(11.5f), y(1f))
                body.cubicTo(x(10.7f), y(2.8f), x(8.3f), y(5.5f), x(6f), y(5.5f))
                body.cubicTo(x(3.7f), y(5.5f), x(1.3f), y(2.8f), x(0.5f), y(1f))
                body.close()
                seam.addRect(x(0.5f), y(0f), x(11.5f), y(1f), Path.Direction.CW)
                outline.moveTo(x(0.5f), y(1f))
                outline.cubicTo(x(1.3f), y(2.8f), x(3.7f), y(5.5f), x(6f), y(5.5f))
                outline.moveTo(x(11.5f), y(1f))
                outline.cubicTo(x(10.7f), y(2.8f), x(8.3f), y(5.5f), x(6f), y(5.5f))
            }
            BubblePreviewTailDirection.UP -> {
                body.moveTo(x(0.5f), y(6f))
                body.lineTo(x(11.5f), y(6f))
                body.lineTo(x(11.5f), y(5f))
                body.cubicTo(x(10.7f), y(3.2f), x(8.3f), y(0.5f), x(6f), y(0.5f))
                body.cubicTo(x(3.7f), y(0.5f), x(1.3f), y(3.2f), x(0.5f), y(5f))
                body.close()
                seam.addRect(x(0.5f), y(5f), x(11.5f), y(6f), Path.Direction.CW)
                outline.moveTo(x(0.5f), y(5f))
                outline.cubicTo(x(1.3f), y(3.2f), x(3.7f), y(0.5f), x(6f), y(0.5f))
                outline.moveTo(x(11.5f), y(5f))
                outline.cubicTo(x(10.7f), y(3.2f), x(8.3f), y(0.5f), x(6f), y(0.5f))
            }
            BubblePreviewTailDirection.LEFT -> {
                body.moveTo(x(6f), y(0.5f))
                body.lineTo(x(6f), y(11.5f))
                body.lineTo(x(5f), y(11.5f))
                body.cubicTo(x(3.2f), y(10.7f), x(0.5f), y(8.3f), x(0.5f), y(6f))
                body.cubicTo(x(0.5f), y(3.7f), x(3.2f), y(1.3f), x(5f), y(0.5f))
                body.close()
                seam.addRect(x(5f), y(0.5f), x(6f), y(11.5f), Path.Direction.CW)
                outline.moveTo(x(5f), y(0.5f))
                outline.cubicTo(x(3.2f), y(1.3f), x(0.5f), y(3.7f), x(0.5f), y(6f))
                outline.moveTo(x(5f), y(11.5f))
                outline.cubicTo(x(3.2f), y(10.7f), x(0.5f), y(8.3f), x(0.5f), y(6f))
            }
            BubblePreviewTailDirection.RIGHT -> {
                body.moveTo(x(0f), y(0.5f))
                body.lineTo(x(0f), y(11.5f))
                body.lineTo(x(1f), y(11.5f))
                body.cubicTo(x(2.8f), y(10.7f), x(5.5f), y(8.3f), x(5.5f), y(6f))
                body.cubicTo(x(5.5f), y(3.7f), x(2.8f), y(1.3f), x(1f), y(0.5f))
                body.close()
                seam.addRect(x(0f), y(0.5f), x(1f), y(11.5f), Path.Direction.CW)
                outline.moveTo(x(1f), y(0.5f))
                outline.cubicTo(x(2.8f), y(1.3f), x(5.5f), y(3.7f), x(5.5f), y(6f))
                outline.moveTo(x(1f), y(11.5f))
                outline.cubicTo(x(2.8f), y(10.7f), x(5.5f), y(8.3f), x(5.5f), y(6f))
            }
            BubblePreviewTailDirection.NONE -> Unit
        }
    }
}
