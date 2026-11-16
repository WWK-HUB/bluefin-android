package com.wwkhub.bluefin

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wwkhub.bluefin.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), BluefinHost {
    private lateinit var binding: ActivityMainBinding
    private lateinit var assetLoader: WebViewAssetLoader
    private val cuePlayer = CuePlayer()
    private val applyPetFrame = Runnable { applyPetPrefs() }
    private val bridge = BluefinBridge(this)

    @Volatile
    private var soundOn = true
    private var replacementWeb: WebView? = null
    private val previewWeb: WebView get() = replacementWeb ?: binding.web
    private var previewRendererRestarts = 0
    private var expectedPreviewUrl = ""
    private var lastReadyAppearance = PackFormat.BUILT_IN_ID

    private var previewReady = false
    private var entryPath = PreviewEntry.ROUTE_B
    private var pollGeneration = 0
    private var lastProblem: String? = null
    private var tipIndex = 0
    private var roamOn = true
    private var petHidden = false
    private var petScale = BluefinStore.SCALE_DEFAULT
    private var petOpacity = BluefinStore.OPACITY_DEFAULT
    private var petOrbSizeDp = BluefinStore.ORB_SIZE_DEFAULT_DP
    private var pendingOverlayGuide = false
    private var tipsDialog: AppCompatDialog? = null
    private var tipsView: View? = null
    private var currentAppearanceId = PackFormat.BUILT_IN_ID
    private var currentManifest: PackManifest? = null
    private var pendingImport: PackAnalysis? = null
    private var openCharacterSection: CharacterSection? = null
    private var lastLoadedEntryKey: String? = null
    private var loadingEntry = false

    private val packZipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) beginPackImport(uri, folder = false)
    }

    private val packFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) beginPackImport(uri, folder = true)
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_SCALE, KEY_SIZE -> {
                petScale = BluefinStore.readScale(prefs())
                schedulePetPrefs()
            }
            KEY_OPACITY -> {
                petOpacity = BluefinStore.readOpacity(prefs())
                schedulePetPrefs()
            }
            KEY_ORB_SIZE_DP -> {
                // Orb size only drives the overlay orb; keep the field in sync
                // and let the open Settings sheet refresh itself.
                petOrbSizeDp = BluefinStore.readOrbSizeDp(prefs())
            }
            KEY_ROAM -> {
                roamOn = prefs().getBoolean(KEY_ROAM, true)
                applyPetPrefs()
            }
            KEY_HIDDEN -> {
                petHidden = prefs().getBoolean(KEY_HIDDEN, false)
                applyPetPrefs()
            }
            KEY_SOUND -> {
                soundOn = prefs().getBoolean(KEY_SOUND, true)
                applySound()
            }
            KEY_CHARACTER_ID -> {
                refreshCharacter()
                loadEntry()
            }
            else -> {
                if (key != null && key.startsWith(KEY_CHARACTER_SCHEME_PREFIX)) loadEntry()
            }
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) BluefinForegroundService.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        refreshFromPrefs()
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        applyInsets()
        ensureForegroundService()
        setupWebView()
        binding.openActions.setOnClickListener { showActions() }
        binding.openChat.setOnClickListener { ChatEntry.request(this) }
        binding.openOverlay.setOnClickListener { OverlayLauncher.toggle(this) }
        binding.openSettings.setOnClickListener { showSettings() }
        binding.statusRetry.setOnClickListener { loadEntry(force = true) }
        binding.openActions.isEnabled = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (previewWeb.canGoBack()) previewWeb.goBack() else finish()
            }
        })

        loadEntry()
        consumeOverlayIntent(intent)
        consumeOpenModelIntent(intent)
        consumeOpenCharacterIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOverlayIntent(intent)
        consumeOpenModelIntent(intent)
        consumeOpenCharacterIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        OverlayAppVisibility.setForeground(true)
        refreshFromPrefs()
        if (currentAppearanceId != PackRepository.currentId(this)) {
            loadEntry()
            openCharacterSection?.refresh()
        }
        if (previewReady) {
            applySound()
            applyPetPrefs()
        }
        if (::binding.isInitialized) previewWeb.onResume()
        OverlayLauncher.onResume(this)
        BluefinForegroundService.refresh(this)
        if (pendingOverlayGuide) {
            pendingOverlayGuide = false
            window.decorView.post {
                if (isFinishing || isDestroyed) return@post
                if (Settings.canDrawOverlays(this)) OverlayLauncher.show(this) else OverlayLauncher.showGuide(this)
            }
        }
    }

    override fun onPause() {
        OverlayAppVisibility.setForeground(false)
        if (::binding.isInitialized) previewWeb.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        previewWeb.removeCallbacks(applyPetFrame)
        pollGeneration++
        pendingImport?.cleanup()
        pendingImport = null
        openCharacterSection = null
        dismissTips()
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        cuePlayer.release()
        if (::binding.isInitialized) {
            previewWeb.apply {
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                removeJavascriptInterface(BRIDGE_NAME)
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
        }
        super.onDestroy()
    }

    override fun isSoundOn(): Boolean = soundOn

    override fun onScriptMessage(message: String) {
        Log.w(TAG, message)
        runOnUiThread {
            if (!previewReady && message.isNotBlank()) lastProblem = message
        }
    }

    override fun onPlayCue(name: String, kind: String) {
        if (!soundOn) return
        runOnUiThread { cuePlayer.play(name.ifBlank { kind }) }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val extra = (8 * resources.displayMetrics.density).toInt()
            binding.topBar.setPadding(bars.left + extra, bars.top + extra, bars.right + extra, 0)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", AssetMimeHandler(this))
            .addPathHandler("/packs/", PackAssetHandler(this))
            .build()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        previewWeb.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        previewWeb.overScrollMode = View.OVER_SCROLL_NEVER
        previewWeb.isVerticalScrollBarEnabled = false
        previewWeb.isHorizontalScrollBarEnabled = false
        previewWeb.isLongClickable = false
        previewWeb.isHapticFeedbackEnabled = false
        previewWeb.setOnLongClickListener { true }
        previewWeb.settings.apply {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = false
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) forceDark = WebSettings.FORCE_DARK_OFF
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isAlgorithmicDarkeningAllowed = false
        }
        previewWeb.addJavascriptInterface(bridge, BRIDGE_NAME)
        previewWeb.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "${consoleMessage.messageLevel()} ${consoleMessage.message()}")
                return true
            }
        }
        previewWeb.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host
                return host != "appassets.androidplatform.net"
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (view !== previewWeb || url != expectedPreviewUrl) return
                injectBridge()
                pollReady()
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if (view !== previewWeb || isDestroyed) return true
                pollGeneration++
                previewReady = false
                val parent = view.parent as? android.view.ViewGroup ?: return false
                val index = parent.indexOfChild(view)
                val params = view.layoutParams
                parent.removeView(view)
                view.destroy()
                replacementWeb = WebView(this@MainActivity)
                parent.addView(previewWeb, index, params)
                setupWebView()
                if (++previewRendererRestarts <= 3) {
                    if (previewRendererRestarts > 1) PackRepository.setCurrent(this@MainActivity, PackFormat.BUILT_IN_ID)
                    loadEntry(force = true)
                } else {
                    binding.openActions.isEnabled = false
                    showStatus(getString(R.string.orb_stage_failed), error = true)
                }
                return true
            }
        }
    }

    private fun loadEntry(force: Boolean = false) {
        if (loadingEntry) return
        loadingEntry = true
        try {
            val appearance = refreshCharacter()
            val scheme = PackRepository.readScheme(this, appearance.manifest)
            val useDefaultPreview = appearance.id == PackFormat.BUILT_IN_ID && scheme.isEmpty()
            val key = "pack:${appearance.id}:$scheme:${PackRepository.revision(this, appearance.id)}"
            if (!force && key == lastLoadedEntryKey && previewReady) return
            lastLoadedEntryKey = key
            pollGeneration++
            lastProblem = null
            previewReady = false
            binding.openActions.isEnabled = false
            dismissTips()
            if (!useDefaultPreview) {
                entryPath = PreviewEntry.PACK_HOST
                binding.selfTestBanner.visibility = View.GONE
                if (!assetExists(PreviewEntry.PACK_HOST)) {
                    showStatus(missingMessage(PreviewEntry.PACK_HOST), error = true)
                    return
                }
                showStatus(getString(R.string.loading), error = false)
                val url = PreviewEntry.packHostUrl(appearance.id, scheme, appearance.builtIn)
                Log.i(TAG, "load pack $url")
                navigatePreview(url)
                return
            }
            val path = PreviewEntry.choose(assetExists(PreviewEntry.ROUTE_B), assetExists(PreviewEntry.ROUTE_A))
            entryPath = path
            val selfTest = PreviewEntry.isSelfTest(path) && assetExists(path)
            binding.selfTestBanner.visibility = if (selfTest) View.VISIBLE else View.GONE
            if (!assetExists(path)) {
                showStatus(missingMessage(path), error = true)
                return
            }
            showStatus(getString(R.string.loading), error = false)
            val url = PreviewEntry.urlFor(path)
            Log.i(TAG, "load $url")
            navigatePreview(url)
        } finally {
            loadingEntry = false
        }
    }

    private fun navigatePreview(url: String) {
        val generation = pollGeneration
        expectedPreviewUrl = url + (if ('?' in url) "&" else "?") + "load=$generation"
        previewWeb.loadUrl(expectedPreviewUrl)
        previewWeb.postDelayed({
            if (!isDestroyed && !previewReady && expectedPreviewUrl.endsWith("load=$generation")) failPreview()
        }, 15_000L)
    }

    private fun failPreview() {
        pollGeneration++
        expectedPreviewUrl = ""
        if (currentAppearanceId != PackFormat.BUILT_IN_ID) {
            val fallback = if (lastReadyAppearance == currentAppearanceId) PackFormat.BUILT_IN_ID else lastReadyAppearance
            Toast.makeText(this, R.string.orb_stage_failed, Toast.LENGTH_LONG).show()
            switchCharacter(fallback)
        } else showStatus(getString(R.string.load_timeout), error = true)
    }

    private fun refreshCharacter(): PackAppearance {
        val resolved = PackRepository.resolveCurrent(this)
        currentAppearanceId = resolved.appearance.id
        currentManifest = resolved.appearance.manifest
        if (resolved.fellBack) {
            Toast.makeText(this, R.string.character_fell_back, Toast.LENGTH_SHORT).show()
        }
        return resolved.appearance
    }

    fun promptPackImportSource() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.character_import)
            .setItems(
                arrayOf(
                    getString(R.string.character_import_zip),
                    getString(R.string.character_import_folder),
                ),
            ) { _, which ->
                if (which == 0) {
                    packZipPicker.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"),
                    )
                } else {
                    packFolderPicker.launch(null)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun switchCharacter(id: String) {
        PackRepository.setCurrent(this, id)
        refreshCharacter()
        openCharacterSection?.refresh()
        loadEntry()
        OverlayLauncher.sync(this)
    }

    fun deleteCharacter(id: String) {
        val wasCurrent = PackRepository.currentId(this) == id
        if (PackRepository.delete(this, id)) {
            if (wasCurrent) {
                refreshCharacter()
                loadEntry()
                OverlayLauncher.sync(this)
                Toast.makeText(this, R.string.character_fell_back, Toast.LENGTH_SHORT).show()
            }
            openCharacterSection?.refresh()
        }
    }

    private fun beginPackImport(uri: android.net.Uri, folder: Boolean) {
        pendingImport?.cleanup()
        pendingImport = null
        Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
        thread(name = "bluefin-pack-import") {
            try {
                val analysis = if (folder) PackRepository.importTree(this, uri) else PackRepository.importZip(this, uri)
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        analysis.cleanup()
                    } else {
                        onPackAnalysis(analysis)
                    }
                }
            } catch (error: PackImportException) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showPackImportFailure(error)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showPackImportFailure(PackImportException(PackImportFailure.IO, error.message.orEmpty(), error))
                    }
                }
            }
        }
    }

    private fun showPackImportFailure(error: PackImportException) {
        val message = when (error.failure) {
            PackImportFailure.TOO_LARGE -> getString(R.string.import_too_large)
            PackImportFailure.BAD_ZIP -> getString(R.string.import_bad_zip)
            PackImportFailure.PATH_UNSAFE -> getString(R.string.import_generic)
            PackImportFailure.CAPACITY -> getString(R.string.import_capacity)
            PackImportFailure.REPLACE_REQUIRED, PackImportFailure.IO -> getString(R.string.import_generic)
        }
        MaterialAlertDialogBuilder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun packProblemMessage(problem: PackProblem): String = when (problem.rejection) {
        PackReject.INCOMPATIBLE_NEW, PackReject.INCOMPATIBLE_OLD -> getString(R.string.import_incompatible)
        PackReject.BUILT_IN_ID -> getString(R.string.import_builtin)
        PackReject.RESERVED_ID -> getString(R.string.import_reserved)
        PackReject.BAD_JSON, PackReject.INCOMPLETE, PackReject.INVALID_AXES,
        PackReject.INVALID_PRESETS, PackReject.INVALID_VOCAB, PackReject.INVALID_SOUNDS,
        PackReject.INVALID_MOVES, PackReject.INVALID_NEEDS, PackReject.INVALID_ID,
        -> getString(R.string.import_manifest_incomplete)
        null -> getString(R.string.import_generic)
    }

    private fun onPackAnalysis(analysis: PackAnalysis) {
        pendingImport?.cleanup()
        pendingImport = analysis
        if (analysis.candidates.isEmpty()) {
            pendingImport = null
            val message = if (analysis.problems.isEmpty()) {
                getString(R.string.import_no_figure)
            } else {
                packProblemMessage(analysis.problems.first())
            }
            analysis.cleanup()
            MaterialAlertDialogBuilder(this).setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val continueImport = {
            if (analysis.warningLarge) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.import_warn_large)
                    .setNegativeButton(R.string.cancel) { _, _ -> cancelPendingImport() }
                    .setPositiveButton(R.string.import_continue) { _, _ -> chooseCandidates(analysis) }
                    .show()
            } else {
                chooseCandidates(analysis)
            }
        }
        if (analysis.problems.isNotEmpty()) {
            val detail = analysis.problems.take(4).joinToString("\n") { "${it.source}：${packProblemMessage(it)}" }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.character_import)
                .setMessage(getString(R.string.character_import_problems, detail))
                .setNegativeButton(R.string.cancel) { _, _ -> cancelPendingImport() }
                .setPositiveButton(android.R.string.ok) { _, _ -> continueImport() }
                .show()
        } else {
            continueImport()
        }
    }

    private fun chooseCandidates(analysis: PackAnalysis) {
        val candidates = analysis.candidates
        if (candidates.size == 1) {
            confirmCandidates(listOf(candidates.first()))
            return
        }
        val labels = candidates.map { candidate ->
            "${candidate.manifest.displayName()} · ${candidate.manifest.version}"
        }.toTypedArray()
        val checked = BooleanArray(candidates.size) { true }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.character_import_pick_title)
            .setMultiChoiceItems(labels, checked) { _, index, value -> checked[index] = value }
            .setNegativeButton(R.string.cancel) { _, _ -> cancelPendingImport() }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = candidates.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) cancelPendingImport() else confirmCandidates(selected)
            }
            .show()
    }

    private fun confirmCandidates(candidates: List<PackCandidate>) {
        val imported = PackRepository.listImported(this)
        val existingIds = imported.map { it.id }.toSet()
        val newIds = candidates.map { it.manifest.id }.filterNot { it in existingIds }.toSet()
        if (imported.size + newIds.size > PackRepository.MAX_IMPORTED) {
            pendingImport?.cleanup()
            pendingImport = null
            MaterialAlertDialogBuilder(this).setMessage(R.string.character_capacity)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val replacements = candidates.filter { it.manifest.id in existingIds }
        if (replacements.isNotEmpty()) {
            val detail = replacements.joinToString("\n") { candidate ->
                val old = imported.first { it.id == candidate.manifest.id }
                getString(
                    R.string.character_replace_message,
                    candidate.manifest.displayName(),
                    old.manifest.version,
                    candidate.manifest.version,
                )
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.character_replace_title)
                .setMessage(detail)
                .setNegativeButton(R.string.cancel) { _, _ -> cancelPendingImport() }
                .setPositiveButton(android.R.string.ok) { _, _ -> installCandidates(candidates, replace = true) }
                .show()
        } else {
            installCandidates(candidates, replace = false)
        }
    }

    private fun installCandidates(candidates: List<PackCandidate>, replace: Boolean) {
        val analysis = pendingImport
        pendingImport = null
        thread(name = "bluefin-pack-install") {
            try {
                val installed = candidates.map { PackRepository.install(this, it, replace) }
                analysis?.cleanup()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) onPacksInstalled(installed)
                }
            } catch (error: Exception) {
                analysis?.cleanup()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showPackImportFailure(
                            error as? PackImportException
                                ?: PackImportException(PackImportFailure.IO, error.message.orEmpty(), error),
                        )
                    }
                }
            }
        }
    }

    private fun onPacksInstalled(installed: List<PackAppearance>) {
        openCharacterSection?.refresh()
        if (installed.isEmpty()) return
        if (installed.any { it.id == PackRepository.currentId(this) }) {
            loadEntry()
            OverlayLauncher.sync(this)
        }
        val first = installed.first()
        val message = if (installed.size == 1) {
            getString(R.string.character_switch_message, first.displayName)
        } else {
            getString(R.string.character_switch_many, installed.size)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.character_switch_title)
            .setMessage(message)
            .setNegativeButton(R.string.character_switch_later, null)
        if (installed.size == 1) {
            builder.setPositiveButton(R.string.character_use) { _, _ -> switchCharacter(first.id) }
        } else {
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                val labels = installed.map { it.displayName }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.character_switch_title)
                    .setItems(labels) { _, index -> switchCharacter(installed[index].id) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        builder.show()
    }

    private fun cancelPendingImport() {
        pendingImport?.cleanup()
        pendingImport = null
    }

    private fun injectBridge() {
        val script = assets.open("native/bridge.js").bufferedReader().use { it.readText() }
        previewWeb.evaluateJavascript(script, null)
        applySound()
    }

    private fun pollReady() {
        val generation = ++pollGeneration
        var tries = 0
        fun tick() {
            if (generation != pollGeneration || isDestroyed) return
            previewWeb.evaluateJavascript("window.__bluefinLoadFailed ? -1 : (" + READY_JS + ")") { raw ->
                if (generation != pollGeneration || isDestroyed) return@evaluateJavascript
                if (raw == "true") {
                    onPreviewReady()
                } else if (raw == "-1") {
                    failPreview()
                } else if (++tries < 75) {
                    previewWeb.postDelayed({ tick() }, 200)
                } else {
                    failPreview()
                }
            }
        }
        tick()
    }

    private fun onPreviewReady() {
        if (previewReady) return
        previewReady = true
        lastReadyAppearance = currentAppearanceId
        previewWeb.evaluateJavascript("window.__bluefinNative&&window.__bluefinNative.fitStage()", null)
        applySound()
        showStatus("", error = false)
        binding.statusOverlay.visibility = View.GONE
        binding.openActions.isEnabled = true
        applyPetPrefs()
        if (!prefs().getBoolean(KEY_TIPS_DONE, false)) showTips()
    }

    private fun showActions() {
        if (!previewReady) return
        val appearance = PackRepository.current(this)
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_actions, null)
        fun pick(word: String) {
            if (word == "walk" && !appearance.canWalk) {
                Toast.makeText(this, R.string.character_cannot_walk, Toast.LENGTH_SHORT).show()
                return
            }
            trigger(word)
            dialog.dismiss()
        }
        val stand = view.findViewById<View>(R.id.action_stand)
        if (appearance.id == PackFormat.BUILT_IN_ID) {
            stand.visibility = View.VISIBLE
            stand.setOnClickListener { pick("stand") }
            fillActionGrid(view.findViewById(R.id.actions_motions), packVocab("motion"), ::pick)
            fillActionGrid(view.findViewById(R.id.actions_expressions), packVocab("expression"), ::pick)
        } else {
            stand.visibility = View.GONE
            val motions = appearance.manifest.vocab
                .filter { it.kind == "motion" }
                .map { it.id to it.label() }
            val expressions = appearance.manifest.vocab
                .filter { it.kind == "expression" }
                .map { it.id to it.label() }
            fillActionGrid(view.findViewById(R.id.actions_motions), motions, ::pick)
            fillActionGrid(view.findViewById(R.id.actions_expressions), expressions, ::pick)
            if (motions.isEmpty() && expressions.isEmpty()) {
                val empty = TextView(this).apply {
                    text = getString(R.string.import_no_actions)
                    setTextColor(getColor(R.color.muted))
                    textSize = 13f
                    setPadding(0, dp(12), 0, dp(12))
                }
                (view.findViewById<View>(R.id.actions_motions).parent as? android.view.ViewGroup)
                    ?.addView(empty)
            }
        }
        dialog.setContentView(view)
        dialog.show()
    }

    /**
     * Exposes the pack's own vocabulary in the in-app drawer. figure.json stays
     * untouched; if it cannot be read we still keep the five quick motions.
     */
    private fun packVocab(kind: String): List<Pair<String, String>> {
        val fallback = if (kind == "motion") {
            listOf(
                "wave" to getString(R.string.action_wave),
                "walk" to getString(R.string.action_walk),
                "run" to getString(R.string.action_run),
                "dance" to getString(R.string.action_dance),
                "sleep" to getString(R.string.action_sleep),
            )
        } else {
            emptyList()
        }
        return try {
            val raw = assets.open("bluefin/figure.json").bufferedReader().use { it.readText() }
            val vocab = JSONObject(raw).optJSONArray("vocab") ?: return fallback
            val words = ArrayList<Pair<String, String>>()
            for (i in 0 until vocab.length()) {
                val item = vocab.optJSONObject(i) ?: continue
                if (item.optString("kind") != kind) continue
                val word = item.optString("id").trim()
                if (word.isEmpty()) continue
                val label = item.optJSONObject("names")
                    ?.optJSONArray("zh")
                    ?.optString(0)
                    ?.takeIf { it.isNotBlank() }
                    ?: word
                words.add(word to label)
            }
            words.ifEmpty { fallback }
        } catch (error: RuntimeException) {
            Log.w(TAG, "cannot read pack vocabulary", error)
            fallback
        } catch (error: IOException) {
            Log.w(TAG, "cannot open pack vocabulary", error)
            fallback
        }
    }

    private fun fillActionGrid(
        grid: GridLayout,
        words: List<Pair<String, String>>,
        onPick: (String) -> Unit,
    ) {
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val gap = (3 * density).roundToInt()
        words.forEach { (word, label) ->
            val button = com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minHeight = dp(48)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(gap, 0, gap, 0)
                setOnClickListener { onPick(word) }
            }
            button.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(gap, gap, gap, gap)
            }
            grid.addView(button)
        }
    }

    private fun trigger(word: String) {
        val quoted = JSONObject.quote(word)
        previewWeb.evaluateJavascript(
            "window.__bluefinNative&&window.__bluefinNative.act($quoted)",
            null,
        )
    }

    private fun showSettings(scrollToModel: Boolean = false, scrollToCharacter: Boolean = false) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_settings, null)
        val characterSectionFrame = view.findViewById<FrameLayout>(R.id.character_section)
        val modelSection = view.findViewById<android.widget.FrameLayout>(R.id.model_section)
        val personaSection = view.findViewById<android.widget.FrameLayout>(R.id.persona_section)
        layoutInflater.inflate(R.layout.section_model_settings, modelSection, true)
        layoutInflater.inflate(R.layout.section_persona_settings, personaSection, true)
        val characterSection = CharacterSection(this, characterSectionFrame)
        openCharacterSection = characterSection
        ModelSettingsSection(this, modelSection) { ChatEntry.request(this) }
        PersonaEditorSection(this, personaSection)
        val sound = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.sound_switch)
        val roam = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.roam_switch)
        val hide = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.hide_switch)
        val overlay = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.overlay_switch)
        val bubblePreview = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.bubble_preview_switch,
        )
        val sizeSlider = view.findViewById<SeekBar>(R.id.size_slider)
        val sizeValue = view.findViewById<android.widget.TextView>(R.id.size_value)
        val opacitySlider = view.findViewById<SeekBar>(R.id.opacity_slider)
        val opacityValue = view.findViewById<android.widget.TextView>(R.id.opacity_value)
        val orbSizeSlider = view.findViewById<SeekBar>(R.id.orb_size_slider)
        val orbSizeValue = view.findViewById<android.widget.TextView>(R.id.orb_size_value)
        val about = view.findViewById<android.widget.TextView>(R.id.about_body)
        var syncing = false

        fun syncControls() {
            syncing = true
            sound.isChecked = soundOn
            roam.isChecked = roamOn && (currentManifest?.canWalk != false)
            hide.isChecked = petHidden
            bubblePreview.isChecked = BluefinStore.readBubblePreview(prefs())
            sizeSlider.max = 80
            sizeSlider.progress = BluefinStore.scaleToProgress(petScale)
            sizeValue.text = String.format(Locale.US, "%.2f", petScale)
            opacitySlider.max = 100
            opacitySlider.progress = BluefinStore.opacityToProgress(petOpacity)
            opacityValue.text = "${(petOpacity * 100).roundToInt()}%"
            orbSizeSlider.max = 28
            orbSizeSlider.progress = BluefinStore.orbSizeToProgress(petOrbSizeDp)
            orbSizeValue.text = "${petOrbSizeDp.roundToInt()}dp"
            syncing = false
        }

        var overlaySync = false
        val overlayListener = OverlayLauncher.Listener { showing ->
            if (overlay.isChecked == showing) return@Listener
            overlaySync = true
            overlay.isChecked = showing
            overlaySync = false
        }
        overlay.isChecked = OverlayLauncher.isShowing()
        syncControls()
        overlay.setOnCheckedChangeListener { _, checked ->
            if (overlaySync || checked == OverlayLauncher.isShowing()) return@setOnCheckedChangeListener
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    overlaySync = true
                    overlay.isChecked = false
                    overlaySync = false
                    OverlayLauncher.showGuide(this)
                } else if (!OverlayLauncher.show(this)) {
                    overlaySync = true
                    overlay.isChecked = false
                    overlaySync = false
                }
            } else {
                OverlayLauncher.hide(this)
            }
        }
        OverlayLauncher.addListener(overlayListener)
        val dialogPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_SCALE, KEY_SIZE -> {
                    petScale = BluefinStore.readScale(prefs())
                    view.post { syncControls() }
                }
                KEY_OPACITY -> {
                    petOpacity = BluefinStore.readOpacity(prefs())
                    view.post { syncControls() }
                }
                KEY_ORB_SIZE_DP -> {
                    petOrbSizeDp = BluefinStore.readOrbSizeDp(prefs())
                    view.post { syncControls() }
                }
                KEY_ROAM -> {
                    roamOn = prefs().getBoolean(KEY_ROAM, true)
                    view.post { syncControls() }
                }
                KEY_SOUND -> {
                    soundOn = prefs().getBoolean(KEY_SOUND, true)
                    view.post { syncControls() }
                }
                KEY_HIDDEN -> {
                    petHidden = prefs().getBoolean(KEY_HIDDEN, false)
                    view.post { syncControls() }
                }
                KEY_BUBBLE_PREVIEW -> {
                    view.post { syncControls() }
                }
                KEY_CHARACTER_ID -> {
                    refreshCharacter()
                    view.post {
                        syncControls()
                        characterSection.refresh()
                    }
                }
                else -> {
                    if (key != null && key.startsWith(KEY_CHARACTER_SCHEME_PREFIX)) {
                        view.post {
                            syncControls()
                            characterSection.refresh()
                        }
                    }
                }
            }
        }
        prefs().registerOnSharedPreferenceChangeListener(dialogPrefListener)
        dialog.setOnDismissListener {
            prefs().unregisterOnSharedPreferenceChangeListener(dialogPrefListener)
            OverlayLauncher.removeListener(overlayListener)
            if (openCharacterSection === characterSection) openCharacterSection = null
        }
        view.findViewById<View>(R.id.overlay_prompt_reset).setOnClickListener {
            OverlayLauncher.resetPrompt(this)
        }
        sound.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            soundOn = checked
            prefs().edit().putBoolean(KEY_SOUND, checked).apply()
            applySound()
        }
        roam.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            if (checked && currentManifest?.canWalk == false) {
                Toast.makeText(this, R.string.character_cannot_walk, Toast.LENGTH_SHORT).show()
                syncing = true
                roam.isChecked = false
                syncing = false
                return@setOnCheckedChangeListener
            }
            roamOn = checked
            prefs().edit().putBoolean(KEY_ROAM, checked).apply()
            applyPetPrefs()
        }
        hide.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            petHidden = checked
            prefs().edit().putBoolean(KEY_HIDDEN, checked).apply()
            applyPetPrefs()
        }
        bubblePreview.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            // Persist only. An already-created preview removes itself through
            // its own preference listener; this path never constructs it.
            BluefinStore.writeBubblePreview(prefs(), checked)
        }
        sizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (syncing) return
                petScale = BluefinStore.progressToScale(progress)
                sizeValue.text = String.format(Locale.US, "%.2f", petScale)
                BluefinStore.writeScale(prefs(), petScale)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        opacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (syncing) return
                petOpacity = BluefinStore.progressToOpacity(progress)
                opacityValue.text = "${(petOpacity * 100).roundToInt()}%"
                BluefinStore.writeOpacity(prefs(), petOpacity)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        orbSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (syncing) return
                petOrbSizeDp = BluefinStore.progressToOrbSize(progress)
                orbSizeValue.text = "${petOrbSizeDp.roundToInt()}dp"
                BluefinStore.writeOrbSizeDp(prefs(), petOrbSizeDp)
                applyPetPrefs()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        about.text = getString(R.string.about, installedVersionName())
        view.findViewById<View>(R.id.reset_button).setOnClickListener {
            dialog.dismiss()
            confirmReset()
        }
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.layoutParams?.height = resources.displayMetrics.heightPixels
        }
        dialog.show()
        if (scrollToModel) {
            view.post {
                (view as? android.widget.ScrollView)?.smoothScrollTo(
                    0,
                    modelSection.top.coerceAtLeast(0),
                )
            }
        } else if (scrollToCharacter) {
            view.post {
                (view as? android.widget.ScrollView)?.smoothScrollTo(
                    0,
                    characterSectionFrame.top.coerceAtLeast(0),
                )
            }
        }
    }

    fun openModelSettings() {
        if (isFinishing || isDestroyed) return
        showSettings(scrollToModel = true)
    }

    fun openCharacterSettings() {
        if (isFinishing || isDestroyed) return
        showSettings(scrollToCharacter = true)
    }

    @Suppress("DEPRECATION")
    private fun installedVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName?.takeIf { it.isNotBlank() } ?: "?"

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_confirm) { _, _ -> performReset() }
            .show()
    }

    private fun performReset() {
        prefs().edit().putBoolean(KEY_TIPS_DONE, false).apply()
        if (!previewReady) {
            showTips()
            return
        }
        previewWeb.evaluateJavascript(RESET_JS) { raw ->
            if (raw?.contains("missing") == true) previewWeb.reload()
            showTips()
        }
    }

    private fun ensureForegroundService() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (granted == PackageManager.PERMISSION_GRANTED) {
                BluefinForegroundService.refresh(this)
            } else {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            BluefinForegroundService.refresh(this)
        }
    }

    private fun refreshFromPrefs() {
        soundOn = prefs().getBoolean(KEY_SOUND, true)
        roamOn = prefs().getBoolean(KEY_ROAM, true)
        petHidden = prefs().getBoolean(KEY_HIDDEN, false)
        petScale = BluefinStore.readScale(prefs())
        petOpacity = BluefinStore.readOpacity(prefs())
        petOrbSizeDp = BluefinStore.readOrbSizeDp(prefs())
    }

    private fun schedulePetPrefs() {
        if (!::binding.isInitialized) return
        previewWeb.removeCallbacks(applyPetFrame)
        previewWeb.postOnAnimation(applyPetFrame)
    }

    private fun applyPetPrefs() {
        if (!::binding.isInitialized) return
        val scale = String.format(Locale.US, "%.2f", BluefinStore.clampScale(petScale))
        val opacity = String.format(Locale.US, "%.2f", BluefinStore.clampOpacity(petOpacity))
        // A pack that declares can.walk=false never roams, but the saved global
        // preference is left untouched so it applies again on the next pack.
        val roam = roamOn && (currentManifest?.canWalk != false)
        previewWeb.evaluateJavascript(
            "window.__bluefinNative&&window.__bluefinNative.applyPrefs($roam,$scale,$petHidden,$opacity)",
            null,
        )
    }

    private fun applySound() {
        if (!::binding.isInitialized) return
        previewWeb.evaluateJavascript(
            "window.__bluefinApplySound&&window.__bluefinApplySound($soundOn)",
            null,
        )
        OverlayLauncher.sync(this)
    }

    private fun consumeOverlayIntent(source: Intent?) {
        if (source?.action == BluefinForegroundService.ACTION_RETURN) {
            source.action = null
            android.util.Log.i("BluefinOverlay", "notification return")
            return
        }
        if (source?.action != OverlayLauncher.ACTION_OVERLAY_GUIDE) return
        source.action = null
        pendingOverlayGuide = true
    }

    private fun consumeOpenModelIntent(source: Intent?) {
        val wantsModel = source?.action == ChatEntry.EXTRA_OPEN_MODEL_SETTINGS ||
            source?.getBooleanExtra(ChatEntry.EXTRA_OPEN_MODEL_SETTINGS, false) == true
        if (!wantsModel) return
        source?.action = null
        source?.removeExtra(ChatEntry.EXTRA_OPEN_MODEL_SETTINGS)
        if (!::binding.isInitialized) return
        binding.root.post { openModelSettings() }
    }

    private fun consumeOpenCharacterIntent(source: Intent?) {
        val wantsCharacter = source?.action == ChatEntry.EXTRA_OPEN_CHARACTER_SETTINGS ||
            source?.getBooleanExtra(ChatEntry.EXTRA_OPEN_CHARACTER_SETTINGS, false) == true
        if (!wantsCharacter) return
        source?.action = null
        source?.removeExtra(ChatEntry.EXTRA_OPEN_CHARACTER_SETTINGS)
        if (!::binding.isInitialized) return
        binding.root.post { openCharacterSettings() }
    }

    private fun showTips() {
        if (isFinishing || isDestroyed) return
        if (!binding.root.isAttachedToWindow) {
            binding.root.post { showTips() }
            return
        }
        dismissTips()
        tipIndex = 0
        val dialog = AppCompatDialog(this, R.style.Theme_Bluefin_TipsDialog)
        val content = dialog.layoutInflater.inflate(R.layout.dialog_tips, null)
        dialog.setContentView(content)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        content.findViewById<View>(R.id.tips_skip).setOnClickListener { finishTips() }
        content.findViewById<View>(R.id.tips_next).setOnClickListener { advanceTip() }
        applyTipsPlacement(content)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            )
            window.setDimAmount(TIPS_DIM_AMOUNT)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            window.setGravity(Gravity.TOP)
        }
        dialog.setOnShowListener {
            BluefinMotion.enter(content.findViewById(R.id.tips_card), y = -dp(8).toFloat())
            dialog.window?.let { window ->
                window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                )
                window.setDimAmount(TIPS_DIM_AMOUNT)
            }
        }
        dialog.setOnDismissListener {
            if (tipsDialog === dialog) {
                tipsDialog = null
                tipsView = null
            }
        }
        tipsDialog = dialog
        tipsView = content
        renderTip()
        dialog.show()
    }

    /** Keeps the card-height window just below the activity's top bar. */
    private fun applyTipsPlacement(view: View) {
        val density = resources.displayMetrics.density
        val horizontal = (12 * density).roundToInt()
        val topGap = (8 * density).roundToInt()
        view.setPadding(horizontal, 0, horizontal, 0)

        val place = Runnable { placeTipsBelowTopBar(view, topGap) }
        val schedulePlacement: () -> Unit = {
            view.removeCallbacks(place)
            view.post(place)
        }

        // The window itself wraps the card, so move the window to the measured
        // top-bar bottom instead of reserving that offset as top padding.
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                changedView: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (tipsView === changedView) schedulePlacement()
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(view) { content, insets ->
            if (tipsView === content) schedulePlacement()
            insets
        }
        ViewCompat.requestApplyInsets(view)
        schedulePlacement()
    }

    private fun placeTipsBelowTopBar(view: View, topGap: Int) {
        if (tipsView !== view) return
        val dialog = tipsDialog ?: return
        val window = dialog.window ?: return
        val topBar = binding.topBar
        if (!topBar.isAttachedToWindow || topBar.height <= 0) return
        val topBarLocation = IntArray(2)
        topBar.getLocationOnScreen(topBarLocation)
        val targetTop = topBarLocation[1] + topBar.height + topGap
        val windowLocation = IntArray(2)
        view.getLocationOnScreen(windowLocation)
        val delta = targetTop - windowLocation[1]
        if (delta != 0) {
            // The dialog window can start at the screen top or below the status
            // bar depending on the platform; correct against its measured spot.
            window.attributes = window.attributes.apply { y += delta }
        }
    }

    private fun renderTip() {
        val content = tipsView ?: return
        content.findViewById<TextView>(R.id.tips_step).text =
            getString(R.string.tip_step, tipIndex + 1, TIP_BODIES.size)
        content.findViewById<TextView>(R.id.tips_body).setText(TIP_BODIES[tipIndex])
        content.findViewById<TextView>(R.id.tips_next).setText(
            if (tipIndex == TIP_BODIES.lastIndex) R.string.tip_done else R.string.tip_next,
        )
    }

    private fun advanceTip() {
        if (tipIndex >= TIP_BODIES.lastIndex) finishTips() else {
            tipIndex += 1
            renderTip()
        }
    }

    private fun finishTips() {
        prefs().edit().putBoolean(KEY_TIPS_DONE, true).apply()
        dismissTips()
    }

    private fun dismissTips() {
        val dialog = tipsDialog ?: return
        tipsDialog = null
        tipsView = null
        dialog.dismiss()
    }

    private fun showStatus(message: String, error: Boolean) {
        if (!error && message.isEmpty()) {
            binding.statusOverlay.visibility = View.GONE
            return
        }
        binding.statusOverlay.visibility = View.VISIBLE
        binding.statusText.text = message
        binding.statusProgress.visibility = if (error) View.GONE else View.VISIBLE
        binding.statusRetry.visibility = if (error) View.VISIBLE else View.GONE
    }

    private fun missingMessage(path: String): String {
        return if (path == PreviewEntry.ROUTE_B) getString(R.string.missing_route_b)
        else getString(R.string.missing_asset, path)
    }

    private fun assetExists(path: String): Boolean {
        return try {
            assets.open(path).close()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val TAG = "Bluefin"
        const val BRIDGE_NAME = "BluefinNative"
        const val PREFS = BluefinStore.PREFS
        const val KEY_TIPS_DONE = BluefinStore.KEY_TIPS_DONE
        const val KEY_SOUND = BluefinStore.KEY_SOUND
        const val KEY_BUBBLE_PREVIEW = BluefinStore.KEY_BUBBLE_PREVIEW
        const val KEY_ROAM = BluefinStore.KEY_ROAM
        const val KEY_HIDDEN = BluefinStore.KEY_HIDDEN
        const val KEY_SIZE = BluefinStore.KEY_SIZE
        const val KEY_SCALE = BluefinStore.KEY_SCALE
        const val KEY_OPACITY = BluefinStore.KEY_OPACITY
        const val KEY_ORB_SIZE_DP = BluefinStore.KEY_ORB_SIZE_DP
        const val KEY_CHARACTER_ID = BluefinStore.KEY_CHARACTER_ID
        const val KEY_CHARACTER_SCHEME_PREFIX = BluefinStore.KEY_CHARACTER_SCHEME_PREFIX
        const val READY_JS =
            "(function(){return !!(window.__bluefinNative&&window.__bluefinNative.ready&&window.__bluefinNative.ready());})()"
        const val RESET_JS =
            "(function(){return window.__bluefinNative?String(window.__bluefinNative.reset()):'missing';})()"
        const val TIPS_DIM_AMOUNT = 0.08f
        val TIP_BODIES = intArrayOf(R.string.tip_1, R.string.tip_2, R.string.tip_3)
    }
}
