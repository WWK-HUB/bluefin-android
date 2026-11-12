package com.wwkhub.bluefin.bubblepreview

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.wwkhub.bluefin.BluefinStore
import com.wwkhub.bluefin.ChatFailureText
import com.wwkhub.bluefin.OverlayAppVisibility
import com.wwkhub.bluefin.PackBubbleColors
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Host-facing lifecycle for the optional preview.
 *
 * The controller is constructed only after the existing overlay panel accepted
 * a user send (`STARTED`) and the compile gate + persisted switch are on. It
 * holds no host size callback: the host hands it immutable frames already in
 * screen pixels. Every platform operation goes through [BubblePreviewBoundary].
 */
internal interface BubblePreviewHostActions {
    fun onBubbleAttached()
    fun onBubbleRecordRequested()
    fun onBubbleAttemptFailed()
    fun onBubbleStopRequested()
    fun onBubbleNoSpace()
    fun onBubbleRoundFinished(token: Long, reason: BubbleSuppressionReason)
}

/**
 * One immutable view of a ChatSession snapshot, reduced by the host to only the
 * current accepted round. The controller never retains history, whole user text
 * or a whole assistant snapshot: [cumulativeAssistant] is consumed and copied
 * into the bounded text state for the duration of the call.
 */
internal data class BubblePreviewChatSignal(
    val token: Long,
    val requestGeneration: Int,
    val cumulativeAssistant: String,
    val terminal: Boolean,
    val stopped: Boolean,
    val failed: Boolean,
    val safeFailureText: String?,
)

internal class BubblePreviewController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val hostGeneration: Long,
    private val circuit: BubblePreviewFailureCircuit,
    private val prefs: SharedPreferences,
    private val characterScale: Float,
    private val characterName: String,
    private val actions: BubblePreviewHostActions,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density.coerceAtLeast(0.5f)
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
        .takeIf { it.isFinite() && it > 0f } ?: density
    private val boundary = BubblePreviewBoundary { fault ->
        counters.failure()
        Log.w(
            TAG,
            "optional bubble fault op=${fault.operation} type=${fault.exceptionType} " +
                "counted=${fault.countedAgainstAttempt}",
        )
    }
    private val counters = BubblePreviewCounters()
    private val updateGate = BubblePreviewUpdateGate(
        minimumDeltaPx = ceil(MINIMUM_UPDATE_DELTA_DP * density).toInt().coerceAtLeast(1),
    )
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SANS_SERIF
    }
    // Paint metrics do not depend on the streaming text, so they are measured
    // once per controller (one content/scale epoch), never per WM layout frame
    // or per revealed character.
    private val measuredActionWidths: Map<BubblePreviewAction, Float> by lazy(LazyThreadSafetyMode.NONE) {
        measureActionTextWidths()
    }
    private val measuredBodyLinePx: Float by lazy(LazyThreadSafetyMode.NONE) {
        measureLinePx(BubblePreviewScalePolicy.bodyTextSp(characterScale))
    }
    private val measuredNameLinePx: Float by lazy(LazyThreadSafetyMode.NONE) {
        measureLinePx(BubblePreviewScalePolicy.labelTextSp(characterScale))
    }

    private var turnState: BubblePreviewTurnState? = null
    private var token: Long? = null
    private var requestGeneration: Int = -1
    private var roleName: String = characterName.trim().ifBlank { "蓝鳍" }
    private var packBubbleColors: PackBubbleColors? = null
    private var failureText: String = ""
    private var stopRequested = false
    private var stopFlushed = false
    private var roundFinishedNotified = false

    private var attempt: BubblePreviewDisplayAttempt? = null
    private var window: BubblePreviewWindow? = null
    private var orphanWindow: BubblePreviewWindow? = null
    private var lastFrame: BubblePreviewHostFrame? = null
    private var lastSnapshot: BubbleTurnSnapshot? = null
    private var placement: BubblePreviewPlacement? = null
    private var lastPlan: BubblePreviewLayoutPlan? = null
    private var pendingSide: BubblePreviewPendingSide? = null
    private var geometryRetryAtMs = 0L
    private var geometryRetryPending = false
    private var pendingUpdatePlacement: BubblePreviewPlacement? = null
    private var pendingUpdateRunnable: Runnable? = null
    private var sustainedRunnable: Runnable? = null
    private var tickRunnable: Runnable? = null
    private var deadlineRunnable: Runnable? = null
    private var scheduledDeadlineAtMs: Long = -1L
    private var destroyed = false
    private var failedRound = false
    private var noSpaceRecorded = false
    private var unresolvedCleanup = false
    private var roundGeneration = 0L

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BluefinStore.KEY_BUBBLE_PREVIEW) {
            val generation = roundGeneration
            // Capture the OFF value inside this notification boundary. Re-reading
            // the preference inside a posted body would lose an OFF immediately
            // followed by ON; a later ON must never revive the suppressed round.
            val enabled = try {
                BluefinStore.readBubblePreview(prefs)
            } catch (error: Exception) {
                reportFault(BubblePreviewOperation.LISTENER, error)
                false
            }
            val change = BubblePreviewPreferenceChange(generation, enabled)
            if (!BubblePreviewPreferencePolicy.shouldDismiss(change, generation)) return@OnSharedPreferenceChangeListener
            val dismiss = {
                if (BubblePreviewPreferencePolicy.shouldDismiss(change, roundGeneration)) {
                    dismissInternal(clearRound = true, BubbleSuppressionReason.PREF_OFF)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runContained(BubblePreviewOperation.LISTENER, dismiss)
            } else {
                try {
                    handler.post { runContained(BubblePreviewOperation.LISTENER, dismiss) }
                } catch (error: Exception) {
                    Log.w(TAG, "bubble preference post contained type=${error.javaClass.name}")
                }
            }
        }
    }

    private val appVisibilityListener = OverlayAppVisibility.Listener { foreground ->
        if (foreground) {
            runContained(BubblePreviewOperation.LISTENER) {
                dismissInternal(clearRound = true, BubbleSuppressionReason.APP_FOREGROUND)
            }
        }
    }

    init {
        try {
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        } catch (error: Exception) {
            reportFault(BubblePreviewOperation.LISTENER, error)
            destroyed = true
        }
        try {
            OverlayAppVisibility.addListener(appVisibilityListener)
        } catch (error: Exception) {
            reportFault(BubblePreviewOperation.LISTENER, error)
            destroyed = true
        }
        if (destroyed) {
            runContained(BubblePreviewOperation.LISTENER) {
                prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            }
            runContained(BubblePreviewOperation.LISTENER) {
                OverlayAppVisibility.removeListener(appVisibilityListener)
            }
        }
    }

    /**
     * Starts one explicit display round. Returns true only when the window was
     * actually added now; a false result leaves the existing record panel in
     * place and waits for a later valid layout frame.
     */
    fun showStarted(
        token: Long,
        userSummary: String,
        acceptedRoleName: String,
        acceptedRequestGeneration: Int,
        packColors: PackBubbleColors? = null,
        attemptToken: BubblePreviewDisplayAttempt? = null,
    ): Boolean = contain(
        BubblePreviewOperation.CREATE,
        token = null,
        onFault = { false },
    ) {
        if (destroyed) return@contain false
        if (unresolvedCleanup || orphanWindow != null) return@contain false
        if (BubblePreviewProcessSession.isBlocked()) return@contain false
        if (!BubblePreviewModule.isEnabled()) return@contain false
        if (!BluefinStore.readBubblePreview(prefs)) return@contain false
        if (circuit.isTripped()) return@contain false

        // A new explicit STARTED replaces the previous desktop round. Retire it
        // first so its window, timers and listener identity cannot leak into the
        // new token. The host has already moved its own signal token forward.
        retireCurrentRoundForReplacement()
        this.token = token
        this.requestGeneration = acceptedRequestGeneration
        this.roleName = acceptedRoleName.trim().ifBlank { "蓝鳍" }
        this.packBubbleColors = packColors
        failureText = ""
        stopRequested = false
        stopFlushed = false
        roundFinishedNotified = false
        failedRound = false
        noSpaceRecorded = false
        roundGeneration += 1
        val state = turnState ?: BubblePreviewTurnState().also { turnState = it }
        lastSnapshot = state.beginTurn(token, userSummary, this.roleName, now())
        lastPlan = null
        // The host may have started the one explicit attempt before reading the
        // optional display basis so constructor/metrics/resource/attach/later
        // update failures all share one non-duplicating token.
        attempt = attemptToken ?: circuit.beginDisplayAttempt(now())
        val frame = lastFrame
        if (frame != null) tryAttach(frame, lastSnapshot!!) else false
    }

    /** Feeds one cumulative snapshot of the accepted current round. */
    fun onChatSignal(signal: BubblePreviewChatSignal) = contain(
        BubblePreviewOperation.SNAPSHOT,
    ) {
        if (destroyed) return@contain
        val currentToken = token ?: return@contain
        if (signal.token != currentToken) return@contain
        if (signal.requestGeneration != requestGeneration) {
            // Clear history, a new in-app send, or same-index same-text replay
            // all move the existing generation. The old desktop token must die.
            dismissInternal(clearRound = true, BubbleSuppressionReason.CLEAR)
            return@contain
        }
        val state = turnState ?: return@contain
        if (signal.failed) {
            failureText = if (signal.safeFailureText.isNullOrBlank()) {
                "模型没有返回内容，请稍后再试。"
            } else {
                ChatFailureText.forBubble(signal.safeFailureText)
            }
        }
        val now = now()
        val snapshot = when {
            signal.failed -> state.onError(currentToken, signal.cumulativeAssistant, now)
            signal.stopped && !stopFlushed -> {
                stopFlushed = true
                state.onStop(currentToken, signal.cumulativeAssistant, now)
            }
            else -> state.onSnapshot(
                currentToken,
                signal.cumulativeAssistant,
                signal.terminal,
                now,
            )
        }
        render(snapshot, lastFrame)
    }

    fun onHostLayout(frame: BubblePreviewHostFrame) = contain(
        BubblePreviewOperation.RENDER,
    ) {
        if (destroyed) return@contain
        counters.callbackDelivered()
        lastFrame = frame
        if (frame.hostGeneration != hostGeneration) return@contain
        val snapshot = lastSnapshot ?: run {
            if (window != null) removeWindow()
            return@contain
        }
        if (snapshot.suppressed || token == null) {
            if (window != null) removeWindow()
            return@contain
        }

        if (frame.clickThrough) {
            dismissInternal(clearRound = true, BubbleSuppressionReason.CLICK_THROUGH)
            return@contain
        }
        if (frame.hidden || frame.opacity <= 0f) {
            dismissInternal(clearRound = true, BubbleSuppressionReason.PREF_OFF)
            return@contain
        }
        if (!frame.stageAttached || !frame.pageReady) {
            suspendForGeometry(frame.sampledAtElapsedMs)
            return@contain
        }
        if (frame.geometry == null || !frame.geometry.isUsable() ||
            !frame.geometry.hasCharacterAnchor()
        ) {
            // Missing trustworthy geometry is a visibility condition, never a
            // local failure count. Keep the current round suspended until a
            // later valid snapshot arrives.
            suspendForGeometry(frame.sampledAtElapsedMs)
            return@contain
        }
        if (geometryRetryPending && now() < geometryRetryAtMs) return@contain

        if (snapshot.geometrySuspended) {
            val state = turnState ?: return@contain
            lastSnapshot = state.setGeometryAvailable(true, now())
        }
        val resumed = lastSnapshot ?: return@contain
        val planAndPlacement = computePlacement(frame, resumed) ?: run {
            if (!noSpaceRecorded) {
                noSpaceRecorded = true
                Log.i(
                    TAG,
                    "bubble no-space hostGen=$hostGeneration geom=${frame.geometryGeneration}",
                )
            }
            if (window != null) removeWindow()
            notifyNoSpace()
            return@contain
        }
        if (window == null) {
            tryAttach(frame, resumed)
            afterSnapshot(resumed)
            return@contain
        }
        if (planAndPlacement.first != lastPlan) {
            applyWindowState(resumed, planAndPlacement.first, planAndPlacement.second)
        } else {
            requestUpdate(frame, planAndPlacement)
            window?.updateText(resumed, failureTextFor(snapshot = resumed))
        }
        afterSnapshot(resumed)
    }

    /**
     * Host metrics/snapshot/resource failure outside the controller's normal
     * boundary. It reuses the one active display attempt, so a constructor,
     * preflight or later snapshot exception can never count as a second
     * independent gesture.
     */
    fun onHostOptionalFailure() = contain(
        BubblePreviewOperation.SNAPSHOT,
    ) {
        markCurrentRoundFailed()
    }

    fun onHostConfigurationChanged() = contain(
        BubblePreviewOperation.SCHEDULED,
    ) {
        if (destroyed) return@contain
        if (lastSnapshot?.suppressed == true || token == null) return@contain
        removeWindow()
        // The old frame carried the previous display/bar/IME epoch. Do not let
        // the coalesced retry reattach a window from stale safe geometry; wait
        // for the next host frame after the invalidation.
        lastFrame = null
        updateGate.reset()
        placement = null
        lastPlan = null
        pendingSide = null
        pendingUpdatePlacement = null
        cancelPendingUpdate()
        noSpaceRecorded = false
        geometryRetryAtMs = now() + GEOMETRY_COALESCE_MS
        val state = turnState ?: return@contain
        lastSnapshot = state.setGeometryAvailable(false, now())
        if (!geometryRetryPending) {
            val generation = roundGeneration
            geometryRetryPending = true
            counters.taskScheduled()
            val runnable = Runnable {
                geometryRetryPending = false
                if (generation != roundGeneration) return@Runnable
                val frame = lastFrame ?: return@Runnable
                runContained(BubblePreviewOperation.SCHEDULED) {
                    if (generation == roundGeneration && now() >= geometryRetryAtMs) {
                        val latest = lastSnapshot ?: return@runContained
                        val plan = computePlacement(frame, latest)
                        if (plan != null) {
                            val resumed = turnState?.setGeometryAvailable(true, now())
                            if (resumed != null) {
                                lastSnapshot = resumed
                                tryAttach(frame, resumed)
                            }
                        }
                    }
                }
            }
            cancelGeometryRetry()
            geometryRetryRunnable = runnable
            handler.postDelayed(runnable, GEOMETRY_COALESCE_MS)
        }
    }

    fun onClickThroughEnabled() = contain(
        BubblePreviewOperation.LISTENER,
    ) {
        dismissInternal(clearRound = true, BubbleSuppressionReason.CLICK_THROUGH)
    }

    fun onHostVisibilityChanged(hidden: Boolean, opacity: Float) = contain(
        BubblePreviewOperation.LISTENER,
    ) {
        if (hidden || !opacity.isFinite() || opacity <= 0f) {
            dismissInternal(clearRound = true, BubbleSuppressionReason.PREF_OFF)
        }
    }

    fun onHostPageReset() = contain(
        BubblePreviewOperation.LISTENER,
    ) {
        dismissInternal(clearRound = true, BubbleSuppressionReason.PANEL)
    }

    fun dismissForHostUi() = contain(
        BubblePreviewOperation.LISTENER,
    ) {
        dismissInternal(clearRound = true, BubbleSuppressionReason.PANEL)
    }

    /** Stop action: forwards to the single session; never sends or retries. */
    fun requestStop() = contain(
        BubblePreviewOperation.LISTENER,
    ) {
        if (destroyed || token == null) return@contain
        if (!stopRequested) {
            stopRequested = true
            actions.onBubbleStopRequested()
        }
    }

    fun close() {
        if (destroyed) return
        destroyed = true
        roundGeneration += 1
        cancelGeometryRetry()
        runContained(BubblePreviewOperation.LISTENER) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        runContained(BubblePreviewOperation.LISTENER) {
            OverlayAppVisibility.removeListener(appVisibilityListener)
        }
        cancelPendingUpdate()
        cancelSustained()
        cancelTick()
        cancelDeadline()
        runContained(BubblePreviewOperation.CLEANUP) {
            attempt?.endAttempt(now())
        }
        attempt = null
        turnState?.let { state ->
            val current = token
            if (current != null) state.suppress(BubbleSuppressionReason.DISMISS, now())
        }
        token = null
        lastSnapshot = null
        removeWindow()
    }

    fun hasUnresolvedCleanup(): Boolean = unresolvedCleanup

    fun counterSnapshot(): BubblePreviewCounterSnapshot = counters.snapshot()

    private fun expandCollapsed(snapshot: BubbleTurnSnapshot) = contain(
        BubblePreviewOperation.RENDER,
    ) {
        val currentToken = token ?: return@contain
        if (snapshot.phase != BubbleTurnPhase.COMPLETE &&
            snapshot.phase != BubbleTurnPhase.STOPPED
        ) return@contain
        val state = turnState ?: return@contain
        val expanded = state.onCollapsedClick(currentToken, now())
        lastSnapshot = expanded
        render(expanded, lastFrame)
    }

    private fun tryAttach(frame: BubblePreviewHostFrame, snapshot: BubbleTurnSnapshot): Boolean {
        if (destroyed || failedRound || window != null) return false
        if (unresolvedCleanup || orphanWindow != null) return false
        if (!isEligible(frame)) return false
        if (!snapshot.isVisible || snapshot.geometrySuspended) return false
        val compute = computePlacement(frame, snapshot) ?: return false
        val windowPlan = compute.first
        val windowPlacement = compute.second
        val geometry = frame.geometry ?: return false

        val created = boundary.run(BubblePreviewOperation.CREATE, attempt, now()) {
            BubblePreviewWindow(
                context = context,
                windowManager = windowManager,
                initialContent = snapshot,
                initialFailureText = failureTextFor(snapshot),
                initialPlan = windowPlan,
                placement = windowPlacement,
                geometry = geometry,
                packBubbleColors = packBubbleColors,
                initialAnchor = frame.geometry?.resolvedAnchor(),
                onRecord = {
                    runContained(BubblePreviewOperation.LISTENER) {
                        actions.onBubbleRecordRequested()
                    }
                },
                onClose = {
                    dismissInternal(clearRound = true, BubbleSuppressionReason.DISMISS)
                },
                onStop = {
                    requestStop()
                },
                onExpand = {
                    val latest = lastSnapshot ?: return@BubblePreviewWindow
                    expandCollapsed(latest)
                },
            )
        }
        val candidate = when (created) {
            is BubblePreviewBoundaryResult.Success -> created.value
            is BubblePreviewBoundaryResult.Recovered -> {
                handleBoundaryRecovery(BubblePreviewOperation.CREATE)
                return false
            }
            is BubblePreviewBoundaryResult.Skipped -> return false
        }

        // Re-check every eligibility rule immediately before addView; the
        // world may have changed while the placeholder view was created.
        if (!isEligible(frame)) {
            discardWindow(candidate)
            return false
        }
        val added = boundary.run(BubblePreviewOperation.ADD, attempt, now()) {
            candidate.add()
        }
        when (added) {
            is BubblePreviewBoundaryResult.Success -> Unit
            is BubblePreviewBoundaryResult.Recovered -> {
                handleBoundaryRecovery(BubblePreviewOperation.ADD)
                discardWindow(candidate)
                return false
            }
            is BubblePreviewBoundaryResult.Skipped -> {
                discardWindow(candidate)
                return false
            }
        }

        window = candidate
        placement = windowPlacement
        lastPlan = windowPlan
        geometryRetryAtMs = 0L
        updateGate.markApplied(now(), windowPlacement)
        counters.added()
        attempt?.markVisible(now())
        scheduleSustainedSuccess()
        notifyAttached()
        Log.i(
            TAG,
            "bubble add hostGen=$hostGeneration geom=${frame.geometryGeneration} " +
                "count=${counterSnapshot()}",
        )
        return true
    }

    private fun requestUpdate(
        frame: BubblePreviewHostFrame,
        planAndPlacement: Pair<BubblePreviewLayoutPlan, BubblePreviewPlacement>,
    ) {
        val currentWindow = window ?: return
        val next = planAndPlacement.second
        val nowMs = now()
        when (val decision = updateGate.decide(nowMs, next)) {
            BubblePreviewUpdateDecision.Apply -> {
                val result = boundary.run(BubblePreviewOperation.UPDATE, attempt, nowMs) {
                    currentWindow.updatePlacement(
                        next,
                        frame.geometry?.resolvedAnchor(),
                    )
                }
                if (result is BubblePreviewBoundaryResult.Recovered) {
                    handleBoundaryRecovery(BubblePreviewOperation.UPDATE)
                    return
                }
                if (result is BubblePreviewBoundaryResult.Skipped) return
                updateGate.markApplied(nowMs, next)
                placement = next
                counters.updated()
            }
            is BubblePreviewUpdateDecision.Schedule -> {
                pendingUpdatePlacement = next
                if (pendingUpdateRunnable == null) {
                    val generation = roundGeneration
                    counters.taskScheduled()
                    val runnable = Runnable {
                        pendingUpdateRunnable = null
                        if (generation != roundGeneration) return@Runnable
                        val frameNow = lastFrame ?: return@Runnable
                        runContained(BubblePreviewOperation.SCHEDULED) {
                            val pending = pendingUpdatePlacement
                            val latest = lastSnapshot
                            if (generation == roundGeneration && pending != null &&
                                window != null && latest != null
                            ) {
                                val computed = computePlacement(frameNow, latest)
                                if (computed != null) requestUpdate(frameNow, computed)
                            }
                        }
                    }
                    pendingUpdateRunnable = runnable
                    handler.postDelayed(runnable, decision.delayMs.coerceAtLeast(1L))
                }
            }
            BubblePreviewUpdateDecision.Skip -> Unit
        }
    }

    private fun applyWindowState(
        snapshot: BubbleTurnSnapshot,
        plan: BubblePreviewLayoutPlan,
        placement: BubblePreviewPlacement,
    ) {
        val current = window ?: return
        val result = boundary.run(BubblePreviewOperation.UPDATE, attempt, now()) {
            current.applyState(
                snapshot,
                failureTextFor(snapshot),
                plan,
                placement,
                lastFrame?.geometry?.resolvedAnchor(),
            )
        }
        if (result is BubblePreviewBoundaryResult.Recovered) {
            handleBoundaryRecovery(BubblePreviewOperation.UPDATE)
            return
        }
        if (result is BubblePreviewBoundaryResult.Skipped) return
        lastPlan = plan
        this.placement = placement
        updateGate.markApplied(now(), placement)
        counters.updated()
    }

    private fun render(snapshot: BubbleTurnSnapshot, frame: BubblePreviewHostFrame?) {
        if (destroyed) return
        if (snapshot.suppressed) {
            val finishedToken = token
            val reason = snapshot.suppressionReason ?: BubbleSuppressionReason.DISMISS
            removeWindow()
            cancelPendingUpdate()
            cancelSustained()
            cancelTick()
            cancelDeadline()
            token = null
            lastSnapshot = null
            attempt?.let { current ->
                runContained(BubblePreviewOperation.CLEANUP) { current.endAttempt(now()) }
            }
            attempt = null
            placement = null
            lastPlan = null
            pendingSide = null
            if (finishedToken != null) notifyFinished(finishedToken, reason)
            return
        }
        if (snapshot.visibleState == BubbleVisibleState.HIDDEN) {
            removeWindow()
            if (snapshot.geometrySuspended) return
            val finishedToken = token
            cancelPendingUpdate()
            cancelSustained()
            cancelTick()
            cancelDeadline()
            token = null
            lastSnapshot = null
            attempt?.let { current ->
                runContained(BubblePreviewOperation.CLEANUP) { current.endAttempt(now()) }
            }
            attempt = null
            if (finishedToken != null) {
                notifyFinished(finishedToken, BubbleSuppressionReason.TIMEOUT)
            }
            return
        }
        if (frame == null) {
            afterSnapshot(snapshot)
            return
        }
        val computed = computePlacement(frame, snapshot) ?: return
        if (window == null) {
            tryAttach(frame, snapshot)
            afterSnapshot(snapshot)
            return
        }
        if (computed.first != lastPlan) {
            applyWindowState(snapshot, computed.first, computed.second)
        } else {
            window?.updateText(snapshot, failureTextFor(snapshot))
            requestUpdate(frame, computed)
        }
        afterSnapshot(snapshot)
    }

    private fun afterSnapshot(snapshot: BubbleTurnSnapshot) {
        if (destroyed || snapshot.suppressed) return
        val hasBacklog = snapshot.visibleState == BubbleVisibleState.TYPING &&
            snapshot.visibleGraphemes < snapshot.targetGraphemes
        if (hasBacklog) scheduleTick() else cancelTick()
        scheduleDeadline(snapshot)
    }

    private fun scheduleTick() {
        if (tickRunnable != null) return
        val generation = roundGeneration
        val currentToken = token ?: return
        counters.taskScheduled()
        val runnable = Runnable {
            tickRunnable = null
            if (generation != roundGeneration) return@Runnable
            runContained(BubblePreviewOperation.SCHEDULED) {
                val state = turnState ?: return@runContained
                val snapshot = state.onTick(currentToken, now())
                lastSnapshot = snapshot
                render(snapshot, lastFrame)
            }
        }
        tickRunnable = runnable
        handler.postDelayed(runnable, BubblePreviewText.TICK_MILLIS)
    }

    private fun scheduleDeadline(snapshot: BubbleTurnSnapshot) {
        val state = turnState ?: return
        val deadline = state.nextDeadlineMillis(now()) ?: run {
            cancelDeadline()
            return
        }
        // The HOLD/error/chip absolute deadline is scheduled exactly once. Text
        // ticks call this on every frame, so an identical pending deadline must
        // not be canceled and re-posted (which would also extend nothing but
        // churn the main looper).
        if (deadlineRunnable != null && scheduledDeadlineAtMs == deadline) return
        cancelDeadline()
        val generation = roundGeneration
        val currentToken = token ?: return
        val delay = (deadline - now()).coerceAtLeast(1L)
        counters.taskScheduled()
        val runnable = Runnable {
            deadlineRunnable = null
            scheduledDeadlineAtMs = -1L
            if (generation != roundGeneration) return@Runnable
            runContained(BubblePreviewOperation.SCHEDULED) {
                val latest = turnState?.snapshot(now()) ?: return@runContained
                if (latest.token != currentToken) return@runContained
                lastSnapshot = latest
                render(latest, lastFrame)
            }
        }
        deadlineRunnable = runnable
        scheduledDeadlineAtMs = deadline
        handler.postDelayed(runnable, delay)
    }

    private fun computePlacement(
        frame: BubblePreviewHostFrame,
        snapshot: BubbleTurnSnapshot,
    ): Pair<BubblePreviewLayoutPlan, BubblePreviewPlacement>? {
        val geometry = frame.geometry ?: return null
        if (!geometry.isUsable() || !geometry.hasCharacterAnchor()) return null
        val character = geometry.characterBounds ?: return null
        val anchor = geometry.resolvedAnchor() ?: return null
        val safe = geometry.safeScreenBounds(dp(BUBBLE_PREVIEW_SAFETY_MARGIN_DP)) ?: return null
        val measured = measuredActionWidths
        val primaryActions = primaryActionsFor(snapshot)
        val planActions = primaryActions
        val primaryWidths = planActions.map { action ->
            BubblePreviewActionLayout.touchWidthPx(
                measured[action] ?: 0f,
                density,
                actionTargetPx(),
                BubblePreviewActionLayout.iconWidthPx(action, density),
            ).toFloat()
        }
        val request = BubblePreviewScaleRequest(
            characterScale = characterScale,
            density = density,
            scaledDensity = scaledDensity,
            safeWidthPx = safe.width,
            safeHeightPx = safe.height,
            hasUserSummary = snapshot.userSummary.isNotBlank(),
            realBodyLinePx = measuredBodyLinePx,
            realNameLinePx = measuredNameLinePx,
            actionCount = planActions.size,
            realActionWidthsPx = primaryWidths,
        )
        val plans = BubblePreviewScalePolicy.fallbackPlans(request)
        if (plans.isEmpty()) return null

        val isChip = snapshot.visibleState == BubbleVisibleState.COLLAPSED
        if (isChip) {
            val closeWidth = BubblePreviewActionLayout.touchWidthPx(
                measured[BubblePreviewAction.CLOSE] ?: 0f,
                density,
                actionTargetPx(),
                BubblePreviewActionLayout.iconWidthPx(BubblePreviewAction.CLOSE, density),
            ).toFloat()
            val chip = BubblePreviewScalePolicy.chipPlan(request, closeWidth) ?: return null
            val decision = BubblePreviewPlacementPolicy.place(
                safeBounds = safe,
                characterBounds = character,
                anchor = anchor,
                orbBounds = geometry.orbWindowBounds,
                plans = listOf(chip),
                previous = placement,
                pendingSide = pendingSide,
                nowElapsedMs = now(),
                density = density,
                petGapDp = BUBBLE_PREVIEW_PET_GAP_DP.toFloat(),
                orbGapDp = BUBBLE_PREVIEW_ORB_GAP_DP.toFloat(),
            )
            pendingSide = decision.pendingSide
            val chosen = decision.placement ?: return null
            return chosen.plan to chosen
        }

        for (plan in plans) {
            val labels = BubblePreviewActionLayout.choose(
                state = snapshot.visibleState,
                contentWidthPx = plan.contentWidthPx,
                density = density,
                minimumTouchPx = actionTargetPx(),
                measured = measured,
            ) ?: continue
            val selected = plan.copy(actionLabels = labels)
            val decision = BubblePreviewPlacementPolicy.place(
                safeBounds = safe,
                characterBounds = character,
                anchor = anchor,
                orbBounds = geometry.orbWindowBounds,
                plans = listOf(selected),
                previous = placement,
                pendingSide = pendingSide,
                nowElapsedMs = now(),
                density = density,
                petGapDp = BUBBLE_PREVIEW_PET_GAP_DP.toFloat(),
                orbGapDp = BUBBLE_PREVIEW_ORB_GAP_DP.toFloat(),
            )
            pendingSide = decision.pendingSide
            val chosen = decision.placement ?: continue
            return chosen.plan to chosen
        }
        return null
    }

    private fun primaryActionsFor(snapshot: BubbleTurnSnapshot): List<BubblePreviewAction> =
        when (snapshot.visibleState) {
            BubbleVisibleState.THINKING,
            BubbleVisibleState.TYPING,
            -> listOf(
                BubblePreviewAction.STOP,
                BubblePreviewAction.RECORDS,
                BubblePreviewAction.CLOSE,
            )
            BubbleVisibleState.HOLD -> listOf(
                BubblePreviewAction.VIEW_ALL,
                BubblePreviewAction.RECORDS,
                BubblePreviewAction.CLOSE,
            )
            BubbleVisibleState.ERROR -> listOf(
                BubblePreviewAction.RECORDS,
                BubblePreviewAction.CLOSE,
            )
            BubbleVisibleState.COLLAPSED -> listOf(BubblePreviewAction.CLOSE)
            BubbleVisibleState.HIDDEN -> emptyList()
        }

    private fun failureTextFor(snapshot: BubbleTurnSnapshot): String {
        if (snapshot.phase != BubbleTurnPhase.FAILED) return ""
        if (snapshot.visibleText.isNotBlank()) return "回复中断，已保留收到的内容"
        return failureText.ifBlank { "模型没有返回内容，请稍后再试。" }
    }

    private fun isEligible(frame: BubblePreviewHostFrame): Boolean {
        if (destroyed || failedRound || window != null) return false
        if (unresolvedCleanup || orphanWindow != null) return false
        if (OverlayAppVisibility.foreground) return false
        if (BubblePreviewProcessSession.isBlocked()) return false
        if (!BubblePreviewModule.isEnabled()) return false
        if (!BluefinStore.readBubblePreview(prefs)) return false
        if (circuit.isTripped()) return false
        if (attempt?.isFailed() != false) return false
        if (frame.hostGeneration != hostGeneration) return false
        if (!frame.stageAttached || !frame.pageReady) return false
        if (frame.clickThrough || frame.hidden || frame.opacity <= 0f) return false
        val geometry = frame.geometry ?: return false
        if (!geometry.isUsable() || !geometry.hasCharacterAnchor()) return false
        if (!Settings.canDrawOverlays(context)) return false
        return true
    }

    private fun markCurrentRoundFailed() {
        val current = attempt
        if (current != null) {
            runContained(BubblePreviewOperation.LISTENER) {
                current.recordFailure(now())
            }
        }
        markAttemptFailed()
    }

    private fun markAttemptFailed() {
        if (failedRound) {
            removeWindow()
            return
        }
        failedRound = true
        turnState?.let { state ->
            token?.let { current ->
                lastSnapshot = state.suppress(BubbleSuppressionReason.DISMISS, now())
                if (current < 0L) Unit
            }
        }
        removeWindow()
        cancelPendingUpdate()
        cancelSustained()
        cancelTick()
        cancelDeadline()
        notifyAttemptFailed()
        Log.w(
            TAG,
            "bubble attempt failed hostGen=$hostGeneration count=${counterSnapshot()}",
        )
    }

    /**
     * Every recovered optional boundary while a window may still be visible
     * fails the current round immediately: hide/remove, cancel posts and the
     * ticker, then tell the host exactly once. Clearing only the payload would
     * leave a visible stale window.
     */
    private fun handleBoundaryRecovery(operation: BubblePreviewOperation) {
        if (operation == BubblePreviewOperation.REMOVE ||
            operation == BubblePreviewOperation.CLEANUP
        ) {
            return
        }
        markCurrentRoundFailed()
    }

    private fun scheduleSustainedSuccess() {
        sustainedRunnable?.let { handler.removeCallbacks(it) }
        val generation = roundGeneration
        val tokenSnapshot = attempt
        val runnable = Runnable {
            sustainedRunnable = null
            if (generation != roundGeneration) return@Runnable
            runContained(BubblePreviewOperation.SCHEDULED) {
                tokenSnapshot?.confirmSustainedSuccess(now())
            }
        }
        sustainedRunnable = runnable
        counters.taskScheduled()
        handler.postDelayed(runnable, BubblePreviewModule.SUSTAINED_SUCCESS_MS)
    }

    private fun suspendForGeometry(nowMs: Long) {
        if (window != null) removeWindow()
        updateGate.reset()
        placement = null
        lastPlan = null
        if (geometryRetryAtMs <= nowMs) geometryRetryAtMs = 0L
        turnState?.let { state ->
            lastSnapshot = state.setGeometryAvailable(false, nowMs)
        }
        cancelTick()
    }

    private fun dismissInternal(
        clearRound: Boolean,
        reason: BubbleSuppressionReason,
    ) {
        if (destroyed) return
        if (clearRound) {
            // Capture the token before clearing it: the host must still learn
            // which round ended so it can detach the matching listener.
            val finishedToken = token
            roundGeneration += 1
            turnState?.let { state ->
                finishedToken?.let { current -> state.suppress(reason, now()) }
            }
            token = null
            lastSnapshot = null
            cancelGeometryRetry()
            cancelPendingUpdate()
            cancelSustained()
            cancelTick()
            cancelDeadline()
            removeWindow()
            placement = null
            lastPlan = null
            pendingSide = null
            noSpaceRecorded = false
            runContained(BubblePreviewOperation.CLEANUP) {
                attempt?.endAttempt(now())
            }
            attempt = null
            if (finishedToken != null) notifyFinished(finishedToken, reason)
            return
        }
        cancelPendingUpdate()
        cancelSustained()
        cancelTick()
        cancelDeadline()
        removeWindow()
        placement = null
        lastPlan = null
        pendingSide = null
    }

    /**
     * Replacement path for a new explicit STARTED. The old round is suppressed
     * and reported, then every timer/window reference is cleared so the new
     * token starts from a clean slate.
     */
    private fun retireCurrentRoundForReplacement() {
        val previous = token
        roundGeneration += 1
        if (previous != null) {
            turnState?.suppress(BubbleSuppressionReason.CLEAR, now())
        }
        token = null
        lastSnapshot = null
        cancelGeometryRetry()
        cancelPendingUpdate()
        cancelSustained()
        cancelTick()
        cancelDeadline()
        removeWindow()
        placement = null
        lastPlan = null
        pendingSide = null
        noSpaceRecorded = false
        runContained(BubblePreviewOperation.CLEANUP) {
            attempt?.endAttempt(now())
        }
        attempt = null
        if (previous != null) {
            roundFinishedNotified = false
            notifyFinished(previous, BubbleSuppressionReason.CLEAR)
        }
    }

    /**
     * Bounded cleanup: the root is hidden inside the boundary, then one
     * removal sequence (itself capped at two platform attempts) runs. A
     * still-failing root is retained as a process-lifetime orphan with a fatal
     * cleanup flag; attach stays disabled and the host's own cleanup is never
     * skipped. An already unmanaged root is idempotent success.
     */
    private fun removeWindow() {
        val current = window ?: return
        window = null
        boundary.run(BubblePreviewOperation.CLEANUP, null, now()) {
            current.hideForCleanup()
        }
        val removed = boundary.run(BubblePreviewOperation.REMOVE, attempt, now()) {
            current.removeImmediateOnce()
        }
        if (removed is BubblePreviewBoundaryResult.Success) {
            counters.removed()
        } else {
            retainOrphan(current)
        }
        cancelSustained()
    }

    private fun discardWindow(candidate: BubblePreviewWindow) {
        boundary.run(BubblePreviewOperation.CLEANUP, null, now()) {
            candidate.hideForCleanup()
        }
        val removed = boundary.run(BubblePreviewOperation.REMOVE, attempt, now()) {
            candidate.removeImmediateOnce()
        }
        if (removed is BubblePreviewBoundaryResult.Success) {
            counters.removed()
        } else {
            retainOrphan(candidate)
        }
    }

    private fun retainOrphan(candidate: BubblePreviewWindow) {
        if (orphanWindow == null) orphanWindow = candidate
        unresolvedCleanup = true
        failedRound = true
        turnState?.let { state ->
            token?.let { current -> state.suppress(BubbleSuppressionReason.DISMISS, now()) }
        }
        token = null
        lastSnapshot = null
        cancelSustained()
        cancelTick()
        cancelDeadline()
        cancelPendingUpdate()
        cancelGeometryRetry()
        BubblePreviewProcessSession.retainUnresolvedController(
            controller = this,
            operation = "REMOVE",
            exceptionType = null,
        )
        Log.e(
            TAG,
            "bubble cleanup blocked hostGen=$hostGeneration; hidden root retained",
        )
    }

    private var geometryRetryRunnable: Runnable? = null

    private fun cancelGeometryRetry() {
        geometryRetryRunnable?.let { handler.removeCallbacks(it) }
        geometryRetryRunnable = null
        geometryRetryPending = false
    }

    private fun cancelPendingUpdate() {
        pendingUpdateRunnable?.let { handler.removeCallbacks(it) }
        pendingUpdateRunnable = null
        pendingUpdatePlacement = null
    }

    private fun cancelSustained() {
        sustainedRunnable?.let { handler.removeCallbacks(it) }
        sustainedRunnable = null
    }

    private fun cancelTick() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun cancelDeadline() {
        deadlineRunnable?.let { handler.removeCallbacks(it) }
        deadlineRunnable = null
        scheduledDeadlineAtMs = -1L
    }

    private fun notifyAttached() {
        runContained(BubblePreviewOperation.LISTENER) { actions.onBubbleAttached() }
    }

    private fun notifyAttemptFailed() {
        runContained(BubblePreviewOperation.LISTENER) { actions.onBubbleAttemptFailed() }
    }

    private fun notifyNoSpace() {
        runContained(BubblePreviewOperation.LISTENER) { actions.onBubbleNoSpace() }
    }

    private fun notifyFinished(finishedToken: Long, reason: BubbleSuppressionReason) {
        if (roundFinishedNotified) return
        roundFinishedNotified = true
        runContained(BubblePreviewOperation.LISTENER) {
            actions.onBubbleRoundFinished(finishedToken, reason)
        }
    }

    private fun contain(
        operation: BubblePreviewOperation,
        token: BubblePreviewDisplayAttempt? = attempt,
        block: () -> Unit,
    ) {
        contain(operation, token, onFault = {}, block = block)
    }

    private fun <T> contain(
        operation: BubblePreviewOperation,
        token: BubblePreviewDisplayAttempt? = attempt,
        onFault: () -> T,
        block: () -> T,
    ): T {
        return when (val result = boundary.run(operation, token, now(), block)) {
            is BubblePreviewBoundaryResult.Success -> result.value
            is BubblePreviewBoundaryResult.Recovered -> {
                markCurrentRoundFailed()
                onFault()
            }
            is BubblePreviewBoundaryResult.Skipped -> onFault()
        }
    }

    /**
     * All optional callbacks use this instead of ignoring a Recovered result.
     * A recovered callback fails the visible round immediately.
     */
    private fun runContained(operation: BubblePreviewOperation, block: () -> Unit) {
        when (boundary.run(operation, attempt, now(), block)) {
            is BubblePreviewBoundaryResult.Success -> Unit
            is BubblePreviewBoundaryResult.Recovered -> handleBoundaryRecovery(operation)
            is BubblePreviewBoundaryResult.Skipped -> Unit
        }
    }

    private fun reportFault(operation: BubblePreviewOperation, error: Exception) {
        boundary.run(operation, null, now()) { throw error }
    }

    private fun measureActionTextWidths(): Map<BubblePreviewAction, Float> {
        val labelSp = BubblePreviewScalePolicy.labelTextSp(characterScale)
        textPaint.textSize = labelSp * scaledDensity
        val labels = mapOf(
            BubblePreviewAction.STOP to "停止",
            BubblePreviewAction.VIEW_ALL to "查看全部",
            BubblePreviewAction.RECORDS to "记录",
            BubblePreviewAction.CLOSE to "×",
        )
        return labels.mapValues { (_, text) -> textPaint.measureText(text) }
    }

    private fun actionTargetPx(): Int =
        ceil(BubblePreviewScalePolicy.ACTION_TARGET_DP * density).toInt().coerceAtLeast(1)

    private fun sanitizeSummary(text: String): String =
        text.trim().replace('\n', ' ').replace('\r', ' ').take(80)

    private fun measureLinePx(spSize: Float): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = spSize * scaledDensity
        }
        val metrics = paint.fontMetrics
        return (metrics.descent - metrics.ascent + metrics.leading).coerceAtLeast(1f)
    }

    private fun now(): Long = SystemClock.uptimeMillis()

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val TAG = "BluefinBubble"
        const val GEOMETRY_COALESCE_MS = 100L
        const val MINIMUM_UPDATE_DELTA_DP = 2f
    }
}
