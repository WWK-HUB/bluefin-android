package com.wwkhub.bluefin

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.Keep
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.wwkhub.bluefin.bubblepreview.BubblePreviewAttachDecision
import com.wwkhub.bluefin.bubblepreview.BubblePreviewChatPanelFrame
import com.wwkhub.bluefin.bubblepreview.BubblePreviewChatSignal
import com.wwkhub.bluefin.bubblepreview.BubblePreviewController
import com.wwkhub.bluefin.bubblepreview.BubblePreviewDisplayAttempt
import com.wwkhub.bluefin.bubblepreview.BubblePreviewDisplayBasis
import com.wwkhub.bluefin.bubblepreview.BubblePreviewFirstAttachFlow
import com.wwkhub.bluefin.bubblepreview.BubblePreviewGeometrySnapshot
import com.wwkhub.bluefin.bubblepreview.BubblePreviewHit
import com.wwkhub.bluefin.bubblepreview.BubblePreviewHitGeometry
import com.wwkhub.bluefin.bubblepreview.BubblePreviewHostActions
import com.wwkhub.bluefin.bubblepreview.BubblePreviewHostFrame
import com.wwkhub.bluefin.bubblepreview.BubblePreviewImeGate
import com.wwkhub.bluefin.bubblepreview.BubblePreviewInsets
import com.wwkhub.bluefin.bubblepreview.BubblePreviewMeasure
import com.wwkhub.bluefin.bubblepreview.BubblePreviewMeasureResult
import com.wwkhub.bluefin.bubblepreview.BubblePreviewModule
import com.wwkhub.bluefin.bubblepreview.BubblePreviewExplicitChatEntry
import com.wwkhub.bluefin.bubblepreview.BubblePreviewOrbProjection
import com.wwkhub.bluefin.bubblepreview.BubblePreviewPanelGeometry
import com.wwkhub.bluefin.bubblepreview.BubblePreviewPoint
import com.wwkhub.bluefin.bubblepreview.BubblePreviewRect
import com.wwkhub.bluefin.bubblepreview.BubblePreviewScreenCoordinates
import com.wwkhub.bluefin.bubblepreview.BubblePreviewScreenSnapshot
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full-screen transparent stage plus a pet-sized touch window.
 * The engine draws the character on the screen (roam, lift, throw, poke).
 * Touches outside the hit circles pass through to other apps.
 */
internal class OverlayWindow(
    context: Context,
    private val onHideRequested: () -> Unit,
    private val onReturnToAppRequested: () -> Unit,
    private val onUnexpectedDetach: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val uiContext = createUiContext(appContext)
    private val themedContext = android.view.ContextThemeWrapper(uiContext, R.style.Theme_Bluefin)
    private val windowManager = uiContext.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val cuePlayer = CuePlayer()
    private val root: FrameLayout
    private val web: WebView
    private val params: WindowManager.LayoutParams
    private val hit = View(themedContext)
    private val hitParams: WindowManager.LayoutParams
    private val orbRoot: FrameLayout
    private val orbParams: WindowManager.LayoutParams
    private val orbButton: View
    private val orbPanel: View
    private val chatPanel: View
    private val chatTranscript: ChatTranscriptView
    private val chatScroll: android.widget.ScrollView
    private val chatInput: android.widget.EditText
    private val chatSend: View
    private val chatStop: View
    private val chatStatus: TextView
    private val chatHint: TextView
    private val chatTitle: TextView
    private val chatSettings: View
    private val chatCollapse: View
    private val chatFull: View
    private val orbUnconfigured: View
    private var chatExpanded = false
    private var chatSession: ChatSession? = null
    private var chatListener: ((ChatSnapshot) -> Unit)? = null
    private var orbExpanded = false
    private var attached = false
    private var hitAttached = false
    private var clickThrough = false
    private var orbAttached = false
    private var orbSideRight = true
    private var orbYDp = Float.NaN
    private var orbSyncing = false
    private var orbDragging = false
    private var orbMoved = false
    private var orbDownRawX = 0f
    private var orbDownRawY = 0f
    private var orbDownWinX = 0
    private var orbDownWinY = 0
    private var destroyed = false
    private var closing = false
    private var pageReady = false
    private var tracking = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var lastMode = ""
    private var resting = false
    private var lastInteract = SystemClock.uptimeMillis()
    private var lastTurnAt = 0L
    private var webDestroyed = false
    private var currentPackId = PackFormat.BUILT_IN_ID
    private var currentManifest: PackManifest? = null
    private var loadedPackKey = ""

    // Optional stage-B surface. These are inert nullable references: nothing is
    // constructed, inflated, read or measured unless an explicit overlay chat
    // send returned STARTED and the compiled gate plus user switch are on.
    private var bubblePreview: BubblePreviewController? = null
    private var bubbleHostGeneration = 0L
    private var bubbleGeometryGeneration = 0L
    private var bubbleScreenSnapshotGeneration = -1L
    private var bubbleScreenSnapshot: BubblePreviewScreenSnapshot? = null
    private var bubbleDisplayBasis: BubblePreviewDisplayBasis? = null
    private var bubbleDisplayAttempt: BubblePreviewDisplayAttempt? = null
    private var bubbleHitBaseLeft = 0
    private var bubbleHitBaseTop = 0
    private var bubbleRequestToken = 0L
    private var petHiddenForBubble = false
    private var petOpacityForBubble = 1f

    // Host-side state for the single accepted round. The session snapshot is
    // consumed transiently; only the accepted user-line index, request
    // generation and a bounded summary survive.
    private var bubbleChatListener: ((ChatSnapshot) -> Unit)? = null
    private var bubbleSignalToken: Long? = null
    private var bubbleSignalUserIndex: Int = -1
    private var bubbleSignalGeneration: Int = -1
    private var bubblePendingSummary: String = ""
    private var bubblePendingGeneration: Int = -1
    private var bubblePendingUserIndex: Int = -1
    private var bubbleFirstAttach: BubblePreviewFirstAttachFlow? = null
    private var bubbleFirstAttachToken: Long = 0L
    private var bubbleFirstAttachDeadline: Runnable? = null
    private var bubbleAwaitingAttachToken: Long? = null
    private var bubbleImeGate: BubblePreviewImeGate? = null
    private var bubbleInsetsInstalled = false
    private var bubbleInsetsEpoch: Runnable? = null
    private var bubbleAppVisibilityInstalled = false
    private var bubbleAppVisibilityListener: OverlayAppVisibility.Listener? = null
    private val chatVisibilityListener = OverlayAppVisibility.Listener { updateChatVisibility(it) }
    private var bubbleDraftWatcherInstalled = false
    private var bubbleInputToken = 0L
    private var bubbleInputPending = false
    private var bubbleInputDeadline: Runnable? = null
    private var applyingDraft = false
    private var panelInsetsInstalled = false
    private var panelInsetsListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    private var applyingPanelHeight = false
    private var chatPanelBaselineHeightPx = 0
    private var chatPanelBaselineTopPx = 0
    private var chatPanelCompact = false
    private var chatSettingsShouldShow = false
    private var bubbleGeometryFrameSeen = false

    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            relayout()
        }

        override fun onLowMemory() = Unit

        override fun onTrimMemory(level: Int) = Unit
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            relayout()
        }
    }

    init {
        checkPlatformFlags()
        clickThrough = BluefinStore.readClickThrough(prefs())
        root = inflateRoot()
        web = root.findViewById(R.id.overlay_web)
        params = fullscreenParams(
            touchable = false,
            title = OverlayLayout.WINDOW_TITLE_STAGE,
            alpha = stageAlphaForCurrentDevice(),
        )
        hitParams = fullscreenParams(touchable = true, title = OverlayLayout.WINDOW_TITLE_PET)
        orbRoot = android.view.LayoutInflater.from(themedContext)
            .inflate(R.layout.overlay_orb, null) as FrameLayout
        orbParams = fullscreenParams(touchable = true, title = OverlayLayout.WINDOW_TITLE_ORB)
        orbButton = orbRoot.findViewById(R.id.overlay_orb_button)
        orbPanel = orbRoot.findViewById(R.id.overlay_orb_panel)
        chatPanel = orbRoot.findViewById(R.id.overlay_chat_panel)
        chatScroll = orbRoot.findViewById(R.id.overlay_chat_scroll)
        chatTranscript = ChatTranscriptView(
            chatScroll,
            orbRoot.findViewById(R.id.overlay_chat_messages),
            inOverlay = true,
        )
        chatInput = orbRoot.findViewById(R.id.overlay_chat_input)
        chatSend = orbRoot.findViewById(R.id.overlay_chat_send)
        chatStop = orbRoot.findViewById(R.id.overlay_chat_stop)
        chatStatus = orbRoot.findViewById(R.id.overlay_chat_status)
        chatHint = orbRoot.findViewById(R.id.overlay_chat_hint)
        chatTitle = orbRoot.findViewById(R.id.overlay_chat_title)
        chatSettings = orbRoot.findViewById(R.id.overlay_chat_settings)
        chatCollapse = orbRoot.findViewById(R.id.overlay_chat_collapse)
        chatFull = orbRoot.findViewById(R.id.overlay_chat_full)
        orbUnconfigured = orbRoot.findViewById(R.id.overlay_orb_unconfigured)
        chatInput.isSaveEnabled = false
        loadOrbPosition()
        setupWebView()
        setupHit()
        setupOrb()
        OverlayAppVisibility.addChatVisibilityListener(chatVisibilityListener)
        updateChatVisibility(OverlayAppVisibility.chatVisible)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (closing || destroyed) return
                attached = false
                handler.post { onUnexpectedDetach() }
            }
        })
    }

    fun show() {
        if (attached || destroyed) return
        applyStageSize()
        registerDisplayCallbacks()
        windowManager.addView(root, params)
        attached = true
        layoutOrb()
        loadedPackKey = cheapPackKey()
        web.loadUrl(cheapStageUrl())
        handler.post { if (attached && !destroyed) syncFromPrefs() }
    }

    fun close() {
        if (destroyed) return
        closing = true
        val bubble = bubblePreview
        try {
            // Optional cleanup must never be able to skip the rc3 host cleanup
            // below. The controller has its own bounded removal and keeps an
            // unresolved orphan reference for the caller. Only the optional
            // Exception is contained; the host's own cleanup exceptions are not.
            cancelBubbleFirstAttach()
            cancelSpeechInputDeadline()
            bubbleInputPending = false
            bubbleInputToken = 0L
            detachBubbleChatListener()
            cancelBubbleInsetsEpoch()
            removeBubbleHostListeners()
            bubble?.close()
        } catch (error: Exception) {
            Log.w(TAG, "optional bubble close contained type=${error.javaClass.name}")
        } finally {
            OverlayAppVisibility.removeChatVisibilityListener(chatVisibilityListener)
            if (bubble?.hasUnresolvedCleanup() != true) bubblePreview = null
            chatSession?.stop()
            chatListener?.let { listener -> chatSession?.removeListener(listener) }
            chatListener = null
            chatSession = null
            val wasAttached = attached
            attached = false
            unregisterDisplayCallbacks()
            removeIfAttached(hit, hitAttached)
            hitAttached = false
            removeIfAttached(orbRoot, orbAttached)
            orbAttached = false
            if (wasAttached) {
                try {
                    windowManager.removeViewImmediate(root)
                } catch (error: IllegalArgumentException) {
                    Log.w(TAG, "overlay was already detached", error)
                }
            }
            destroyWebView()
            destroyed = true
        }
    }

    fun syncFromPrefs() {
        if (destroyed) return
        applyClickThroughSetting()
        refreshPackIfNeeded()
        refreshOrbControls()
        if (pageReady) pushPrefs()
        // Orb size lives only in prefs, so always relayout a live overlay too.
        layoutOrb()
        chatSession?.let { renderChat(it.snapshot()) }
        bubblePreview?.let { bubble ->
            runBubbleHostCallback("sync") {
                petHiddenForBubble = prefs().getBoolean(BluefinStore.KEY_HIDDEN, false)
                petOpacityForBubble = BluefinStore.readOpacity(prefs())
                bubble.onHostVisibilityChanged(petHiddenForBubble, petOpacityForBubble)
            }
        }
    }

    /**
     * Click-through only drops or restores the pet hit window. The stage, orb,
     * panel and chat keep their own windows, and nothing is rebuilt here.
     */
    private fun applyClickThroughSetting() {
        val enabled = BluefinStore.readClickThrough(prefs())
        val changed = enabled != clickThrough
        clickThrough = enabled
        if (enabled) {
            dropHitWindow()
            bubbleInputPending = false
            cancelSpeechInputDeadline()
            bubblePreview?.let { runBubbleHostCallback("click-through") { it.onClickThroughEnabled() } }
        } else if (changed && pageReady) {
            // Ask the page to publish now so poking works again immediately
            // rather than waiting for the next animation layout tick.
            web.evaluateJavascript(
                "window.__bluefinOverlay&&window.__bluefinOverlay.publish()",
                null,
            )
        }
    }

    /**
     * Cheap identity read for the synchronous show() path. It only touches
     * prefs and the already-installed pack directory; it never scans, parses or
     * initializes PackRepository. If anything is uncertain, Route B wins.
     */
    private fun cheapPackKey(): String {
        val id = currentIdFromPrefs()
        currentPackId = id
        val scheme = if (id == PackFormat.BUILT_IN_ID) "" else storedScheme(id)
        return "$id|$scheme"
    }

    private fun cheapStageUrl(): String {
        val id = currentPackId
        if (id == PackFormat.BUILT_IN_ID) return PreviewEntry.urlFor(PreviewEntry.ROUTE_B)
        val dir = File(PackRepository.packRoot(appContext), id)
        if (!File(dir, PackImportEngine.MANIFEST_FILE).isFile) {
            return PreviewEntry.urlFor(PreviewEntry.ROUTE_B)
        }
        return PreviewEntry.packHostUrl(id, storedScheme(id))
    }

    private fun currentIdFromPrefs(): String =
        prefs().getString(BluefinStore.KEY_CHARACTER_ID, PackFormat.BUILT_IN_ID)
            ?.takeIf { PackPaths.validId(it) }
            ?: PackFormat.BUILT_IN_ID

    private fun storedScheme(id: String): String =
        prefs().getString(BluefinStore.KEY_CHARACTER_SCHEME_PREFIX + id, "").orEmpty().trim()

    private fun refreshPackIfNeeded() {
        val resolved = PackRepository.resolveCurrent(appContext)
        if (resolved.fellBack) {
            Toast.makeText(appContext, R.string.character_fell_back, Toast.LENGTH_SHORT).show()
        }
        val appearance = resolved.appearance
        currentPackId = appearance.id
        currentManifest = appearance.manifest
        val scheme = if (appearance.builtIn) "" else storedScheme(appearance.id)
        val key = appearance.id + "|" + scheme
        if (key == loadedPackKey) return
        // If the first page is still loading, leave the old key in place so the
        // page-finished sync notices the switch and loads the selected pack.
        if (!pageReady) return
        loadedPackKey = key
        pageReady = false
        bubbleGeometryGeneration += 1
        bubblePreview?.let { runBubbleHostCallback("page-reset") { it.onHostPageReset() } }
        web.loadUrl(stageUrlFor(appearance))
    }

    private fun stageUrlFor(appearance: PackAppearance): String {
        if (appearance.builtIn) return PreviewEntry.urlFor(PreviewEntry.ROUTE_B)
        return PreviewEntry.packHostUrl(
            appearance.id,
            PackRepository.readScheme(appContext, appearance.manifest),
        )
    }

    @SuppressLint("InflateParams")
    private fun inflateRoot(): FrameLayout {
        return android.view.LayoutInflater.from(themedContext)
            .inflate(R.layout.overlay_window, null) as FrameLayout
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", AssetMimeHandler(uiContext))
            .addPathHandler("/packs/", PackAssetHandler(uiContext))
            .build()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        root.setBackgroundColor(0)
        web.setBackgroundColor(0)
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.isLongClickable = false
        web.isHapticFeedbackEnabled = false
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        web.setOnLongClickListener { true }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            safeBrowsingEnabled = false
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) forceDark = WebSettings.FORCE_DARK_OFF
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isAlgorithmicDarkeningAllowed = false
        }
        web.addJavascriptInterface(
            BluefinBridge(object : BluefinHost {
                override fun isSoundOn(): Boolean = soundOn()
                override fun onScriptMessage(message: String) {
                    Log.w(TAG, message)
                }

                override fun onPlayCue(name: String, kind: String) {
                    if (!soundOn()) return
                    val cue = name.ifBlank { kind }
                    handler.post { cuePlayer.play(cue) }
                }
            }),
            BRIDGE_NAME,
        )
        web.addJavascriptInterface(OverlayLayoutSink { json -> handler.post { onLayout(json) } }, OVERLAY_BRIDGE)
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                Log.i(TAG, "console ${consoleMessage.message()}")
                return true
            }
        }
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.host != "appassets.androidplatform.net"
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (destroyed || !attached) return
                if (!url.contains("index.html") && !url.contains("pack-host.html")) return
                pageReady = true
                injectPage()
                handler.post { if (attached && !destroyed) syncFromPrefs() }
            }
        }
    }

    private fun injectPage() {
        val bridge = uiContext.assets.open("native/bridge.js").bufferedReader().use { it.readText() }
        val stage = uiContext.assets.open("native/overlay-stage.js").bufferedReader().use { it.readText() }
        web.evaluateJavascript(bridge, null)
        web.evaluateJavascript(stage, null)
        pushPrefs()
    }

    private fun pushPrefs() {
        if (destroyed) return
        val roam = prefs().getBoolean(BluefinStore.KEY_ROAM, true) &&
            (currentManifest?.canWalk != false)
        val hidden = prefs().getBoolean(BluefinStore.KEY_HIDDEN, false)
        val scale = OverlayPetPolicy.scaleFor(BluefinStore.readScale(prefs()))
        val opacity = BluefinStore.readOpacity(prefs())
        petHiddenForBubble = hidden
        petOpacityForBubble = opacity
        if (hidden || opacity <= 0f) {
            bubblePreview?.let { bubble ->
                runBubbleHostCallback("push-prefs") {
                    bubble.onHostVisibilityChanged(hidden, opacity)
                }
            }
        }
        val scaleJs = String.format(Locale.US, "%.2f", scale)
        val opacityJs = String.format(Locale.US, "%.2f", opacity)
        web.evaluateJavascript(
            "window.__bluefinNative&&window.__bluefinNative.applyPrefs($roam,$scaleJs,$hidden,$opacityJs)",
            null,
        )
        web.evaluateJavascript(
            "window.__bluefinApplySound&&window.__bluefinApplySound(${soundOn()})",
            null,
        )
        web.evaluateJavascript(
            "window.__bluefinOverlay&&window.__bluefinOverlay.setScale($scaleJs)",
            null,
        )
        web.evaluateJavascript(
            "window.__bluefinOverlay&&window.__bluefinOverlay.setOpacity($opacityJs)",
            null,
        )
        refreshOrbControls()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHit() {
        hit.setBackgroundColor(0)
        hit.setOnTouchListener { _, event ->
            val cssX = cssX(event.rawX)
            val cssY = cssY(event.rawY)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    resting = false
                    lastInteract = SystemClock.uptimeMillis()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWinX = hitParams.x
                    downWinY = hitParams.y
                    sendPointer("down", cssX, cssY)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (tracking) moveHitWithFinger(event.rawX, event.rawY)
                    sendPointer("move", cssX, cssY)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    tracking = false
                    lastInteract = SystemClock.uptimeMillis()
                    sendPointer("up", cssX, cssY)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    sendPointer("cancel", cssX, cssY)
                    true
                }

                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOrb() {
        orbPanel.isClickable = true
        chatPanel.isClickable = true
        orbRoot.setOnTouchListener { _, event ->
            // The expanded panel window is full screen only so a tap outside the
            // panel can collapse it. It consumes that outside tap and never
            // forwards it to the character.
            if ((orbExpanded || chatExpanded) && event.actionMasked == MotionEvent.ACTION_DOWN) {
                collapseOrb()
                true
            } else {
                false
            }
        }
        orbButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    orbDragging = true
                    orbMoved = false
                    orbDownRawX = event.rawX
                    orbDownRawY = event.rawY
                    orbDownWinX = orbParams.x
                    orbDownWinY = orbParams.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!orbDragging) return@setOnTouchListener false
                    if (!orbExpanded) {
                        val dx = event.rawX - orbDownRawX
                        val dy = event.rawY - orbDownRawY
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > dp(6)) orbMoved = true
                        if (orbMoved) {
                            val (sw, sh) = screenSize()
                            val (x, y) = OverlayLayout.clamp(
                                orbDownWinX + dx.roundToInt(),
                                orbDownWinY + dy.roundToInt(),
                                orbParams.width,
                                orbParams.height,
                                sw,
                                sh,
                            )
                            orbParams.x = x
                            orbParams.y = y
                            updateOrbWindow()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!orbDragging) return@setOnTouchListener true
                    orbDragging = false
                    if (orbExpanded) {
                        collapseOrb()
                    } else if (orbMoved) {
                        val (sw, _) = screenSize()
                        orbSideRight = orbParams.x + orbParams.width / 2 >= sw / 2
                        orbYDp = orbParams.y / density()
                        persistOrbPosition()
                        layoutOrb()
                    } else {
                        expandOrb()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    orbDragging = false
                    layoutOrb()
                    true
                }

                else -> false
            }
        }
        addOrbPanelListeners()
        chatSend.setOnClickListener { sendFromOverlay() }
        chatStop.setOnClickListener { chatSession?.stop() }
        chatPanel.findViewById<View>(R.id.overlay_chat_full).setOnClickListener { openFullChat() }
        chatHint.setOnClickListener { openModelSettingsFromOverlay() }
        chatStatus.setOnClickListener { openModelSettingsFromOverlay() }
        refreshOrbControls()
    }

    private fun addOrbPanelListeners() {
        orbPanel.findViewById<View>(R.id.overlay_open_chat).setOnClickListener {
            openSpeechEntry()
        }
        orbPanel.findViewById<View>(R.id.overlay_change_character).setOnClickListener {
            collapseOrb()
            ChatEntry.openCharacterSettings(appContext)
        }

        val sizeSlider = orbPanel.findViewById<SeekBar>(R.id.overlay_size_slider)
        val sizeValue = orbPanel.findViewById<TextView>(R.id.overlay_size_value)
        sizeSlider.max = 80
        sizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (orbSyncing) return
                val scale = BluefinStore.progressToScale(progress)
                sizeValue.text = String.format(Locale.US, "%.2f", scale)
                BluefinStore.writeScale(prefs(), scale)
                pushPrefs()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        val opacitySlider = orbPanel.findViewById<SeekBar>(R.id.overlay_opacity_slider)
        val opacityValue = orbPanel.findViewById<TextView>(R.id.overlay_opacity_value)
        opacitySlider.max = 100
        opacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (orbSyncing) return
                val opacity = BluefinStore.progressToOpacity(progress)
                opacityValue.text = "${(opacity * 100).roundToInt()}%"
                BluefinStore.writeOpacity(prefs(), opacity)
                pushPrefs()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        val orbSizeSlider = orbPanel.findViewById<SeekBar>(R.id.overlay_orb_size_slider)
        val orbSizeValue = orbPanel.findViewById<TextView>(R.id.overlay_orb_size_value)
        orbSizeSlider.max = 28
        orbSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (orbSyncing) return
                val sizeDp = BluefinStore.progressToOrbSize(progress)
                orbSizeValue.text = "${sizeDp.roundToInt()}dp"
                BluefinStore.writeOrbSizeDp(prefs(), sizeDp)
                // Orb size only affects the orb button/window, never the pet.
                layoutOrb()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        orbPanel.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.overlay_roam_switch,
        ).setOnCheckedChangeListener { _, checked ->
            if (orbSyncing) return@setOnCheckedChangeListener
            if (checked && currentManifest?.canWalk == false) {
                Toast.makeText(appContext, R.string.character_cannot_walk, Toast.LENGTH_SHORT).show()
                refreshOrbControls()
                return@setOnCheckedChangeListener
            }
            prefs().edit().putBoolean(BluefinStore.KEY_ROAM, checked).apply()
            pushPrefs()
        }
        orbPanel.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.overlay_sound_switch,
        ).setOnCheckedChangeListener { _, checked ->
            if (orbSyncing) return@setOnCheckedChangeListener
            prefs().edit().putBoolean(BluefinStore.KEY_SOUND, checked).apply()
            pushPrefs()
        }
        orbPanel.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.overlay_click_through_switch,
        ).setOnCheckedChangeListener { _, checked ->
            if (orbSyncing) return@setOnCheckedChangeListener
            BluefinStore.writeClickThrough(prefs(), checked)
            applyClickThroughSetting()
        }
        orbPanel.findViewById<View>(R.id.overlay_hide_button).setOnClickListener {
            collapseOrb()
            handler.post { onHideRequested() }
        }
        orbPanel.findViewById<View>(R.id.overlay_return_button).setOnClickListener {
            collapseOrb()
            handler.post { onReturnToAppRequested() }
        }
        chatSettings.setOnClickListener { openModelSettingsFromOverlay() }
        chatCollapse.setOnClickListener { collapseOrb() }
        orbPanel.findViewById<View>(R.id.overlay_open_records).setOnClickListener {
            openChatPanel(requestInput = false)
        }
        orbPanel.findViewById<View>(R.id.overlay_unconfigured_cancel).setOnClickListener {
            orbUnconfigured.visibility = View.GONE
        }
        orbPanel.findViewById<View>(R.id.overlay_unconfigured_settings).setOnClickListener {
            openModelSettingsFromOverlay()
        }
    }

    private fun refreshOrbControls() {
        if (destroyed) return
        val scale = BluefinStore.readScale(prefs())
        val opacity = BluefinStore.readOpacity(prefs())
        val orbSizeDp = BluefinStore.readOrbSizeDp(prefs())
        orbSyncing = true
        try {
            val scaleSlider = orbPanel.findViewById<SeekBar>(R.id.overlay_size_slider)
            scaleSlider.max = 80
            scaleSlider.progress = BluefinStore.scaleToProgress(scale)
            orbPanel.findViewById<TextView>(R.id.overlay_size_value).text =
                String.format(Locale.US, "%.2f", scale)
            val opacitySlider = orbPanel.findViewById<SeekBar>(R.id.overlay_opacity_slider)
            opacitySlider.max = 100
            opacitySlider.progress = BluefinStore.opacityToProgress(opacity)
            orbPanel.findViewById<TextView>(R.id.overlay_opacity_value).text =
                "${(opacity * 100).roundToInt()}%"
            val orbSizeSlider = orbPanel.findViewById<SeekBar>(R.id.overlay_orb_size_slider)
            orbSizeSlider.max = 28
            orbSizeSlider.progress = BluefinStore.orbSizeToProgress(orbSizeDp)
            orbPanel.findViewById<TextView>(R.id.overlay_orb_size_value).text =
                "${orbSizeDp.roundToInt()}dp"
            orbPanel.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
                R.id.overlay_roam_switch,
            ).isChecked = prefs().getBoolean(BluefinStore.KEY_ROAM, true) &&
                (currentManifest?.canWalk != false)
            orbPanel.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
                R.id.overlay_sound_switch,
            ).isChecked = soundOn()
            orbPanel.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
                R.id.overlay_click_through_switch,
            ).isChecked = BluefinStore.readClickThrough(prefs())
            val name = currentManifest?.let {
                if (currentPackId == PackFormat.BUILT_IN_ID) {
                    PackAppearance.BUILT_IN_DISPLAY_NAME
                } else {
                    it.displayName()
                }
            } ?: if (currentPackId == PackFormat.BUILT_IN_ID) {
                PackAppearance.BUILT_IN_DISPLAY_NAME
            } else {
                currentPackId
            }
            orbPanel.findViewById<TextView>(R.id.overlay_character_name).text =
                uiContext.getString(R.string.orb_current_character, name)
            refreshActionButtons()
        } finally {
            orbSyncing = false
        }
    }

    private fun refreshActionButtons() {
        val grid = orbPanel.findViewById<GridLayout>(R.id.overlay_actions_grid)
        grid.removeAllViews()
        val appearanceBuiltIn = currentPackId == PackFormat.BUILT_IN_ID
        val words: List<Pair<String, String>> = if (appearanceBuiltIn) {
            listOf(
                "wave" to getString(R.string.action_wave),
                "walk" to getString(R.string.action_walk),
                "run" to getString(R.string.action_run),
                "dance" to getString(R.string.action_dance),
                "sleep" to getString(R.string.action_sleep),
            )
        } else {
            currentManifest?.vocab.orEmpty().map { it.id to it.label() }
        }
        if (words.isEmpty()) {
            grid.addView(TextView(themedContext).apply {
                text = getString(R.string.import_no_actions)
                setTextColor(android.graphics.Color.parseColor("#D9E8F8"))
                textSize = 12f
                setPadding(dp(4), dp(4), dp(4), dp(4))
            })
            return
        }
        val canWalk = currentManifest?.canWalk != false
        words.forEach { (word, label) ->
            val button = com.google.android.material.button.MaterialButton(
                themedContext,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minHeight = dp(36)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(2), 0, dp(2), 0)
                setOnClickListener {
                    if (word == "walk" && !canWalk) {
                        Toast.makeText(appContext, R.string.character_cannot_walk, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    lastInteract = SystemClock.uptimeMillis()
                    resting = false
                    collapseOrb()
                    web.evaluateJavascript(
                        "window.__bluefinOverlay&&window.__bluefinOverlay.act(${JSONObject.quote(word)})",
                        null,
                    )
                    Log.i(TAG, "orb action $word")
                }
            }
            button.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            grid.addView(button)
        }
    }

    private fun expandOrb() {
        dismissBubblePreview()
        hideOrbUnconfiguredPrompt()
        orbExpanded = true
        chatExpanded = false
        layoutOrb()
        orbPanel.animate().cancel()
        orbPanel.translationX = if (orbSideRight) dp(24).toFloat() else -dp(24).toFloat()
        orbPanel.alpha = 0f
        orbPanel.animate().translationX(0f).alpha(1f).setDuration(160).start()
        Log.i(TAG, "orb expanded")
    }

    private fun collapseOrb() {
        if (!orbExpanded && !chatExpanded) return
        orbExpanded = false
        chatExpanded = false
        detachChatListener()
        orbPanel.animate().cancel()
        chatPanel.animate().cancel()
        orbPanel.translationX = 0f
        orbPanel.alpha = 1f
        orbPanel.visibility = View.GONE
        chatPanel.translationX = 0f
        chatPanel.alpha = 1f
        chatPanel.visibility = View.GONE
        hideOrbUnconfiguredPrompt()
        releasePanelIme()
        setOrbFocusable(false)
        resetChatPanelAdaptation()
        layoutOrb()
        Log.i(TAG, "orb collapsed")
    }

    private fun openChatPanel(
        requestInput: Boolean = true,
        dismissBubble: Boolean = true,
        forceRestore: Boolean = false,
    ) {
        if (dismissBubble) dismissBubblePreview()
        if (requestInput && !ModelEndpointStore.isReady(appContext)) {
            showOrbUnconfiguredPrompt()
            return
        }
        installDraftWatcher()
        installPanelInsetsHandler()
        installBubbleInsetsCallbacks()
        val session = ChatSession.shared(appContext)
        chatSession = session
        attachChatListener(session)
        hideOrbUnconfiguredPrompt()
        orbExpanded = true
        chatExpanded = true
        resetChatPanelAdaptation()
        layoutOrb()
        refreshChatPanelBaseline()
        setOrbFocusable(true)
        renderChat(session.snapshot())
        bindLatestDraft()
        chatPanel.animate().cancel()
        chatPanel.translationX = if (orbSideRight) dp(24).toFloat() else -dp(24).toFloat()
        chatPanel.alpha = 0f
        chatPanel.animate().translationX(0f).alpha(1f).setDuration(160).start()
        if (requestInput) {
            chatInput.requestFocus()
            showPanelIme()
        } else {
            releasePanelIme()
        }
        if (forceRestore) updateSendState()
        handler.post { applyChatPanelInsets() }
        Log.i(TAG, "orb chat expanded input=$requestInput")
    }

    /**
     * The orb "对话" path. An eligible click creates the single speech input
     * balloon lazily after this user gesture; unconfigured, click-through,
     * switch-off and circuit paths keep the existing record panel.
     */
    private fun openSpeechEntry() {
        if (!ModelEndpointStore.isReady(appContext)) {
            showOrbUnconfiguredPrompt()
            return
        }
        if (clickThrough) {
            openChatPanel(requestInput = false)
            Toast.makeText(
                appContext,
                R.string.speech_click_through_fallback,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val circuit = try {
            BubblePreviewExplicitChatEntry.failureCircuitOrNull()
        } catch (error: Exception) {
            Log.w(TAG, "speech entry circuit contained type=${error.javaClass.name}")
            null
        }
        if (!BubblePreviewModule.isEnabled() ||
            !BluefinStore.readBubblePreview(prefs()) ||
            circuit == null || circuit.isTripped()
        ) {
            openChatPanel(requestInput = false)
            return
        }
        collapseOrb()
        scheduleSpeechInputAfterEntry()
    }

    /**
     * Same isolation pattern as scheduleBubblePreviewAfterStarted: post first,
     * then recheck attached/pageReady/pref/circuit/click-through before any
     * controller, window, EditText or keyboard exists.
     */
    private fun scheduleSpeechInputAfterEntry() {
        val token = ++bubbleRequestToken
        bubbleInputToken = token
        bubbleInputPending = true
        bubbleSignalToken = token
        bubbleAwaitingAttachToken = token
        handler.post {
            if (token != bubbleRequestToken || destroyed) return@post
            var attempt: BubblePreviewDisplayAttempt? = null
            try {
                if (!attached || !pageReady) {
                    fallbackSpeechToRecord(R.string.speech_unavailable_fallback)
                    return@post
                }
                if (OverlayAppVisibility.foreground) {
                    fallbackSpeechToRecord(null)
                    return@post
                }
                if (!BubblePreviewModule.isEnabled() ||
                    !BluefinStore.readBubblePreview(prefs())
                ) {
                    fallbackSpeechToRecord(null)
                    return@post
                }
                if (clickThrough || petHiddenForBubble || petOpacityForBubble <= 0f) {
                    fallbackSpeechToRecord(null)
                    return@post
                }
                val circuit = BubblePreviewExplicitChatEntry.failureCircuitOrNull()
                    ?: run {
                        fallbackSpeechToRecord(null)
                        return@post
                    }
                if (circuit.isTripped()) {
                    fallbackSpeechToRecord(null)
                    return@post
                }
                if (!Settings.canDrawOverlays(appContext)) {
                    fallbackSpeechToRecord(R.string.speech_unavailable_fallback)
                    return@post
                }
                attempt = circuit.beginDisplayAttempt(nowMillis())
                if (attempt == null) {
                    fallbackSpeechToRecord(null)
                    return@post
                }
                bubbleDisplayAttempt = attempt
                detachBubbleChatListener()
                val controller = try {
                    bubblePreview ?: BubblePreviewController(
                        context = uiContext,
                        windowManager = windowManager,
                        hostGeneration = ++bubbleHostGeneration,
                        circuit = circuit,
                        prefs = prefs(),
                        characterScale = OverlayPetPolicy.scaleFor(BluefinStore.readScale(prefs())),
                        characterName = currentCharacterName(),
                        actions = bubbleHostActions(),
                    ).also { bubblePreview = it }
                } catch (error: Exception) {
                    attempt.recordFailure(nowMillis())
                    bubbleDisplayAttempt = null
                    throw error
                }
                installBubbleInsetsCallbacks()
                installPanelInsetsHandler()
                installBubbleAppVisibility()
                controller.onHostVisibilityChanged(
                    petHiddenForBubble,
                    petOpacityForBubble,
                )
                val accepted = controller.showInput(
                    token = token,
                    draftText = ChatDraft.state.rebind().text,
                    acceptedRoleName = currentCharacterName(),
                    packColors = currentManifest?.bubble,
                    attemptToken = attempt,
                )
                if (!accepted) {
                    attempt.recordFailure(nowMillis())
                    bubbleDisplayAttempt = null
                    throw IllegalStateException("speech input not accepted")
                }
                scheduleSpeechInputDeadline(token)
            } catch (error: Exception) {
                Log.w(TAG, "speech input unavailable type=${error.javaClass.name}")
                attempt?.recordFailure(nowMillis())
                bubbleDisplayAttempt = null
                abortBubblePreviewRound()
                fallbackSpeechToRecord(R.string.speech_unavailable_fallback)
            }
        }
    }

    private fun scheduleSpeechInputDeadline(token: Long) {
        cancelSpeechInputDeadline()
        val runnable = Runnable {
            bubbleInputDeadline = null
            if (token != bubbleRequestToken || destroyed) return@Runnable
            if (bubbleInputToken == token && bubbleInputPending) {
                bubbleInputPending = false
                abortBubblePreviewRound()
                fallbackSpeechToRecord(R.string.speech_unavailable_fallback)
            }
        }
        bubbleInputDeadline = runnable
        handler.postDelayed(runnable, SPEECH_INPUT_DEADLINE_MS)
    }

    private fun cancelSpeechInputDeadline() {
        bubbleInputDeadline?.let { handler.removeCallbacks(it) }
        bubbleInputDeadline = null
    }

    private fun fallbackSpeechToRecord(message: Int?) {
        cancelSpeechInputDeadline()
        bubbleInputPending = false
        bubbleInputToken = 0L
        bubbleAwaitingAttachToken = null
        if (bubbleSignalToken != null && bubblePreview != null) {
            dismissBubblePreview()
        } else {
            bubbleSignalToken = null
        }
        bubbleDisplayAttempt = null
        if (destroyed || !attached) return
        if (message != null) {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
        try {
            openChatPanel(requestInput = false, dismissBubble = true)
        } catch (error: Exception) {
            Log.w(TAG, "speech fallback panel contained type=${error.javaClass.name}")
        }
    }

    /**
     * STARTED from the head-top input. The same accepted session/round is
     * transferred to the reply face; EMPTY keeps the input, BUSY never stops or
     * interrupts the current reply.
     */
    private fun sendSpeechInput(text: String) {
        if (destroyed || !attached) return
        val session = chatSession ?: ChatSession.shared(appContext).also { chatSession = it }
        if (text.isBlank()) {
            bubblePreview?.keepInput()
            return
        }
        ChatDraft.state.edit(text)
        val capture = ChatDraft.state.captureSend()
        val outcome = when (session.send(text)) {
            ChatSendResult.STARTED -> ChatDraftSendOutcome.STARTED
            ChatSendResult.EMPTY -> ChatDraftSendOutcome.EMPTY
            ChatSendResult.BUSY -> ChatDraftSendOutcome.BUSY
            ChatSendResult.NOT_CONFIGURED -> ChatDraftSendOutcome.NOT_CONFIGURED
        }
        val latest = ChatDraft.state.onSendOutcome(capture, outcome)
        when (outcome) {
            ChatDraftSendOutcome.STARTED -> {
                val snapshot = session.snapshot()
                val replyToken = ++bubbleRequestToken
                bubbleSignalToken = replyToken
                bubbleSignalGeneration = snapshot.requestGeneration
                bubbleSignalUserIndex = snapshot.lines.lastIndex
                bubbleInputToken = 0L
                bubbleInputPending = false
                bubbleAwaitingAttachToken = null
                cancelSpeechInputDeadline()
                val accepted = try {
                    bubblePreview?.beginReplyFromInput(
                        token = replyToken,
                        userSummary = bubbleSummary(text),
                        acceptedRoleName = currentCharacterName(),
                        acceptedRequestGeneration = snapshot.requestGeneration,
                        packColors = currentManifest?.bubble,
                    ) == true
                } catch (error: Exception) {
                    Log.w(TAG, "speech reply switch contained type=${error.javaClass.name}")
                    false
                }
                if (!accepted) {
                    fallbackSpeechToRecord(R.string.speech_unavailable_fallback)
                    return
                }
                attachBubbleChatListener(session)
                try {
                    deliverBubbleSignal(replyToken, bubbleSignalPayload(snapshot))
                } catch (error: Exception) {
                    Log.w(TAG, "speech reply catch-up contained type=${error.javaClass.name}")
                }
            }
            ChatDraftSendOutcome.EMPTY -> bubblePreview?.keepInput()
            ChatDraftSendOutcome.BUSY -> {
                Toast.makeText(
                    appContext,
                    R.string.speech_busy_fallback,
                    Toast.LENGTH_SHORT,
                ).show()
                bubblePreview?.keepInput()
            }
            ChatDraftSendOutcome.NOT_CONFIGURED -> {
                openChatPanel(requestInput = false)
            }
        }
    }

    private fun showOrbUnconfiguredPrompt() {
        orbUnconfigured.visibility = View.VISIBLE
        chatPanel.visibility = View.GONE
        orbPanel.visibility = View.VISIBLE
    }

    private fun hideOrbUnconfiguredPrompt() {
        orbUnconfigured.visibility = View.GONE
    }

    private fun releasePanelIme() {
        try {
            chatInput.clearFocus()
            val imm = appContext.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(chatInput.windowToken, 0)
        } catch (error: Exception) {
            Log.w(TAG, "panel ime release contained type=${error.javaClass.name}")
        }
    }

    private fun showPanelIme() {
        try {
            val imm = appContext.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT)
        } catch (error: Exception) {
            Log.w(TAG, "panel ime show contained type=${error.javaClass.name}")
        }
    }

    private fun attachChatListener(session: ChatSession) {
        if (chatListener != null) return
        val listener: (ChatSnapshot) -> Unit = { snapshot ->
            handler.post { if (!destroyed && chatExpanded) renderChat(snapshot) }
        }
        chatListener = listener
        session.addListener(listener)
    }

    private fun detachChatListener() {
        chatListener?.let { listener -> chatSession?.removeListener(listener) }
        chatListener = null
    }

    private fun sendFromOverlay() {
        installDraftWatcher()
        val session = chatSession ?: ChatSession.shared(appContext).also {
            chatSession = it
            attachChatListener(it)
        }
        val capture = ChatDraft.state.captureSend()
        val text = capture.text
        if (text.isBlank()) {
            updateSendState()
            return
        }
        val outcome = when (session.send(text)) {
            ChatSendResult.STARTED -> ChatDraftSendOutcome.STARTED
            ChatSendResult.EMPTY -> ChatDraftSendOutcome.EMPTY
            ChatSendResult.BUSY -> ChatDraftSendOutcome.BUSY
            ChatSendResult.NOT_CONFIGURED -> ChatDraftSendOutcome.NOT_CONFIGURED
        }
        val latest = ChatDraft.state.onSendOutcome(capture, outcome)
        applyDraftText(latest.text)
        when (outcome) {
            ChatDraftSendOutcome.STARTED -> {
                val snapshot = session.snapshot()
                scheduleBubblePreviewAfterStarted(
                    userSummary = bubbleSummary(text),
                    acceptedGeneration = snapshot.requestGeneration,
                    userIndex = snapshot.lines.lastIndex,
                )
            }
            ChatDraftSendOutcome.NOT_CONFIGURED -> {
                applyChatStatus(session.snapshot())
                updateSendState()
            }
            ChatDraftSendOutcome.BUSY -> {
                applyChatStatus(session.snapshot())
                updateSendState()
            }
            ChatDraftSendOutcome.EMPTY -> updateSendState()
        }
    }

    private fun openFullChat() {
        dismissBubblePreview()
        collapseOrb()
        ChatEntry.openRecords(appContext)
    }

    private fun bubbleSummary(text: String): String =
        text.trim().replace('\n', ' ').replace('\r', ' ').take(80)

    private fun openModelSettingsFromOverlay() {
        dismissBubblePreview()
        collapseOrb()
        ChatEntry.openModelSettings(appContext)
    }

    private fun setOrbFocusable(focusable: Boolean) {
        orbParams.flags = if (focusable) {
            OverlayLayout.WINDOW_FLAGS and OverlayLayout.FLAG_NOT_FOCUSABLE.inv()
        } else {
            OverlayLayout.WINDOW_FLAGS
        }
        orbParams.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            0
        }
        if (orbAttached) updateOrbWindow()
    }

    private fun updateChatHint() {
        val snapshot = chatSession?.snapshot()
        if (snapshot != null) {
            applyChatStatus(snapshot)
            return
        }
        val endpoint = ModelEndpointStore.load(appContext)
        val ready = EndpointAddressPolicy.isConfigured(
            endpoint.url,
            endpoint.model,
            ModelEndpointStore.hasSavedKey(appContext),
        )
        chatHint.text = if (ready) {
            "${getString(R.string.chat_configured)} · ${endpoint.model}"
        } else {
            getString(R.string.chat_unconfigured_orb)
        }
    }

    private fun applyChatStatus(snapshot: ChatSnapshot) {
        val busy = snapshot.workerBusy ||
            snapshot.phase == ChatPhase.THINKING || snapshot.phase == ChatPhase.STREAMING
        val text = when {
            !snapshot.configured -> getString(R.string.chat_unconfigured)
            busy -> getString(R.string.chat_busy_hint)
            snapshot.consecutiveFailures >= 5 -> getString(R.string.chat_failed_settings_hint)
            snapshot.phase == ChatPhase.FAILED && !snapshot.lastError.isNullOrBlank() ->
                "${getString(R.string.chat_failed)}：${snapshot.lastError}"
            else -> "${getString(R.string.chat_configured)} · ${snapshot.modelName}"
        }
        chatHint.text = text
        chatStatus.text = text
        val settingsNeeded = !snapshot.configured ||
            snapshot.consecutiveFailures >= 5 ||
            (snapshot.phase == ChatPhase.FAILED && !snapshot.lastError.isNullOrBlank())
        chatSettingsShouldShow = settingsNeeded
        chatSettings.visibility = if (settingsNeeded && !chatPanelCompact) View.VISIBLE else View.GONE
        updateSendState()
    }

    private fun installDraftWatcher() {
        if (bubbleDraftWatcherInstalled) return
        bubbleDraftWatcherInstalled = true
        chatInput.isSaveEnabled = false
        chatInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingDraft) return
                ChatDraft.state.edit(s?.toString().orEmpty())
                updateSendState()
            }
        })
    }

    private fun bindLatestDraft() {
        applyDraftText(ChatDraft.state.rebind().text)
    }

    private fun applyDraftText(text: String) {
        applyingDraft = true
        try {
            if (chatInput.text?.toString() != text) {
                chatInput.setText(text)
                chatInput.setSelection(text.length)
            }
        } finally {
            applyingDraft = false
        }
        updateSendState()
    }

    private fun updateSendState() {
        if (destroyed) return
        val snapshot = chatSession?.snapshot()
        val configured = snapshot?.configured ?: ModelEndpointStore.isReady(appContext)
        val busy = snapshot != null && (snapshot.workerBusy ||
            snapshot.phase == ChatPhase.THINKING || snapshot.phase == ChatPhase.STREAMING)
        val blank = chatInput.text?.toString().orEmpty().isBlank()
        chatSend.isEnabled = configured && !busy && !blank
        chatStop.visibility = if (busy) View.VISIBLE else View.GONE
        chatSend.visibility = if (busy) View.GONE else View.VISIBLE
    }

    private fun renderChat(snapshot: ChatSnapshot) {
        if (destroyed) return
        applyChatStatus(snapshot)
        val generating = snapshot.workerBusy ||
            snapshot.phase == ChatPhase.THINKING || snapshot.phase == ChatPhase.STREAMING
        chatSend.visibility = if (generating) View.GONE else View.VISIBLE
        chatStop.visibility = if (generating) View.VISIBLE else View.GONE
        chatTranscript.render(snapshot.lines, generating)
    }

    private fun loadOrbPosition() {
        orbSideRight = prefs().getString(BluefinStore.KEY_ORB_SIDE, "right") != "left"
        val storedY = prefs().getFloat(BluefinStore.KEY_ORB_Y_DP, Float.NaN)
        orbYDp = if (storedY.isFinite()) storedY else Float.NaN
    }

    private fun persistOrbPosition() {
        prefs().edit()
            .putString(BluefinStore.KEY_ORB_SIDE, if (orbSideRight) "right" else "left")
            .putFloat(BluefinStore.KEY_ORB_Y_DP, orbYDp)
            .apply()
    }

    private fun layoutOrb() {
        val d = density()
        val (sw, sh) = screenSize()
        val orbSizeDp = BluefinStore.readOrbSizeDp(prefs())
        val orbSize = (orbSizeDp * d).roundToInt().coerceAtLeast(1)
        val margin = dp(OverlayLayout.ORB_MARGIN_DP)
        val landscape = sw > sh
        val safeBottomDp = OverlayLayout.orbSafeBottomDp(sh, d, orbSizeDp)
        if (!orbYDp.isFinite()) orbYDp = OverlayLayout.defaultOrbYDp(sh, d)
        orbYDp = OverlayLayout.clampOrbYDp(orbYDp, safeBottomDp, orbSizeDp)
        val orbY = (orbYDp * d).roundToInt().coerceIn(0, (sh - orbSize).coerceAtLeast(0))
        val maxOrbX = (sw - orbSize - margin).coerceAtLeast(0)
        val orbX = if (orbSideRight) maxOrbX else margin.coerceIn(0, maxOrbX)
        val buttonParams = (orbButton.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(orbSize, orbSize)
        buttonParams.width = orbSize
        buttonParams.height = orbSize
        if (orbExpanded) {
            orbParams.width = sw
            orbParams.height = sh
            orbParams.x = 0
            orbParams.y = 0
            // In landscape the panel uses the short side minus small insets; it
            // must not inherit the portrait roam-band clearance or only actions
            // would fit. The panel is a scrollable side column beside the orb.
            val panelWidth = OverlayLayout.orbPanelWidthPx(sw, d, orbSizeDp)
            val panelHeight = OverlayLayout.orbPanelHeightPx(sh, d, landscape)
            val panelTop = OverlayLayout.orbPanelTopPx(sh, d, landscape, panelHeight, orbYDp)
            val panelX = if (orbSideRight) {
                (orbX - panelWidth).coerceAtLeast(0)
            } else {
                (orbX + orbSize).coerceAtMost((sw - panelWidth).coerceAtLeast(0))
            }
            val panel = if (chatExpanded) chatPanel else orbPanel
            panel.layoutParams = FrameLayout.LayoutParams(panelWidth, panelHeight).apply {
                leftMargin = panelX
                topMargin = panelTop
            }
            orbPanel.visibility = if (chatExpanded) View.GONE else View.VISIBLE
            chatPanel.visibility = if (chatExpanded) View.VISIBLE else View.GONE
            buttonParams.leftMargin = orbX
            buttonParams.topMargin = orbY
        } else {
            orbParams.width = orbSize
            orbParams.height = orbSize
            orbParams.x = orbX
            orbParams.y = orbY
            buttonParams.leftMargin = 0
            buttonParams.topMargin = 0
            orbPanel.visibility = View.GONE
            chatPanel.visibility = View.GONE
        }
        orbButton.layoutParams = buttonParams
        if (orbAttached) updateOrbWindow() else addOrb()
        if (attached) {
            handler.post { if (attached && !destroyed) updateChatHint() }
        }
    }

    private fun updateOrbWindow() {
        if (!orbAttached) return
        try {
            windowManager.updateViewLayout(orbRoot, orbParams)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "orb window update skipped", error)
            orbAttached = false
        }
    }

    private fun addOrb() {
        if (orbAttached || destroyed) return
        try {
            windowManager.addView(orbRoot, orbParams)
            orbAttached = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "orb window failed", error)
        }
    }

    private fun onLayout(json: String) {
        if (destroyed || !attached) return
        val parsed = try {
            JSONObject(json)
        } catch (error: RuntimeException) {
            Log.w(TAG, "bad layout", error)
            return
        }
        val mode = parsed.optString("mode")
        val facing = parsed.optInt("facing", 1)
        val x = parsed.optDouble("x").toFloat()
        val minX = parsed.optDouble("minX").toFloat()
        val maxX = parsed.optDouble("maxX").toFloat()
        if (mode != lastMode) {
            Log.i(TAG, "mode $lastMode -> $mode x=$x facing=$facing")
            if (resting && (mode == "idle" || mode == "walk" || mode == "run")) resting = false
            lastMode = mode
        }
        // Empty hits (opacity 0) drop the touch window even mid-gesture; while
        // dragging, non-empty geometry stays under moveHitWithFinger's control.
        if (!tracking || (parsed.optJSONArray("hits")?.length() ?: 0) == 0) placeHit(parsed)
        val now = SystemClock.uptimeMillis()
        val target = OverlayPetPolicy.edgeTarget(x, facing, minX, maxX, mode)
        if (target != null && !resting && now - lastTurnAt > 450) {
            lastTurnAt = now
            val run = mode == "run"
            Log.i(TAG, "edge turn x=$x -> $target")
            web.evaluateJavascript(
                "window.__bluefinOverlay&&window.__bluefinOverlay.walkTo($target, $run)",
                null,
            )
        }
        if (OverlayPetPolicy.shouldStartSleep(now - lastInteract, mode, resting)) {
            resting = true
            Log.i(TAG, "idle rest")
            web.evaluateJavascript("window.__bluefinOverlay&&window.__bluefinOverlay.rest()", null)
        }
        // Inactive/suppressed rounds must not read metrics, capture a screen
        // snapshot or render. Only a round with an active token or a bounded
        // first-attach intent receives the 80 ms layout stream.
        publishBubbleHostTail(parsed, now)
    }

    /**
     * The new optional host tail gets its own Exception boundary. A preference,
     * module or resource failure here must never escape after the baseline pet
     * edge/sleep/hit logic and crash the host, while the baseline code itself is
     * not wrapped or silenced.
     */
    private fun publishBubbleHostTail(parsed: JSONObject, now: Long) {
        try {
            val bubble = bubblePreview ?: return
            if (bubbleSignalToken == null && bubbleFirstAttach == null) return
            if (!BubblePreviewModule.isEnabled() || !BluefinStore.readBubblePreview(prefs())) {
                dismissBubblePreview()
                return
            }
            if (clickThrough || petHiddenForBubble || petOpacityForBubble <= 0f) {
                bubble.onHostVisibilityChanged(petHiddenForBubble, petOpacityForBubble)
                return
            }
            publishBubbleGeometry(bubble, parsed, now)
        } catch (error: Exception) {
            Log.w(TAG, "optional bubble host tail contained type=${error.javaClass.name}")
            abortBubblePreviewRound()
        }
    }

    private fun placeHit(parsed: JSONObject) {
        if (OverlayAppVisibility.chatVisible) return
        val hits = parsed.optJSONArray("hits")
        val hasHits = hits != null && hits.length() > 0
        if (!OverlayClickThroughPolicy.shouldAttachHit(clickThrough, hasHits)) {
            // Click-through on: dropping the pet window lets taps, moves and
            // scrolls reach the app underneath. Opacity 0 also publishes no
            // hits, so the same drop avoids a stale target over empty air.
            dropHitWindow()
            return
        }
        val hitList = hits ?: return
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until hitList.length()) {
            val hit = hitList.getJSONObject(i)
            val hx = hit.optDouble("x").toFloat()
            val hy = hit.optDouble("y").toFloat()
            val r = hit.optDouble("r").toFloat()
            minX = minOf(minX, hx - r)
            minY = minOf(minY, hy - r)
            maxX = maxOf(maxX, hx + r)
            maxY = maxOf(maxY, hy + r)
        }
        val d = density()
        val pad = 12f * d
        val loc = IntArray(2)
        web.getLocationOnScreen(loc)
        val left = (loc[0] + minX * d - pad).roundToInt()
        val top = (loc[1] + minY * d - pad).roundToInt()
        val right = (loc[0] + maxX * d + pad).roundToInt()
        val bottom = (loc[1] + maxY * d + pad).roundToInt()
        val (sw, sh) = screenSize()
        val width = (right - left).coerceAtLeast(dp(48))
        val height = (bottom - top).coerceAtLeast(dp(48))
        val (x, y) = OverlayLayout.clamp(left, top, width, height, sw, sh)
        hitParams.x = x
        hitParams.y = y
        bubbleHitBaseLeft = x
        bubbleHitBaseTop = y
        hitParams.width = width.coerceAtMost(sw)
        hitParams.height = height.coerceAtMost(sh)
        if (!hitAttached) {
            try {
                windowManager.addView(hit, hitParams)
                hitAttached = true
                bringOrbToFront()
            } catch (error: RuntimeException) {
                Log.w(TAG, "hit window failed", error)
            }
        } else {
            try {
                windowManager.updateViewLayout(hit, hitParams)
            } catch (error: IllegalArgumentException) {
                hitAttached = false
            }
        }
    }

    private fun dropHitWindow() {
        tracking = false
        if (!hitAttached) return
        try {
            windowManager.removeViewImmediate(hit)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "hit window already gone", error)
        } finally {
            hitAttached = false
        }
    }

    private fun updateChatVisibility(chatVisible: Boolean) {
        if (destroyed) return
        if (chatVisible) {
            collapseOrb()
            dropHitWindow()
        }
        val visibility = if (chatVisible) View.INVISIBLE else View.VISIBLE
        root.visibility = visibility
        orbRoot.visibility = visibility
    }

    private fun moveHitWithFinger(rawX: Float, rawY: Float) {
        if (!hitAttached) return
        val (sw, sh) = screenSize()
        val nx = downWinX + (rawX - downRawX).roundToInt()
        val ny = downWinY + (rawY - downRawY).roundToInt()
        val (x, y) = OverlayLayout.clamp(nx, ny, hitParams.width, hitParams.height, sw, sh)
        hitParams.x = x
        hitParams.y = y
        try {
            windowManager.updateViewLayout(hit, hitParams)
        } catch (error: IllegalArgumentException) {
            hitAttached = false
        }
    }

    private fun sendPointer(type: String, x: Float, y: Float) {
        if (!pageReady) return
        web.evaluateJavascript(
            "window.__bluefinOverlay&&window.__bluefinOverlay.pointer(${JSONObject.quote(type)},$x,$y)",
            null,
        )
    }

    private fun bringOrbToFront() {
        if (!orbAttached) return
        try {
            windowManager.removeViewImmediate(orbRoot)
            windowManager.addView(orbRoot, orbParams)
        } catch (error: RuntimeException) {
            Log.w(TAG, "could not raise orb", error)
            orbAttached = false
        }
    }

    private fun stageAlphaForCurrentDevice(): Float {
        val maximum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(InputManager::class.java)
                ?.getMaximumObscuringOpacityForTouch()
                ?: OverlayLayout.MAX_OBSCURING_OPACITY_FOR_TOUCH
        } else {
            OverlayLayout.MAX_OBSCURING_OPACITY_FOR_TOUCH
        }
        return OverlayLayout.stageAlpha(Build.VERSION.SDK_INT, maximum)
    }

    private fun fullscreenParams(
        touchable: Boolean,
        title: String,
        alpha: Float = 1f,
    ): WindowManager.LayoutParams {
        val flags = if (touchable) {
            OverlayLayout.WINDOW_FLAGS
        } else {
            OverlayLayout.WINDOW_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OverlayLayout.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.alpha = alpha
            this.title = title
            @Suppress("DEPRECATION")
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * Explicit overlay STARTED entry. Only the first finite steps run here:
     * create the controller, install lazy insets handlers, preflight against the
     * projected collapsed orb button, then collapse the full-screen record
     * panel before any window is added. A later trustworthy geometry frame
     * rechecks the actual collapsed orb bounds and only then calls showStarted.
     */
    private fun scheduleBubblePreviewAfterStarted(
        userSummary: String,
        acceptedGeneration: Int,
        userIndex: Int,
    ) {
        val requestToken = ++bubbleRequestToken
        bubblePendingSummary = userSummary
        bubblePendingGeneration = acceptedGeneration
        bubblePendingUserIndex = userIndex
        handler.post {
            if (requestToken != bubbleRequestToken) return@post
            try {
                if (destroyed || !attached || !pageReady) return@post
                if (OverlayAppVisibility.foreground) return@post
                if (!BubblePreviewModule.isEnabled()) return@post
                if (!BluefinStore.readBubblePreview(prefs())) return@post
                val circuit = BubblePreviewExplicitChatEntry.failureCircuitOrNull() ?: return@post
                if (circuit.isTripped()) return@post
                if (clickThrough || petHiddenForBubble || petOpacityForBubble <= 0f) return@post
                if (!Settings.canDrawOverlays(appContext)) return@post
                // One explicit display attempt covers preflight metrics,
                // controller construction/inflate/measure, attach and later
                // update failures. beginDisplayAttempt settles a previous
                // still-visible attempt; a duplicate report on this token is
                // collapsed to one count.
                val attempt = circuit.beginDisplayAttempt(nowMillis()) ?: return@post
                bubbleDisplayAttempt = attempt
                detachBubbleChatListener()
                val controller = try {
                    bubblePreview ?: BubblePreviewController(
                        context = uiContext,
                        windowManager = windowManager,
                        hostGeneration = ++bubbleHostGeneration,
                        circuit = circuit,
                        prefs = prefs(),
                        characterScale = OverlayPetPolicy.scaleFor(BluefinStore.readScale(prefs())),
                        characterName = currentCharacterName(),
                        actions = bubbleHostActions(),
                    ).also { bubblePreview = it }
                } catch (error: Exception) {
                    // Constructor/metrics failures happen before showStarted can
                    // open its attempt token; own that failure here exactly once.
                    attempt.recordFailure(nowMillis())
                    bubbleDisplayAttempt = null
                    throw error
                }
                // A new explicit STARTED replaces the previous desktop round:
                // drop the old listener identity and remove its window before
                // binding the new token.
                if (bubbleSignalToken != null || bubbleAwaitingAttachToken != null) {
                    detachBubbleChatListener()
                    bubblePreview?.dismissForHostUi()
                }
                bubbleSignalToken = requestToken
                bubbleSignalGeneration = acceptedGeneration
                bubbleSignalUserIndex = userIndex
                installBubbleInsetsCallbacks()
                installPanelInsetsHandler()
                installBubbleAppVisibility()
                bubbleAwaitingAttachToken = null
                val projected = projectedOrbBounds()
                val flow = BubblePreviewFirstAttachFlow()
                bubbleFirstAttach = flow
                bubbleFirstAttachToken = requestToken
                bubbleGeometryFrameSeen = false
                when (val decision = flow.begin(nowMillis(), projected != null)) {
                    BubblePreviewAttachDecision.PRECHECK_FAILED ->
                        finishBubbleFirstAttach(requestToken, restore = true)
                    BubblePreviewAttachDecision.AWAIT_COLLAPSE -> {
                        collapseOrbForBubbleAttach()
                        val next = flow.onPanelCollapsed(nowMillis())
                        if (next == BubblePreviewAttachDecision.AWAIT_GEOMETRY) {
                            scheduleFirstAttachDeadline(requestToken)
                        } else {
                            finishBubbleFirstAttach(requestToken, restore = true)
                        }
                    }
                    else -> Unit
                }
                // The controller is not given the token yet: no add can happen
                // while the full-screen record panel is still expanded.
                controller.onHostVisibilityChanged(
                    petHiddenForBubble,
                    petOpacityForBubble,
                )
            } catch (error: Exception) {
                Log.w(TAG, "optional bubble unavailable type=${error.javaClass.name}")
                abortBubblePreviewRound()
            }
        }
    }

    private fun bubbleHostActions(): BubblePreviewHostActions =
        object : BubblePreviewHostActions {
            override fun onBubbleAttached() {
                postBubbleCallback(
                    "attached",
                    bubbleAwaitingAttachToken ?: bubbleSignalToken,
                ) {
                    cancelBubbleFirstAttach()
                    bubbleAwaitingAttachToken = null
                    bubbleInputPending = false
                    cancelSpeechInputDeadline()
                    if (orbExpanded || chatExpanded) collapseOrb()
                    bubblePreview?.focusInput()
                }
            }

            override fun onBubbleRecordRequested() {
                postBubbleCallback("record", bubbleSignalToken) {
                    if (!destroyed && attached) openChatPanel(requestInput = false)
                }
            }

            override fun onBubbleAttemptFailed() {
                postBubbleCallback(
                    "attempt-failed",
                    bubbleAwaitingAttachToken ?: bubbleSignalToken,
                ) {
                    val awaiting = bubbleAwaitingAttachToken
                    bubbleAwaitingAttachToken = null
                    if (bubblePreview?.isInputMode() == true) {
                        bubbleInputPending = false
                        cancelSpeechInputDeadline()
                        fallbackSpeechToRecord(R.string.speech_unavailable_fallback)
                    } else if (awaiting != null && awaiting == bubbleRequestToken) {
                        finishBubbleFirstAttach(awaiting, restore = true)
                    } else {
                        detachBubbleChatListener()
                    }
                }
            }

            override fun onBubbleStopRequested() {
                postBubbleCallback("stop", bubbleSignalToken) {
                    val session = chatSession ?: ChatSession.peek() ?: return@postBubbleCallback
                    session.stop()
                }
            }

            override fun onBubbleNoSpace() {
                postBubbleCallback(
                    "no-space",
                    bubbleAwaitingAttachToken ?: bubbleSignalToken,
                ) {
                    val awaiting = bubbleAwaitingAttachToken
                    bubbleAwaitingAttachToken = null
                    if (bubblePreview?.isInputMode() == true) {
                        bubbleInputPending = false
                        cancelSpeechInputDeadline()
                        fallbackSpeechToRecord(R.string.speech_unavailable_fallback)
                    } else if (awaiting != null && awaiting == bubbleRequestToken) {
                        finishBubbleFirstAttach(awaiting, restore = true)
                    }
                }
            }

            override fun onBubbleRoundFinished(
                token: Long,
                reason: com.wwkhub.bluefin.bubblepreview.BubbleSuppressionReason,
            ) {
                postBubbleCallback("finished", token) {
                    if (bubbleSignalToken == token) bubbleSignalToken = null
                    if (bubbleFirstAttachToken == token) cancelBubbleFirstAttach()
                    if (bubbleAwaitingAttachToken == token) bubbleAwaitingAttachToken = null
                    if (bubbleInputToken == token) {
                        bubbleInputToken = 0L
                        bubbleInputPending = false
                        cancelSpeechInputDeadline()
                    }
                    detachBubbleChatListener()
                }
            }

            override fun onBubbleInputSendRequested(text: String) {
                postBubbleCallback(
                    "input-send",
                    bubbleInputToken.takeIf { it != 0L } ?: bubbleSignalToken,
                ) {
                    sendSpeechInput(text)
                }
            }

            override fun onBubbleInputExpandRequested(text: String) {
                postBubbleCallback(
                    "input-expand",
                    bubbleInputToken.takeIf { it != 0L } ?: bubbleSignalToken,
                ) {
                    ChatDraft.state.edit(text)
                    bubbleInputPending = false
                    cancelSpeechInputDeadline()
                    dismissBubblePreview()
                    if (!destroyed && attached) {
                        openChatPanel(requestInput = false, dismissBubble = false)
                    }
                }
            }

            override fun onBubbleInputDraftChanged(text: String) {
                ChatDraft.state.edit(text)
            }

            override fun onBubbleInputNoSpace() {
                postBubbleCallback(
                    "input-no-space",
                    bubbleInputToken.takeIf { it != 0L } ?: bubbleSignalToken,
                ) {
                    bubbleInputPending = false
                    cancelSpeechInputDeadline()
                    fallbackSpeechToRecord(R.string.speech_unavailable_fallback)
                }
            }
        }

    /**
     * Boundary for a nullable optional-controller callback reached from a
     * baseline path (relayout/sync/push/page reset). Only the callback is
     * wrapped: host layout, preferences and unrelated exceptions above/below
     * this call keep their original behaviour.
     */
    private fun runBubbleHostCallback(name: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.w(TAG, "bubble host callback $name contained type=${error.javaClass.name}")
            abortBubblePreviewRound()
        }
    }

    private fun postBubbleCallback(name: String, roundToken: Long?, block: () -> Unit) {
        try {
            handler.post {
                try {
                    if (destroyed) return@post
                    // Every deferred host action belongs to one round. A late
                    // post from an older round must not stop, detach or dismiss
                    // the round that replaced it.
                    val current = bubbleSignalToken ?: bubbleAwaitingAttachToken
                    if (roundToken == null || current != roundToken) return@post
                    block()
                } catch (error: Exception) {
                    Log.w(TAG, "bubble callback $name contained type=${error.javaClass.name}")
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "bubble callback $name post contained type=${error.javaClass.name}")
        }
    }

    private fun collapseOrbForBubbleAttach() {
        try {
            if (orbExpanded || chatExpanded) {
                collapseOrb()
            } else {
                hideOrbUnconfiguredPrompt()
                releasePanelIme()
                setOrbFocusable(false)
                layoutOrb()
            }
        } catch (error: Exception) {
            Log.w(TAG, "bubble collapse contained type=${error.javaClass.name}")
        }
    }

    private fun scheduleFirstAttachDeadline(token: Long) {
        cancelFirstAttachDeadline()
        val runnable = Runnable {
            bubbleFirstAttachDeadline = null
            if (token != bubbleRequestToken || destroyed) return@Runnable
            val flow = bubbleFirstAttach ?: return@Runnable
            try {
                val decision = flow.onDeadline(nowMillis())
                if (decision != BubblePreviewAttachDecision.AWAIT_GEOMETRY) {
                    finishBubbleFirstAttach(token, restore = true)
                }
            } catch (error: Exception) {
                Log.w(TAG, "first attach deadline contained type=${error.javaClass.name}")
                finishBubbleFirstAttach(token, restore = true)
            }
        }
        bubbleFirstAttachDeadline = runnable
        handler.postDelayed(runnable, FIRST_ATTACH_DEADLINE_MS)
    }

    private fun cancelFirstAttachDeadline() {
        bubbleFirstAttachDeadline?.let { handler.removeCallbacks(it) }
        bubbleFirstAttachDeadline = null
    }

    private fun cancelBubbleFirstAttach() {
        bubbleFirstAttach = null
        cancelFirstAttachDeadline()
    }

    /**
     * Restores the record panel without a keyboard under the same user gesture.
     * A canceled generation (×, panel, preference off, click-through, app
     * foreground) never reaches this path because the token no longer matches.
     */
    private fun finishBubbleFirstAttach(token: Long, restore: Boolean) {
        if (token != bubbleRequestToken) return
        bubbleFirstAttach = null
        cancelFirstAttachDeadline()
        if (!restore) return
        bubbleSignalToken = null
        detachBubbleChatListener()
        try {
            bubblePreview?.dismissForHostUi()
        } catch (error: Exception) {
            Log.w(TAG, "bubble first-attach dismiss contained type=${error.javaClass.name}")
        }
        bubbleDisplayAttempt = null
        if (!bubbleRestoreAllowed()) return
        handler.post {
            if (token != bubbleRequestToken || destroyed) return@post
            if (OverlayAppVisibility.foreground) return@post
            try {
                openChatPanel(requestInput = false, dismissBubble = false, forceRestore = true)
                releasePanelIme()
            } catch (error: Exception) {
                Log.w(TAG, "bubble restore contained type=${error.javaClass.name}")
            }
        }
    }

    private fun bubbleRestoreAllowed(): Boolean =
        !destroyed && attached && pageReady &&
            !OverlayAppVisibility.foreground &&
            BubblePreviewModule.isEnabled() &&
            BluefinStore.readBubblePreview(prefs()) &&
            !clickThrough && !petHiddenForBubble && petOpacityForBubble > 0f &&
            Settings.canDrawOverlays(appContext)

    private fun advanceBubbleFirstAttach(frame: BubblePreviewHostFrame) {
        val flow = bubbleFirstAttach ?: return
        val token = bubbleFirstAttachToken
        if (token != bubbleRequestToken) {
            cancelBubbleFirstAttach()
            return
        }
        if (!bubbleRestoreAllowed()) {
            cancelBubbleFirstAttach()
            bubbleSignalToken = null
            detachBubbleChatListener()
            try {
                bubblePreview?.dismissForHostUi()
            } catch (error: Exception) {
                Log.w(TAG, "bubble ineligible dismiss contained type=${error.javaClass.name}")
            }
            return
        }
        bubbleGeometryFrameSeen = true
        val geometry = frame.geometry
        val trustworthy = frame.stageAttached && frame.pageReady &&
            !frame.clickThrough && !frame.hidden && frame.opacity > 0f &&
            !orbExpanded && !chatExpanded &&
            bubbleImeGate?.isTrustworthy() == true &&
            geometry != null && geometry.isUsable() &&
            geometry.hasCharacterAnchor() && geometry.orbWindowBounds != null
        try {
            when (val decision = flow.onGeometry(nowMillis(), trustworthy)) {
                BubblePreviewAttachDecision.READY -> {
                    bubbleFirstAttach = null
                    cancelFirstAttachDeadline()
                    startAcceptedBubbleRound(token)
                }
                BubblePreviewAttachDecision.TIMED_OUT,
                BubblePreviewAttachDecision.PRECHECK_FAILED,
                -> finishBubbleFirstAttach(token, restore = true)
                else -> Unit
            }
        } catch (error: Exception) {
            Log.w(TAG, "bubble first-attach geometry contained type=${error.javaClass.name}")
            finishBubbleFirstAttach(token, restore = true)
        }
    }

    private fun startAcceptedBubbleRound(token: Long) {
        if (OverlayAppVisibility.foreground) {
            bubbleAwaitingAttachToken = null
            finishBubbleFirstAttach(token, restore = true)
            return
        }
        val controller = bubblePreview ?: run {
            finishBubbleFirstAttach(token, restore = true)
            return
        }
        bubbleAwaitingAttachToken = token
        val shown = try {
            controller.showStarted(
                token = token,
                userSummary = bubblePendingSummary,
                acceptedRoleName = currentCharacterName(),
                acceptedRequestGeneration = bubblePendingGeneration,
                packColors = currentManifest?.bubble,
                attemptToken = bubbleDisplayAttempt,
            )
        } catch (error: Exception) {
            Log.w(TAG, "bubble showStarted contained type=${error.javaClass.name}")
            false
        }
        if (!shown) {
            bubbleAwaitingAttachToken = null
            finishBubbleFirstAttach(token, restore = true)
            return
        }
        val session = chatSession
        if (session != null) {
            attachBubbleChatListener(session)
            // The round may have streamed while the first-attach flow waited for
            // geometry. Deliver one current snapshot now so the window catches
            // up without waiting for the next streaming delta.
            try {
                deliverBubbleSignal(token, bubbleSignalPayload(session.snapshot()))
            } catch (error: Exception) {
                Log.w(TAG, "bubble catch-up snapshot contained type=${error.javaClass.name}")
            }
        }
    }

    private fun attachBubbleChatListener(session: ChatSession) {
        if (bubbleChatListener != null) return
        val token = bubbleSignalToken ?: return
        val listener: (ChatSnapshot) -> Unit = { snapshot ->
            // ChatSession notifies on the main looper and addListener invokes
            // synchronously. Consume the controller immediately; never queue a
            // ChatSnapshot or a copied 32K response across a post boundary.
            try {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    deliverBubbleSignal(token, bubbleSignalPayload(snapshot))
                } else {
                    // Defensive non-main entry: post only the token, then read
                    // the CURRENT snapshot on main. No history/captured text is
                    // retained by the pending post.
                    handler.post {
                        if (destroyed) return@post
                        try {
                            deliverBubbleSignal(token, bubbleSignalPayload(session.snapshot()))
                        } catch (error: Exception) {
                            Log.w(TAG, "bubble signal contained type=${error.javaClass.name}")
                        }
                    }
                }
            } catch (error: Exception) {
                // A synchronous optional render must not escape this listener
                // and block other ChatSession listeners.
                Log.w(TAG, "bubble signal contained type=${error.javaClass.name}")
            }
        }
        bubbleChatListener = listener
        try {
            session.addListener(listener)
        } catch (error: Exception) {
            bubbleChatListener = null
            Log.w(TAG, "bubble listener add contained type=${error.javaClass.name}")
        }
    }

    private fun bubbleSignalPayload(snapshot: ChatSnapshot): BubbleSignalPayload {
        val userIndex = bubbleSignalUserIndex
        val indexValid = userIndex >= 0 && userIndex < snapshot.lines.size + 1
        val assistant = if (indexValid) {
            snapshot.lines
                .drop((userIndex + 1).coerceAtLeast(0))
                .lastOrNull { line -> line.role == ChatRole.ASSISTANT }
                ?.text
                .orEmpty()
        } else {
            ""
        }
        val failed = snapshot.phase == ChatPhase.FAILED
        return BubbleSignalPayload(
            requestGeneration = snapshot.requestGeneration,
            cumulativeAssistant = assistant,
            terminal = snapshot.phase == ChatPhase.IDLE && !snapshot.workerBusy,
            stopped = snapshot.phase == ChatPhase.STOPPED,
            failed = failed,
            safeFailureText = if (failed) {
                ChatFailureText.forBubble(snapshot.lastError)
            } else {
                null
            },
            indexValid = indexValid,
        )
    }

    private fun deliverBubbleSignal(token: Long, signal: BubbleSignalPayload) {
        val controller = bubblePreview ?: return
        if (token != bubbleSignalToken || token != bubbleRequestToken) return
        if (signal.requestGeneration != bubbleSignalGeneration) {
            // Clear history, a new in-app send or a same-index same-text replay
            // moved the existing generation: the old desktop token must die
            // before any user-index decision can swallow the invalidation.
            controller.onChatSignal(
                BubblePreviewChatSignal(
                    token = token,
                    requestGeneration = signal.requestGeneration,
                    cumulativeAssistant = "",
                    terminal = false,
                    stopped = false,
                    failed = false,
                    safeFailureText = null,
                ),
            )
            return
        }
        if (!signal.indexValid) return
        controller.onChatSignal(
            BubblePreviewChatSignal(
                token = token,
                requestGeneration = signal.requestGeneration,
                cumulativeAssistant = signal.cumulativeAssistant,
                terminal = signal.terminal,
                stopped = signal.stopped,
                failed = signal.failed,
                safeFailureText = signal.safeFailureText,
            ),
        )
    }

    private fun detachBubbleChatListener() {
        val listener = bubbleChatListener ?: return
        bubbleChatListener = null
        val session = chatSession ?: ChatSession.peek() ?: return
        try {
            session.removeListener(listener)
        } catch (error: Exception) {
            Log.w(TAG, "bubble listener remove contained type=${error.javaClass.name}")
        }
    }

    private fun dismissBubblePreview() {
        bubbleRequestToken += 1
        cancelBubbleFirstAttach()
        cancelSpeechInputDeadline()
        bubbleInputToken = 0L
        bubbleInputPending = false
        bubbleSignalToken = null
        bubbleAwaitingAttachToken = null
        detachBubbleChatListener()
        try {
            bubblePreview?.dismissForHostUi()
        } catch (error: Exception) {
            Log.w(TAG, "optional bubble dismiss contained type=${error.javaClass.name}")
        }
        bubbleDisplayAttempt = null
    }

    /**
     * Any host-side failure after an explicit round started removes the live
     * window and all pending work; logging alone would leave a stale bubble.
     */
    private fun abortBubblePreviewRound() {
        cancelBubbleFirstAttach()
        cancelSpeechInputDeadline()
        bubbleInputPending = false
        bubbleInputToken = 0L
        bubbleSignalToken = null
        bubbleAwaitingAttachToken = null
        detachBubbleChatListener()
        try {
            bubblePreview?.dismissForHostUi()
        } catch (error: Exception) {
            Log.w(TAG, "optional bubble abort contained type=${error.javaClass.name}")
        }
        bubbleDisplayAttempt = null
    }

    private fun installBubbleAppVisibility() {
        if (bubbleAppVisibilityInstalled) return
        val listener = bubbleAppVisibilityListener ?: OverlayAppVisibility.Listener { foreground ->
            if (!foreground) return@Listener
            try {
                handler.post {
                    if (destroyed) return@post
                    try {
                        cancelBubbleFirstAttach()
                        dismissBubblePreview()
                    } catch (error: Exception) {
                        Log.w(TAG, "bubble foreground body contained type=${error.javaClass.name}")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "bubble foreground post contained type=${error.javaClass.name}")
            }
        }.also { bubbleAppVisibilityListener = it }
        bubbleAppVisibilityInstalled = true
        try {
            OverlayAppVisibility.addListener(listener)
        } catch (error: Exception) {
            bubbleAppVisibilityInstalled = false
            Log.w(TAG, "bubble visibility listener contained type=${error.javaClass.name}")
        }
    }

    private fun removeBubbleHostListeners() {
        if (bubbleAppVisibilityInstalled) {
            bubbleAppVisibilityInstalled = false
            val listener = bubbleAppVisibilityListener
            bubbleAppVisibilityListener = null
            if (listener != null) {
                try {
                    OverlayAppVisibility.removeListener(listener)
                } catch (error: Exception) {
                    Log.w(TAG, "bubble visibility remove contained type=${error.javaClass.name}")
                }
            }
        }
        try {
            ViewCompat.setOnApplyWindowInsetsListener(orbRoot, null)
        } catch (error: Exception) {
            Log.w(TAG, "bubble insets remove contained type=${error.javaClass.name}")
        }
        panelInsetsListener?.let { listener ->
            try {
                orbRoot.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            } catch (error: Exception) {
                Log.w(TAG, "panel insets remove contained type=${error.javaClass.name}")
            }
        }
        panelInsetsListener = null
        panelInsetsInstalled = false
    }

    /**
     * Lazy insets installation. It is called only from a user chat action, never
     * from show()/layoutOrb/constructor. The IME gate stays UNKNOWN until one
     * real callback arrives, so neither the first attach nor the panel geometry
     * assumes a zero-height keyboard.
     */
    private fun installBubbleInsetsCallbacks() {
        if (bubbleInsetsInstalled) return
        bubbleInsetsInstalled = true
        val gate = bubbleImeGate ?: BubblePreviewImeGate().also { bubbleImeGate = it }
        try {
            ViewCompat.setOnApplyWindowInsetsListener(orbRoot) { _, insets ->
                val stateBefore = gate.state
                val knownBefore = gate.isTrustworthy()
                val bottomBefore = gate.bottomPx
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
                gate.onInsets(imeBottom, visible)
                if (!knownBefore || stateBefore != gate.state || bottomBefore != gate.bottomPx) {
                    // Drop stale keyboard bounds. The focused input window handles
                    // its own IME insets without losing the editor or its focus.
                    invalidateBubbleScreenSnapshot()
                    scheduleBubbleInsetsEpoch()
                }
                insets
            }
            orbRoot.requestApplyInsets()
        } catch (error: Exception) {
            bubbleInsetsInstalled = false
            Log.w(TAG, "bubble insets install contained type=${error.javaClass.name}")
        }
    }

    private fun scheduleBubbleInsetsEpoch() {
        if (bubbleInsetsEpoch != null) return
        val runnable = Runnable {
            bubbleInsetsEpoch = null
            if (destroyed) return@Runnable
            bubbleGeometryGeneration += 1
            invalidateBubbleScreenSnapshot()
            // The display/bar basis is config-scoped; an IME epoch does not
            // change it, but the screen snapshot (IME edge) is already invalid.
            bubblePreview?.let { controller ->
                runBubbleHostCallback("insets-epoch") { controller.onHostInsetsChanged() }
            }
        }
        bubbleInsetsEpoch = runnable
        handler.postDelayed(runnable, INSETS_EPOCH_COALESCE_MS)
    }

    private fun cancelBubbleInsetsEpoch() {
        bubbleInsetsEpoch?.let { handler.removeCallbacks(it) }
        bubbleInsetsEpoch = null
    }

    private fun installPanelInsetsHandler() {
        if (panelInsetsInstalled) return
        panelInsetsInstalled = true
        try {
            val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                if (!destroyed && chatExpanded) handler.post { applyChatPanelInsets() }
            }
            panelInsetsListener = listener
            orbRoot.viewTreeObserver.addOnGlobalLayoutListener(listener)
        } catch (error: Exception) {
            panelInsetsInstalled = false
            panelInsetsListener = null
            Log.w(TAG, "panel insets install contained type=${error.javaClass.name}")
        }
    }

    /**
     * Captures the baseline panel top/height once when the chat is opened or the
     * configuration changes. The subsequent global-layout adaptation consumes
     * these actual baseline values without reading display metrics in the loop.
     */
    private fun refreshChatPanelBaseline() {
        try {
            val (sw, sh) = screenSize()
            val d = density()
            val landscape = sw > sh
            val height = OverlayLayout.orbPanelHeightPx(sh, d, landscape)
            chatPanelBaselineHeightPx = height
            chatPanelBaselineTopPx = OverlayLayout.orbPanelTopPx(
                sh,
                d,
                landscape,
                height,
                orbYDp,
            )
        } catch (error: Exception) {
            Log.w(TAG, "panel baseline contained type=${error.javaClass.name}")
        }
    }

    /**
     * Adapts only the chat panel to the attached orb window's real visible
     * frame. It reads no display metrics in the global-layout feedback loop:
     * the baseline top/height come from the last baseline layout, and the live
     * IME-adjusted frame comes from getWindowVisibleDisplayFrame/root insets.
     */
    private fun applyChatPanelInsets() {
        if (destroyed || !chatExpanded || applyingPanelHeight) return
        try {
            applyChatPanelInsetsContained()
        } catch (error: Exception) {
            // The panel adaptation is optional chrome; a bad measure/layout must
            // not escape the global-layout callback into the host.
            Log.w(TAG, "panel insets contained type=${error.javaClass.name}")
        }
    }

    private fun applyChatPanelInsetsContained() {
        val panel = chatPanel
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val baselineHeight = when {
            chatPanelBaselineHeightPx > 0 -> chatPanelBaselineHeightPx
            lp.height > 0 -> lp.height
            else -> return
        }
        val baselineTop = when {
            chatPanelBaselineTopPx >= 0 -> chatPanelBaselineTopPx
            else -> lp.topMargin.coerceAtLeast(0)
        }
        val rootLocation = IntArray(2)
        orbRoot.getLocationOnScreen(rootLocation)
        val frame = chatVisibleFrame() ?: return
        val requestedTop = rootLocation[1] + baselineTop
        val target = BubblePreviewPanelGeometry.chatPanelFrame(
            requestedTopPx = requestedTop,
            baselineHeightPx = baselineHeight,
            visibleTopPx = frame.topPx,
            visibleFrameBottomPx = frame.bottomPx,
            bottomMarginPx = dp(8),
            compactMinimumHeightPx = compactChatControlsHeightPx(),
        ) ?: return
        applyChatPanelCompact(target.compact)
        val targetTopMargin = (target.topPx - rootLocation[1]).coerceAtLeast(0)
        if (lp.height == target.heightPx && lp.topMargin == targetTopMargin) return
        applyingPanelHeight = true
        try {
            lp.topMargin = targetTopMargin
            lp.height = target.heightPx
            panel.layoutParams = lp
        } finally {
            applyingPanelHeight = false
        }
    }

    private data class ChatVisibleFrame(val topPx: Int, val bottomPx: Int)

    /**
     * The chat panel is independent of the optional bubble/IME gate: the first
     * populated send can open the keyboard before a bubble gate exists. Use the
     * attached orb window's own visible frame, with root insets only as a
     * fallback, and never a fixed 300 dp guess.
     */
    private fun chatVisibleFrame(): ChatVisibleFrame? {
        val windowFrame = Rect()
        val hasWindowFrame = try {
            orbRoot.getWindowVisibleDisplayFrame(windowFrame)
            windowFrame.bottom > windowFrame.top
        } catch (error: Exception) {
            Log.w(TAG, "panel visible frame contained type=${error.javaClass.name}")
            false
        }
        val location = IntArray(2)
        val hasLocation = try {
            orbRoot.getLocationOnScreen(location)
            true
        } catch (error: Exception) {
            Log.w(TAG, "panel root location contained type=${error.javaClass.name}")
            false
        }
        val screenVisible = rootVisibleScreenBounds(orbRoot)
        val top: Int
        val bottom: Int
        if (hasWindowFrame) {
            top = windowFrame.top
            bottom = windowFrame.bottom
        } else if (screenVisible != null) {
            top = screenVisible.top
            bottom = screenVisible.bottom
        } else if (hasLocation && orbRoot.height > 0) {
            top = location[1]
            bottom = location[1] + orbRoot.height
        } else {
            return null
        }
        if (screenVisible != null) {
            // Do not extend outside the actually visible root rectangle.
            val clippedTop = maxOf(top, screenVisible.top)
            val clippedBottom = minOf(bottom, screenVisible.bottom)
            if (clippedBottom > clippedTop) {
                return ChatVisibleFrame(clippedTop, clippedBottom)
            }
        }
        if (bottom <= top) return null
        return ChatVisibleFrame(top, bottom)
    }

    /** Compatibility accessor for existing panel tests. No metrics read. */
    private fun visibleFrameBottomPx(): Int = chatVisibleFrame()?.bottomPx ?: 0

    private fun minimumChatControlsHeightPx(): Int = chatControlsHeight(includeOptional = true)

    private fun compactChatControlsHeightPx(): Int = chatControlsHeight(includeOptional = false)

    private fun chatControlsHeight(includeOptional: Boolean): Int {
        val pad = chatPanel.paddingTop + chatPanel.paddingBottom
        val title = if (includeOptional) measuredHeightOf(chatTitle, dp(20)) else 0
        val status = if (includeOptional) measuredHeightOf(chatStatus, dp(18)) else 0
        val input = measuredHeightOf(chatInput, dp(40))
        val send = maxOf(measuredHeightOf(chatSend, dp(40)), measuredHeightOf(chatStop, dp(40)))
        val settings = if (includeOptional && chatSettingsShouldShow) {
            measuredHeightOf(chatSettings, dp(36))
        } else {
            0
        }
        val full = maxOf(
            measuredHeightOf(chatFull, dp(36)),
            measuredHeightOf(chatCollapse, dp(36)),
        )
        return pad + title + status + input + send + settings + full + dp(16)
    }

    private fun applyChatPanelCompact(compact: Boolean) {
        if (chatPanelCompact == compact) return
        chatPanelCompact = compact
        chatTitle.visibility = if (compact) View.GONE else View.VISIBLE
        chatStatus.visibility = if (compact) View.GONE else View.VISIBLE
        chatScroll.visibility = if (compact) View.GONE else View.VISIBLE
        chatSettings.visibility = if (!compact && chatSettingsShouldShow) {
            View.VISIBLE
        } else {
            View.GONE
        }
        // Keep the edit text and both action buttons at their declared sizes;
        // only the optional line count is reduced while keyboard space is tight.
        chatInput.maxLines = if (compact) 1 else 3
    }

    private fun resetChatPanelAdaptation() {
        chatPanelCompact = false
        chatTitle.visibility = View.VISIBLE
        chatStatus.visibility = View.VISIBLE
        chatScroll.visibility = View.VISIBLE
        chatSettings.visibility = if (chatSettingsShouldShow) View.VISIBLE else View.GONE
        chatInput.maxLines = 3
    }

    private fun measuredHeightOf(view: View, fallbackPx: Int): Int {
        val measured = view.height
        if (measured > 0) return measured
        return maxOf(view.minimumHeight, fallbackPx)
    }

    private fun projectedOrbBounds(): BubblePreviewRect? {
        val basis = readBubbleDisplayBasis() ?: return null
        if (!orbYDp.isFinite()) return null
        return BubblePreviewOrbProjection.collapsedButtonBounds(
            displayBounds = basis.displayBounds,
            sideRight = orbSideRight,
            orbYDp = orbYDp,
            orbSizeDp = BluefinStore.readOrbSizeDp(prefs()),
            density = density(),
        )
    }

    /**
     * Lazily read the optional display/bounds/system-bar basis once per
     * configuration epoch. Preflight and the later screen snapshot share it.
     * A real metrics Exception is a failed optional attempt; an invalid/missing
     * value remains ordinary unavailable geometry.
     */
    private fun readBubbleDisplayBasis(): BubblePreviewDisplayBasis? {
        bubbleDisplayBasis?.let { return it }
        return when (val result = BubblePreviewMeasure.capture { currentDisplayBasisOrNull() }) {
            is BubblePreviewMeasureResult.Value -> result.value.also { bubbleDisplayBasis = it }
            BubblePreviewMeasureResult.Missing -> null
            is BubblePreviewMeasureResult.Failed -> {
                Log.w(TAG, "bubble display basis failed type=${result.exceptionType}")
                recordBubbleOptionalFailure()
                null
            }
        }
    }

    private fun currentDisplayBasisOrNull(): BubblePreviewDisplayBasis? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            BubblePreviewDisplayBasis(
                displayBounds = BubblePreviewRect(
                    bounds.left,
                    bounds.top,
                    bounds.right,
                    bounds.bottom,
                ),
                systemInsets = BubblePreviewInsets(insets.left, insets.top, insets.right, insets.bottom),
            ).takeIf { it.isUsable() }
        } else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            val insets = root.rootWindowInsets
            var left = insets?.systemWindowInsetLeft ?: 0
            var top = insets?.systemWindowInsetTop ?: 0
            var right = insets?.systemWindowInsetRight ?: 0
            var bottom = insets?.systemWindowInsetBottom ?: 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets?.displayCutout?.let { cutout ->
                    left = maxOf(left, cutout.safeInsetLeft)
                    top = maxOf(top, cutout.safeInsetTop)
                    right = maxOf(right, cutout.safeInsetRight)
                    bottom = maxOf(bottom, cutout.safeInsetBottom)
                }
            }
            BubblePreviewDisplayBasis(
                displayBounds = BubblePreviewRect(0, 0, point.x, point.y),
                systemInsets = BubblePreviewInsets(left, top, right, bottom),
            ).takeIf { it.isUsable() }
        }
    }

    private fun currentDisplayBounds(): BubblePreviewRect? =
        readBubbleDisplayBasis()?.displayBounds

    private fun invalidateBubbleDisplayBasis() {
        bubbleDisplayBasis = null
        invalidateBubbleScreenSnapshot()
    }

    private fun invalidateBubbleScreenSnapshot() {
        bubbleScreenSnapshotGeneration = -1L
        bubbleScreenSnapshot = null
    }

    private fun nowMillis(): Long = SystemClock.uptimeMillis()

    private fun currentCharacterName(): String {
        val manifest = currentManifest
        return if (currentPackId == PackFormat.BUILT_IN_ID) {
            PackAppearance.BUILT_IN_DISPLAY_NAME
        } else {
            manifest?.displayName() ?: currentPackId
        }
    }

    /**
     * Converts the existing 80 ms layout stream to immutable screen geometry for
     * the optional preview. This runs only after pet edge/sleep/hit handling and
     * only when an explicit STARTED action already created the controller.
     */
    private fun publishBubbleGeometry(
        bubble: BubblePreviewController,
        parsed: JSONObject,
        sampledAtElapsedMs: Long,
    ) {
        try {
            val hitsJson = parsed.optJSONArray("hits")
            val d = density()
            val location = IntArray(2)
            web.getLocationOnScreen(location)
            var dragDx = 0f
            var dragDy = 0f
            if (tracking && hitAttached) {
                dragDx = (hitParams.x - bubbleHitBaseLeft).toFloat()
                dragDy = (hitParams.y - bubbleHitBaseTop).toFloat()
            }
            val hits = mutableListOf<BubblePreviewHit>()
            if (hitsJson != null) {
                for (index in 0 until hitsJson.length()) {
                    val hit = hitsJson.getJSONObject(index)
                    hits.add(
                        BubblePreviewHit(
                            x = location[0] + hit.optDouble("x").toFloat() * d + dragDx,
                            y = location[1] + hit.optDouble("y").toFloat() * d + dragDy,
                            radius = hit.optDouble("r").toFloat() * d,
                        ),
                    )
                }
            }
            val characterBounds = BubblePreviewHitGeometry.characterBounds(hits)
            val headAnchor = BubblePreviewHitGeometry.headAnchor(hits)
            val screen = bubbleScreenSnapshot(bubbleGeometryGeneration)
            val geometry = if (characterBounds != null && headAnchor != null && screen != null) {
                BubblePreviewGeometrySnapshot(
                    hostGeneration = bubbleHostGeneration,
                    geometryGeneration = bubbleGeometryGeneration,
                    sampledAtElapsedMs = sampledAtElapsedMs,
                    displayBounds = screen.displayBounds,
                    systemInsets = screen.systemInsets,
                    rootVisibleBounds = screen.rootVisibleBounds,
                    imeInsets = screen.imeInsets,
                    characterBounds = characterBounds,
                    headAnchor = headAnchor,
                    orbWindowBounds = currentOrbWindowBounds(screen.displayBounds),
                )
            } else {
                null
            }
            val frame = BubblePreviewHostFrame(
                hostGeneration = bubbleHostGeneration,
                geometryGeneration = bubbleGeometryGeneration,
                sampledAtElapsedMs = sampledAtElapsedMs,
                stageAttached = attached,
                pageReady = pageReady,
                clickThrough = clickThrough,
                hidden = petHiddenForBubble,
                opacity = petOpacityForBubble,
                geometry = geometry,
            )
            bubble.onHostLayout(frame)
            tryAdvanceBubbleFirstAttach(frame)
        } catch (error: Exception) {
            Log.w(TAG, "optional bubble publish contained type=${error.javaClass.name}")
            abortBubblePreviewRound()
        }
    }

    private fun tryAdvanceBubbleFirstAttach(frame: BubblePreviewHostFrame) {
        if (bubbleFirstAttach == null) return
        val token = bubbleFirstAttachToken
        try {
            advanceBubbleFirstAttach(frame)
        } catch (error: Exception) {
            Log.w(TAG, "bubble first-attach advance contained type=${error.javaClass.name}")
            finishBubbleFirstAttach(token, restore = true)
        }
    }

    private fun currentOrbWindowBounds(displayBounds: BubblePreviewRect): BubblePreviewRect? {
        if (!orbAttached) return null
        if (orbExpanded || chatExpanded) {
            // The expanded orb window is genuinely full screen. Report its real
            // visible rectangle in screen coordinates instead of pretending it
            // is the small collapsed button; the first-attach flow refuses READY
            // until the panel is collapsed and this function returns the actual
            // small button.
            return rootVisibleScreenBounds(orbRoot)
        }
        // Prefer the real on-screen origin and actual laid-out size. Deriving
        // the origin from display+params assumes zero display origin and can
        // drift on cutout/secondary-display window contexts.
        val onScreen = IntArray(2)
        orbRoot.getLocationOnScreen(onScreen)
        val bounds = BubblePreviewScreenCoordinates.compactBounds(
            locationOnScreen = BubblePreviewPoint(onScreen[0].toFloat(), onScreen[1].toFloat()),
            widthPx = orbRoot.width,
            heightPx = orbRoot.height,
        )
        if (bounds != null) return bounds
        if (orbParams.width <= 0 || orbParams.height <= 0) return null
        val left = displayBounds.left + orbParams.x
        val top = displayBounds.top + orbParams.y
        return BubblePreviewRect(
            left = left,
            top = top,
            right = left + orbParams.width,
            bottom = top + orbParams.height,
        ).takeIf { it.isValid() }
    }

    /**
     * One screen/bars/cutout/IME read per geometry generation. The display and
     * bar basis is shared with preflight; the IME edge comes from the cached
     * trustworthy gate. Missing/UNKNOWN data returns null, never a guessed 1x1
     * size and never a zero keyboard while the gate still says UNKNOWN.
     */
    private fun bubbleScreenSnapshot(geometryGeneration: Long): BubblePreviewScreenSnapshot? {
        if (bubbleScreenSnapshotGeneration == geometryGeneration) return bubbleScreenSnapshot
        bubbleScreenSnapshotGeneration = geometryGeneration
        val captured = when (
            val result = BubblePreviewMeasure.capture { captureBubbleScreenSnapshot() }
        ) {
            is BubblePreviewMeasureResult.Value -> result.value
            BubblePreviewMeasureResult.Missing -> null
            is BubblePreviewMeasureResult.Failed -> {
                // A thrown metrics/snapshot Exception is an optional display
                // failure, not ordinary missing geometry. Count it once on the
                // active attempt and immediately drop the old safe geometry.
                Log.w(TAG, "bubble screen geometry failed type=${result.exceptionType}")
                recordBubbleOptionalFailure()
                null
            }
        }
        bubbleScreenSnapshot = captured
        return captured
    }

    private fun captureBubbleScreenSnapshot(): BubblePreviewScreenSnapshot? {
        val basis = readBubbleDisplayBasis() ?: return null
        val rootVisible = rootVisibleScreenBounds(root) ?: return null
        // The preview must use the real cached IME gate. The old helper returned
        // 0 whenever the record panel was collapsed, even while the gate was
        // VISIBLE, allowing an attach over a live keyboard.
        val gate = bubbleImeGate ?: return null
        if (!gate.isTrustworthy()) return null
        val imeInsets = gate.imeBottomInsets()
        if (!imeInsets.isValid()) return null
        if (!basis.isUsable()) return null
        return BubblePreviewScreenSnapshot(
            displayBounds = basis.displayBounds,
            systemInsets = basis.systemInsets,
            imeInsets = imeInsets,
            rootVisibleBounds = rootVisible,
        ).takeIf { it.isUsable() }
    }

    /**
     * Converts a root/window-relative getGlobalVisibleRect result to actual
     * screen pixels. `getGlobalVisibleRect` is not guaranteed to include the
     * window origin, so the screen origin is computed explicitly from
     * locationOnScreen - locationInWindow.
     */
    private fun rootVisibleScreenBounds(view: View): BubblePreviewRect? {
        val local = Rect()
        if (!view.getGlobalVisibleRect(local)) return null
        val onScreen = IntArray(2)
        val inWindow = IntArray(2)
        view.getLocationOnScreen(onScreen)
        view.getLocationInWindow(inWindow)
        return BubblePreviewScreenCoordinates.visibleBounds(
            localVisible = BubblePreviewRect(local.left, local.top, local.right, local.bottom),
            locationOnScreen = BubblePreviewPoint(onScreen[0].toFloat(), onScreen[1].toFloat()),
            locationInWindow = BubblePreviewPoint(inWindow[0].toFloat(), inWindow[1].toFloat()),
        )
    }

    private fun recordBubbleOptionalFailure() {
        val attempt = bubbleDisplayAttempt
        val counted = attempt?.recordFailure(nowMillis()) == true
        Log.w(TAG, "bubble optional host failure counted=$counted")
        invalidateBubbleScreenSnapshot()
        // Never keep old safe geometry while a new epoch waits: clear the token,
        // remove the live window and drop pending posts immediately.
        if (bubbleFirstAttach != null) {
            finishBubbleFirstAttach(bubbleFirstAttachToken, restore = true)
        } else {
            abortBubblePreviewRound()
        }
        bubbleDisplayAttempt = null
    }

    private fun applyStageSize() {
        val (sw, sh) = screenSize()
        params.width = sw
        params.height = sh
        params.x = 0
        params.y = 0
    }

    private fun relayout() {
        if (!attached || destroyed) return
        bubbleGeometryGeneration += 1
        invalidateBubbleDisplayBasis()
        bubblePreview?.let { runBubbleHostCallback("relayout") { it.onHostConfigurationChanged() } }
        applyStageSize()
        try {
            windowManager.updateViewLayout(root, params)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "stage update skipped", error)
        }
        layoutOrb()
        if (chatExpanded) refreshChatPanelBaseline()
        if (pageReady) web.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
    }

    private fun removeIfAttached(view: View, isAttached: Boolean) {
        if (!isAttached) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "extra window already gone", error)
        }
    }

    private fun cssX(rawX: Float): Float {
        val loc = IntArray(2)
        web.getLocationOnScreen(loc)
        return (rawX - loc[0]) / density()
    }

    private fun cssY(rawY: Float): Float {
        val loc = IntArray(2)
        web.getLocationOnScreen(loc)
        return (rawY - loc[1]) / density()
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
        } else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            point.x.coerceAtLeast(1) to point.y.coerceAtLeast(1)
        }
    }

    private fun registerDisplayCallbacks() {
        uiContext.registerComponentCallbacks(componentCallbacks)
        uiContext.getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
    }

    private fun unregisterDisplayCallbacks() {
        try {
            uiContext.unregisterComponentCallbacks(componentCallbacks)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "overlay callbacks already gone", error)
        }
        try {
            uiContext.getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "overlay display listener already gone", error)
        }
    }

    private fun destroyWebView() {
        if (webDestroyed) return
        webDestroyed = true
        cuePlayer.release()
        try {
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.removeJavascriptInterface(BRIDGE_NAME)
            web.removeJavascriptInterface(OVERLAY_BRIDGE)
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        } catch (error: RuntimeException) {
            Log.w(TAG, "overlay WebView cleanup failed", error)
        }
    }

    private fun prefs() = appContext.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
    private fun getString(id: Int): String = uiContext.getString(id)
    private fun soundOn(): Boolean = prefs().getBoolean(BluefinStore.KEY_SOUND, true)
    private fun density(): Float = uiContext.resources.displayMetrics.density.coerceAtLeast(0.5f)
    private fun dp(value: Int): Int = (value * density()).roundToInt()

    private companion object {
        const val TAG = "BluefinOverlay"
        const val BRIDGE_NAME = "BluefinNative"
        const val OVERLAY_BRIDGE = "BluefinOverlay"
        const val FIRST_ATTACH_DEADLINE_MS = 2500L
        const val SPEECH_INPUT_DEADLINE_MS = 2500L
        const val INSETS_EPOCH_COALESCE_MS = 100L

        fun createUiContext(app: Context): Context {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return app
            return try {
                val display = app.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)
                    ?: return app
                app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } catch (error: RuntimeException) {
                Log.w(TAG, "window context unavailable, using application context", error)
                app
            }
        }

        fun checkPlatformFlags() {
            check(OverlayLayout.TYPE_APPLICATION_OVERLAY == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            check(OverlayLayout.FLAG_NOT_FOCUSABLE == WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            check(OverlayLayout.FLAG_NOT_TOUCHABLE == WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            check(OverlayLayout.FLAG_NOT_TOUCH_MODAL == WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            check(OverlayLayout.FLAG_LAYOUT_IN_SCREEN == WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            check(OverlayLayout.FLAG_HARDWARE_ACCELERATED == WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        }
    }
}

/**
 * Immutable, bounded snapshot of the accepted round. Only these values cross
 * the post boundary; the session snapshot/history is never retained.
 */
private data class BubbleSignalPayload(
    val requestGeneration: Int,
    val cumulativeAssistant: String,
    val terminal: Boolean,
    val stopped: Boolean,
    val failed: Boolean,
    val safeFailureText: String?,
    val indexValid: Boolean,
)

@Keep
internal class OverlayLayoutSink(private val deliver: (String) -> Unit) {
    @JavascriptInterface
    fun layout(json: String?) {
        if (!json.isNullOrBlank()) deliver(json)
    }
}
