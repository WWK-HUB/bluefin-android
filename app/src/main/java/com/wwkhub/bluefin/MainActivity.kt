package com.wwkhub.bluefin

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
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
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wwkhub.bluefin.databinding.ActivityMainBinding
import com.wwkhub.bluefin.update.UpdateCoordinator
import com.wwkhub.bluefin.update.UpdateInstaller
import com.wwkhub.bluefin.update.UpdateManifest
import com.wwkhub.bluefin.voice.tts.CloudVoiceDeletion
import com.wwkhub.bluefin.voice.tts.TtsReader
import org.json.JSONObject
import java.io.File
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
    private var roamOn = true
    private var petHidden = false
    private var petScale = BluefinStore.SCALE_DEFAULT
    private var petOpacity = BluefinStore.OPACITY_DEFAULT
    private var pendingOverlayGuide = false
    private var settingsPanel: SettingsPanel? = null
    private var guide: WelcomeGuide? = null
    private var guideReturnsToSettings = false
    private var petSettings: PetSettingsSection? = null
    private var overlaySettingsListener: OverlayLauncher.Listener? = null
    private var notificationButton: View? = null
    private var updateCoordinator: UpdateCoordinator? = null
    private var updateDialog: AlertDialog? = null
    private var updateRetry: (() -> Unit)? = null
    private var pendingInstallFile: File? = null
    private val updateSourcePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingInstallFile != null) {
            if (UpdateInstaller.canRequestInstalls(this)) continueUpdateInstall()
            else clearPendingUpdate()
        }
    }
    private var currentAppearanceId = PackFormat.BUILT_IN_ID
    private var currentManifest: PackManifest? = null
    private var pendingImport: PackAnalysis? = null
    private var openCharacterSection: CharacterSection? = null
    private var speechSectionView: SpeechSettingsSection? = null
    private var voicePackImportCallback: (() -> Unit)? = null
    private var lastLoadedEntryKey: String? = null
    private var loadingEntry = false

    private val packZipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) beginPackImport(uri, folder = false)
    }

    private val packFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) beginPackImport(uri, folder = true)
    }

    private val voicePackCreator = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) onVoicePackChanged()
        speechSectionView?.refresh()
    }

    internal fun openVoicePackCreator() = voicePackCreator.launch(Intent(this, VoicePackCreatorActivity::class.java))

    private val voicePackPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) beginVoicePackImport(uri)
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
            BluefinStore.KEY_SPEECH_ON, BluefinStore.KEY_VOICE_PACK_ID -> {
                TtsReader.peek()?.stop()
            }
            KEY_CHARACTER_ID -> {
                refreshCharacter()
                petSettings?.refresh()
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
        refreshNotificationSetting()
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
        setupWebView()
        binding.openActions.setOnClickListener { showActions() }
        binding.openChat.setOnClickListener { ChatEntry.request(this) }
        binding.openOverlay.setOnClickListener { OverlayLauncher.toggle(this) }
        binding.openSettings.setOnClickListener { showSettings() }
        binding.statusRetry.setOnClickListener { loadEntry(force = true) }
        binding.openActions.isEnabled = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val panel = settingsPanel
                val tutorial = guide
                when {
                    panel != null -> panel.back()
                    tutorial != null -> tutorial.back()
                    previewWeb.canGoBack() -> previewWeb.goBack()
                    else -> finish()
                }
            }
        })

        loadEntry()
        restorePanels(savedInstanceState)
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
        CloudVoiceDeletion.resume(this)
        OverlayAppVisibility.setForeground(true)
        OverlayLauncher.setSettingsVisible(settingsPanel != null || guide != null)
        refreshNotificationSetting()
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
        OverlayLauncher.setSettingsVisible(false)
        if (::binding.isInitialized) previewWeb.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        dismissUpdateDialog()
        updateCoordinator?.cancel()
        clearPendingUpdate()
        previewWeb.removeCallbacks(applyPetFrame)
        pollGeneration++
        pendingImport?.cleanup()
        pendingImport = null
        disposeSettingsSections()
        voicePackImportCallback = null
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

    /**
     * On-demand ZipVoice fallback flow (consent → download → verification) in
     * a dedicated activity so it is visible over any screen. Offered only
     * after persistent cloud failure or from the offline settings; nothing
     * downloads without explicit consent and the engine never ships in the APK.
     */
    fun promptFallbackDownload(onReady: () -> Unit = {}) {
        if (isFinishing || isDestroyed) return
        FallbackSetupActivity.launch(this) { ok ->
            if (ok) onReady()
            speechSectionView?.refresh()
        }
    }

    fun promptVoicePackImport(onDone: () -> Unit = {}) {
        if (isFinishing || isDestroyed) return
        voicePackImportCallback = onDone
        voicePackPicker.launch(
            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"),
        )
    }

    private fun beginVoicePackImport(uri: android.net.Uri) {
        Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
        thread(name = "bluefin-voice-pack-import") {
            try {
                val (pack, work) = VoicePackRepository.analyzeZip(this, uri)
                val installed = runCatching { VoicePackRepository.install(this, work) }.getOrElse {
                    (it as? VoicePackException)?.let { error ->
                        throw error
                    }
                    throw VoicePackException(VoicePackReject.BAD_JSON, it.message.orEmpty())
                }
                VoicePackRepository.setCurrent(this, installed.id)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(
                        this,
                        getString(R.string.speech_voice_status, pack.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onVoicePackChanged()
                    speechSectionView?.refresh()
                    voicePackImportCallback?.invoke()
                    voicePackImportCallback = null
                }
            } catch (error: VoicePackException) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val reason = voicePackProblem(error)
                    Toast.makeText(
                        this,
                        getString(R.string.speech_voice_import_failed, reason),
                        Toast.LENGTH_LONG,
                    ).show()
                    voicePackImportCallback?.invoke()
                    voicePackImportCallback = null
                }
            }
        }
    }

    private fun voicePackProblem(error: VoicePackException): String =
        voicePackProblem(this, error)

    /** Called when the朗读 switch or engine state changed in settings. */
    fun onSpeechPreferenceChanged() {
        TtsReader.peek()?.stop()
    }

    /** Called after the timbre changed; the next reply uses it. */
    fun onVoicePackChanged() {
        TtsReader.peek()?.stop()
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
    }

    private fun showActions() {
        if (!previewReady) return
        val appearance = PackRepository.current(this)
        val sections = ActionVocab.of(appearance.manifest)
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
        fillActionGrid(view.findViewById(R.id.actions_motions), sections.motions, ::pick)
        fillActionGrid(view.findViewById(R.id.actions_expressions), sections.expressions, ::pick)
        view.findViewById<View>(R.id.actions_motions_header).visibility =
            if (sections.motions.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.actions_expressions_header).visibility =
            if (sections.expressions.isEmpty()) View.GONE else View.VISIBLE
        if (sections.isEmpty) {
            val empty = TextView(this).apply {
                text = getString(R.string.import_no_actions)
                setTextColor(getColor(R.color.muted))
                textSize = 13f
                setPadding(0, dp(12), 0, dp(12))
            }
            (view.findViewById<View>(R.id.actions_motions).parent as? android.view.ViewGroup)
                ?.addView(empty)
        }
        dialog.setContentView(view)
        dialog.show()
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

    private fun showSettings(page: SettingsPage = SettingsPage.HOME) {
        val existing = settingsPanel
        val panel = existing ?: SettingsPanel(this, ::closeSettings, ::buildSettingsPage, ::openSettingsLink)
        panel.fromGuide = guide != null
        if (existing == null) {
            settingsPanel = panel
            binding.root.addView(panel.view, FrameLayout.LayoutParams(-1, -1))
        }
        panel.show(page)
        syncPanels()
    }

    private fun closeSettings() {
        val panel = settingsPanel ?: return
        panel.hideKeyboard()
        binding.root.removeView(panel.view)
        settingsPanel = null
        disposeSettingsSections()
        syncPanels()
    }

    private fun disposeSettingsSections() {
        openCharacterSection = null
        speechSectionView?.dispose()
        speechSectionView = null
        petSettings?.dispose()
        petSettings = null
        overlaySettingsListener?.let(OverlayLauncher::removeListener)
        overlaySettingsListener = null
        notificationButton = null
    }

    private fun buildSettingsPage(page: SettingsPage, body: LinearLayout) {
        val panel = settingsPanel?.view ?: return
        val content = FrameLayout(this).apply {
            background = panel.card()
            setPadding(dp(18), dp(12), dp(18), dp(14))
        }
        body.addView(content, LinearLayout.LayoutParams(-1, -2))
        when (page) {
            SettingsPage.OVERLAY -> bindOverlaySettings(layoutInflater.inflate(R.layout.page_overlay_settings, content, true))
            SettingsPage.PET -> {
                layoutInflater.inflate(R.layout.page_pet_settings, content, true)
                petSettings = PetSettingsSection(content, prefs()) { currentManifest?.canWalk != false }
                content.findViewById<View>(R.id.reset_button).setOnClickListener { confirmReset() }
            }
            SettingsPage.CHARACTER -> openCharacterSection = CharacterSection(this, content)
            SettingsPage.MODEL -> {
                layoutInflater.inflate(R.layout.section_model_settings, content, true)
                ModelSettingsSection(this, content) { ChatEntry.request(this) }
            }
            SettingsPage.PERSONA -> {
                layoutInflater.inflate(R.layout.section_persona_settings, content, true)
                PersonaEditorSection(this, content)
            }
            SettingsPage.VOICE -> {
                layoutInflater.inflate(R.layout.section_voice_settings, content, true)
                VoiceSettingsSection(this, content)
            }
            SettingsPage.SPEECH -> speechSectionView = SpeechSettingsSection(this, content)
            SettingsPage.ABOUT -> {
                layoutInflater.inflate(R.layout.page_about_settings, content, true)
                bindAboutSettings(content)
            }
            else -> Unit
        }
    }

    private fun bindOverlaySettings(view: View) {
        val overlay = view.findViewById<CompoundButton>(R.id.overlay_switch)
        val bubble = view.findViewById<CompoundButton>(R.id.bubble_preview_switch)
        var syncing = false
        val listener = OverlayLauncher.Listener { showing ->
            syncing = true
            overlay.isChecked = showing
            syncing = false
        }
        overlay.isChecked = OverlayLauncher.isShowing()
        overlay.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            if (!checked) {
                OverlayLauncher.hide(this)
            } else if (!Settings.canDrawOverlays(this)) {
                listener.onOverlayVisibilityChanged(false)
                OverlayLauncher.showGuide(this, force = true)
            } else if (!OverlayLauncher.show(this)) {
                listener.onOverlayVisibilityChanged(false)
            }
        }
        overlaySettingsListener = listener
        OverlayLauncher.addListener(listener)
        bubble.isChecked = BluefinStore.readBubblePreview(prefs())
        bubble.setOnCheckedChangeListener { _, checked -> BluefinStore.writeBubblePreview(prefs(), checked) }
        view.findViewById<View>(R.id.overlay_prompt_reset).setOnClickListener { OverlayLauncher.resetPrompt(this) }
        notificationButton = view.findViewById<View>(R.id.settings_notification).apply {
            setOnClickListener { requestNotifications() }
        }
        refreshNotificationSetting()
    }

    private fun bindAboutSettings(view: View) {
        view.findViewById<TextView>(R.id.about_version).text = getString(R.string.about_version, installedVersionName())
        view.findViewById<View>(R.id.about_licenses).setOnClickListener {
            showAboutDetail(R.string.about_licenses_entry, R.string.about_licenses)
        }
        view.findViewById<View>(R.id.about_source).setOnClickListener {
            showAboutDetail(R.string.about_source_entry, R.string.about_source)
        }
        view.findViewById<View>(R.id.about_privacy).setOnClickListener {
            showAboutDetail(R.string.about_privacy_entry, R.string.about_privacy)
        }
        view.findViewById<View>(R.id.about_check_update).setOnClickListener { startUpdateCheck() }
    }

    private fun openSettingsLink(page: SettingsPage) {
        when (page) {
            SettingsPage.SCREEN -> startActivity(ScreenAwarenessActivity.settingsIntent(this))
            SettingsPage.GUIDE -> {
                closeSettings()
                showGuide(0, returnToSettings = true)
            }
            else -> showSettings(page)
        }
    }

    fun openModelSettings() {
        if (!isFinishing && !isDestroyed) showSettings(SettingsPage.MODEL)
    }

    fun openCharacterSettings() {
        if (!isFinishing && !isDestroyed) showSettings(SettingsPage.CHARACTER)
    }

    @Suppress("DEPRECATION")
    private fun installedVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName?.takeIf { it.isNotBlank() } ?: "?"

    /** About keeps only the version and entries on the first screen; the full
     *  licenses, origin and privacy notes open on demand. */
    private fun showAboutDetail(titleRes: Int, bodyRes: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- in-app update: check → download → verify → system installer ----

    private fun coordinator(): UpdateCoordinator = updateCoordinator ?: UpdateCoordinator(
        this,
        object : UpdateCoordinator.Listener {
            override fun onChecking(sourceLabel: String) = updateUi {
                showUpdateDialog(
                    getString(R.string.update_check_entry),
                    getString(R.string.update_checking, sourceLabel),
                )
            }

            override fun onAvailable(manifest: UpdateManifest, sourceLabel: String) = updateUi {
                dismissUpdateDialog()
                val size = manifest.sizeBytes
                    ?.let { getString(R.string.update_size_mb, it / 1048576.0) }
                    ?: getString(R.string.update_size_unknown)
                val body = getString(
                    R.string.update_available_message,
                    manifest.notes ?: getString(R.string.update_no_notes),
                    size,
                    sourceLabel,
                )
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.update_available_title, manifest.versionName))
                    .setMessage(
                        if (sourceLabel == "GitHub") body + "\n\n" + getString(R.string.update_github_hint)
                        else body,
                    )
                    .setPositiveButton(R.string.update_download_install) { _, _ ->
                        startUpdateDownload(manifest)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            override fun onUpToDate() = updateUi {
                dismissUpdateDialog()
                Toast.makeText(this@MainActivity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
            }

            override fun onProgress(read: Long, total: Long?) = updateUi {
                val message = if (total != null && total > 0) {
                    getString(R.string.update_downloading, (read * 100 / total).toInt())
                } else {
                    getString(R.string.update_downloading_unknown)
                }
                updateDialog?.setMessage(message)
            }

            override fun onVerifying() = updateUi {
                updateDialog?.setMessage(getString(R.string.update_verifying))
            }

            override fun onReadyToInstall(file: File, manifest: UpdateManifest) = updateUi {
                dismissUpdateDialog()
                pendingInstallFile = file
                continueUpdateInstall()
            }

            override fun onFailure(message: String) = updateUi {
                dismissUpdateDialog()
                val githubConfigured = listOf(
                    BuildConfig.UPDATE_PRIMARY_MANIFEST_URL,
                    BuildConfig.UPDATE_FALLBACK_MANIFEST_URL,
                ).any { it.contains("github.com") }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.update_failed_title)
                    .setMessage(
                        if (githubConfigured) message + "\n\n" + getString(R.string.update_github_hint)
                        else message,
                    )
                    .setPositiveButton(R.string.update_retry) { _, _ -> updateRetry?.invoke() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            override fun onCancelled() = updateUi { dismissUpdateDialog() }
        },
    ).also { updateCoordinator = it }

    private fun updateUi(block: () -> Unit) {
        if (!isFinishing && !isDestroyed) block()
    }

    private fun showUpdateDialog(title: String, message: String) {
        dismissUpdateDialog()
        updateDialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel) { _, _ -> coordinator().cancel() }
            .setCancelable(false)
            .show()
    }

    private fun dismissUpdateDialog() {
        updateDialog?.dismiss()
        updateDialog = null
    }

    private fun startUpdateCheck() {
        if (coordinator().check()) updateRetry = { startUpdateCheck() }
    }

    private fun startUpdateDownload(manifest: UpdateManifest) {
        if (!coordinator().download(manifest)) return
        updateRetry = { startUpdateDownload(manifest) }
        showUpdateDialog(
            getString(R.string.update_download_install),
            getString(R.string.update_downloading_unknown),
        )
    }

    private fun clearPendingUpdate() {
        pendingInstallFile?.delete()
        pendingInstallFile = null
    }

    private fun showInstallLaunchFailure() {
        clearPendingUpdate()
        Toast.makeText(this, R.string.update_install_launch_failed, Toast.LENGTH_LONG).show()
    }

    /** The system installer always confirms; unknown-sources permission first. */
    private fun continueUpdateInstall() {
        val file = pendingInstallFile ?: return
        if (!file.isFile) {
            showInstallLaunchFailure()
            return
        }
        if (!UpdateInstaller.canRequestInstalls(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_install_unknown_title)
                .setMessage(R.string.update_install_unknown_message)
                .setPositiveButton(R.string.update_install_unknown_go) { _, _ ->
                    try {
                        updateSourcePermission.launch(UpdateInstaller.unknownSourcesIntent(this))
                    } catch (error: android.content.ActivityNotFoundException) {
                        showInstallLaunchFailure()
                    } catch (error: SecurityException) {
                        showInstallLaunchFailure()
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> clearPendingUpdate() }
                .setOnCancelListener { clearPendingUpdate() }
                .show()
            return
        }
        try {
            startActivity(UpdateInstaller.installIntent(this, file))
            pendingInstallFile = null
        } catch (error: android.content.ActivityNotFoundException) {
            showInstallLaunchFailure()
        } catch (error: SecurityException) {
            showInstallLaunchFailure()
        } catch (error: IllegalArgumentException) {
            showInstallLaunchFailure()
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_confirm) { _, _ -> performReset() }
            .show()
    }

    private fun performReset() {
        if (!previewReady) {
            Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show()
            return
        }
        previewWeb.evaluateJavascript(RESET_JS) { raw ->
            if (raw?.contains("missing") == true) previewWeb.reload()
        }
        closeSettings()
    }

    private fun refreshNotificationSetting() {
        notificationButton?.visibility = if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) View.VISIBLE else View.GONE
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_notifications)
            .setMessage(R.string.settings_notifications_help)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (prefs().getBoolean(KEY_NOTIFICATION_REQUESTED, false) &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                } else {
                    prefs().edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply()
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .show()
    }

    private fun refreshFromPrefs() {
        soundOn = prefs().getBoolean(KEY_SOUND, true)
        roamOn = prefs().getBoolean(KEY_ROAM, true)
        petHidden = prefs().getBoolean(KEY_HIDDEN, false)
        petScale = BluefinStore.readScale(prefs())
        petOpacity = BluefinStore.readOpacity(prefs())
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

    override fun onSaveInstanceState(outState: Bundle) {
        settingsPanel?.let { outState.putString(STATE_SETTINGS_PAGE, it.page.name) }
        guide?.let { outState.putInt(STATE_GUIDE, it.index) }
        outState.putBoolean(STATE_GUIDE_RETURN, guideReturnsToSettings)
        super.onSaveInstanceState(outState)
    }

    private fun restorePanels(state: Bundle?) {
        val savedStep = state?.getInt(STATE_GUIDE, -1) ?: -1
        if (savedStep >= 0) {
            showGuide(savedStep, state?.getBoolean(STATE_GUIDE_RETURN) == true)
        } else if (!prefs().getBoolean(KEY_TIPS_DONE, false)) {
            showGuide(prefs().getInt(KEY_GUIDE_STEP, 0))
        }
        val savedPage = state?.getString(STATE_SETTINGS_PAGE)
        SettingsPage.entries.firstOrNull { it.name == savedPage }?.let(::showSettings)
    }

    private fun showGuide(start: Int, returnToSettings: Boolean = false) {
        val tutorial = WelcomeGuide(
            this, start,
            onProgress = { prefs().edit().putInt(KEY_GUIDE_STEP, it).apply() },
            open = ::openSettingsLink,
            finish = ::completeGuide,
        )
        guide = tutorial
        guideReturnsToSettings = returnToSettings
        binding.root.addView(tutorial.view, FrameLayout.LayoutParams(-1, -1))
        syncPanels()
    }

    private fun completeGuide() {
        val tutorial = guide ?: return
        prefs().edit().putBoolean(KEY_TIPS_DONE, true).remove(KEY_GUIDE_STEP).apply()
        binding.root.removeView(tutorial.view)
        guide = null
        val returnToSettings = guideReturnsToSettings
        guideReturnsToSettings = false
        if (returnToSettings) showSettings() else syncPanels()
    }

    private fun syncPanels() {
        val settings = settingsPanel?.view
        val tutorial = guide?.view
        tutorial?.visibility = if (settings == null) View.VISIBLE else View.GONE
        val activePanel = settings ?: tutorial
        binding.root.children.forEach { view ->
            view.importantForAccessibility = if (activePanel == null || view === activePanel) {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            } else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        activePanel?.let { ViewCompat.requestApplyInsets(it) }
        OverlayLauncher.setSettingsVisible(activePanel != null)
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
        const val KEY_GUIDE_STEP = "guide_step"
        const val KEY_NOTIFICATION_REQUESTED = "notification_requested"
        const val STATE_SETTINGS_PAGE = "settings_page"
        const val STATE_GUIDE = "guide_page"
        const val STATE_GUIDE_RETURN = "guide_return"
        const val KEY_SOUND = BluefinStore.KEY_SOUND
        const val KEY_ROAM = BluefinStore.KEY_ROAM
        const val KEY_HIDDEN = BluefinStore.KEY_HIDDEN
        const val KEY_SIZE = BluefinStore.KEY_SIZE
        const val KEY_SCALE = BluefinStore.KEY_SCALE
        const val KEY_OPACITY = BluefinStore.KEY_OPACITY
        const val KEY_CHARACTER_ID = BluefinStore.KEY_CHARACTER_ID
        const val KEY_CHARACTER_SCHEME_PREFIX = BluefinStore.KEY_CHARACTER_SCHEME_PREFIX
        const val READY_JS =
            "(function(){return !!(window.__bluefinNative&&window.__bluefinNative.ready&&window.__bluefinNative.ready());})()"
        const val RESET_JS =
            "(function(){return window.__bluefinNative?String(window.__bluefinNative.reset()):'missing';})()"
    }
}
