package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage-C source/reachability contracts that cannot be observed purely:
 * the STARTED -> first-attach -> visible-preview path, lazy insets, listener
 * identity, panel resize and the text-only window update rule.
 */
class BubblePreviewStageCContractTest {
    private val modulePath = "com/wwkhub/bluefin/bubblepreview"

    private fun source(path: String): String = File(path).readText()

    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("missing body for $signature", open >= 0)
        var depth = 0
        var index = open
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        while (index < source.length) {
            val char = source[index]
            val next = if (index + 1 < source.length) source[index + 1] else '\u0000'
            when {
                inLineComment -> if (char == '\n') inLineComment = false
                inBlockComment -> if (char == '*' && next == '/') {
                    inBlockComment = false
                    index += 1
                }
                inString -> when {
                    char == '\\' -> index += 1
                    char == '"' -> inString = false
                }
                inChar -> when {
                    char == '\\' -> index += 1
                    char == '\'' -> inChar = false
                }
                char == '/' && next == '/' -> {
                    inLineComment = true
                    index += 1
                }
                char == '/' && next == '*' -> {
                    inBlockComment = true
                    index += 1
                }
                char == '"' -> inString = true
                char == '\'' -> inChar = true
                char == '{' -> depth += 1
                char == '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1).trimEnd()
                }
            }
            index += 1
        }
        throw AssertionError("unbalanced body for $signature")
    }

    @Test
    fun startedFirstAttachesOnlyThroughProjectedPreflightThenCollapseAndRealBounds() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val schedule = methodBody(host, "    private fun scheduleBubblePreviewAfterStarted(")
        assertTrue(schedule.indexOf("handler.post") < schedule.indexOf("BubblePreviewModule.isEnabled()"))
        assertTrue(schedule.contains("readBubblePreview"))
        assertTrue(schedule.contains("BubblePreviewExplicitChatEntry.failureCircuitOrNull()"))
        assertTrue(schedule.contains("projectedOrbBounds()"))
        assertTrue(schedule.contains("BubblePreviewFirstAttachFlow()"))
        assertTrue(schedule.contains("collapseOrbForBubbleAttach()"))
        assertTrue(schedule.contains("scheduleFirstAttachDeadline"))
        // No add may happen while the full-screen record panel is still open.
        assertFalse(schedule.contains("showStarted("))

        val advance = methodBody(host, "    private fun advanceBubbleFirstAttach(")
        assertTrue(advance.contains("!orbExpanded && !chatExpanded"))
        assertTrue(advance.contains("bubbleImeGate?.isTrustworthy() == true"))
        assertTrue(advance.contains("geometry.orbWindowBounds != null"))
        assertTrue(advance.contains("BubblePreviewAttachDecision.READY"))
        assertTrue(advance.contains("startAcceptedBubbleRound(token)"))

        val start = methodBody(host, "    private fun startAcceptedBubbleRound(")
        assertTrue(start.contains("controller.showStarted("))
        assertTrue(start.contains("attachBubbleChatListener(session)"))
        assertTrue(start.contains("finishBubbleFirstAttach(token, restore = true)"))

        val bounds = methodBody(host, "    private fun currentOrbWindowBounds(")
        assertTrue(bounds.contains("if (!orbAttached) return null"))
        assertTrue(bounds.contains("if (orbExpanded || chatExpanded)"))
        assertTrue(bounds.contains("rootVisibleScreenBounds(orbRoot)"))
        assertTrue(bounds.contains("BubblePreviewScreenCoordinates.compactBounds("))
        assertTrue(bounds.contains("orbRoot.getLocationOnScreen(onScreen)"))
        assertTrue(bounds.contains("orbParams.width"))
        assertTrue(bounds.contains("orbParams.height"))

        val finish = methodBody(host, "    private fun finishBubbleFirstAttach(")
        assertTrue(finish.contains("if (token != bubbleRequestToken) return"))
        assertTrue(finish.contains("bubblePreview?.dismissForHostUi()"))
        assertTrue(finish.contains("openChatPanel(requestInput = false, dismissBubble = false, forceRestore = true)"))
        assertTrue(finish.contains("releasePanelIme()"))
    }

    @Test
    fun noAnchorNoSpaceUnknownImeOrAddFailureRestoresWithoutAKeyboard() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val noSpace = methodBody(host, "            override fun onBubbleNoSpace()")
        assertTrue(noSpace.contains("finishBubbleFirstAttach(awaiting, restore = true)"))
        val failed = methodBody(host, "            override fun onBubbleAttemptFailed()")
        assertTrue(failed.contains("finishBubbleFirstAttach(awaiting, restore = true)"))

        val deadline = methodBody(host, "    private fun scheduleFirstAttachDeadline(")
        assertTrue(deadline.contains("FIRST_ATTACH_DEADLINE_MS"))
        assertTrue(deadline.contains("finishBubbleFirstAttach(token, restore = true)"))
        assertTrue(host.contains("const val FIRST_ATTACH_DEADLINE_MS = 2500L"))

        val advance = methodBody(host, "    private fun advanceBubbleFirstAttach(")
        assertTrue(advance.contains("TIMED_OUT"))
        assertTrue(advance.contains("PRECHECK_FAILED"))

        val dismiss = methodBody(host, "    private fun dismissBubblePreview()")
        assertTrue(dismiss.contains("bubbleRequestToken += 1"))
        assertTrue(dismiss.contains("cancelBubbleFirstAttach()"))
    }

    @Test
    fun insetsAreLazyAndCoalescedIntoOneStyleEpoch() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val install = methodBody(host, "    private fun installBubbleInsetsCallbacks()")
        assertTrue(install.contains("ViewCompat.setOnApplyWindowInsetsListener(orbRoot)"))
        assertTrue(install.contains("WindowInsetsCompat.Type.ime()"))
        assertTrue(install.contains("scheduleBubbleInsetsEpoch()"))
        assertTrue(install.contains("orbRoot.requestApplyInsets()"))

        val epoch = methodBody(host, "    private fun scheduleBubbleInsetsEpoch()")
        assertTrue(epoch.contains("INSETS_EPOCH_COALESCE_MS"))
        assertTrue(epoch.contains("bubbleGeometryGeneration += 1"))
        assertTrue(epoch.contains("invalidateBubbleScreenSnapshot()"))
        assertTrue(epoch.contains("runBubbleHostCallback(\"insets-epoch\")"))
        assertTrue(epoch.contains("controller.onHostInsetsChanged()"))
        assertTrue(host.contains("const val INSETS_EPOCH_COALESCE_MS = 100L"))

        val capture = methodBody(host, "    private fun bubbleScreenSnapshot(")
        assertTrue(capture.contains("if (bubbleScreenSnapshotGeneration == geometryGeneration)"))
        assertTrue(capture.contains("captureBubbleScreenSnapshot()"))
        assertFalse(capture.contains("onHostLayout"))
    }

    @Test
    fun activeRoundOnlyFeedsGeometryAndCapturesVisibleRootInScreenCoordinates() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val layout = methodBody(host, "    private fun onLayout(")
        assertTrue(layout.contains("publishBubbleHostTail(parsed, now)"))
        assertFalse(layout.contains("publishBubbleGeometry("))
        assertFalse(layout.contains("BubblePreviewModule.isEnabled()"))

        val tail = methodBody(host, "    private fun publishBubbleHostTail(")
        val guard = tail.indexOf("bubbleSignalToken == null && bubbleFirstAttach == null")
        val publish = tail.indexOf("publishBubbleGeometry(bubble, parsed, now)")
        assertTrue(guard in 0 until publish)
        assertTrue(tail.contains("BubblePreviewModule.isEnabled()"))
        assertTrue(tail.contains("clickThrough || petHiddenForBubble || petOpacityForBubble <= 0f"))
        assertTrue(tail.contains("catch (error: Exception)"))

        val capture = methodBody(host, "    private fun captureBubbleScreenSnapshot()")
        assertTrue(capture.contains("val basis = readBubbleDisplayBasis()"))
        assertTrue(capture.contains("rootVisibleScreenBounds(root)"))
        assertTrue(capture.contains("bubbleImeGate"))
        assertTrue(capture.contains("gate.isTrustworthy()"))
        assertTrue(capture.contains("gate.imeBottomInsets()"))
        assertFalse(capture.contains("currentWindowMetrics"))
        assertFalse(capture.contains("screenSize()"))

        val publishBody = methodBody(host, "    private fun publishBubbleGeometry(")
        assertTrue(publishBody.contains("orbWindowBounds = currentOrbWindowBounds("))
        assertTrue(publishBody.contains("BubblePreviewHostFrame("))
        assertTrue(publishBody.contains("bubble.onHostLayout(frame)"))
        assertTrue(publishBody.contains("tryAdvanceBubbleFirstAttach(frame)"))
        assertFalse(publishBody.contains("screenSize()"))
    }

    @Test
    fun listenerBindsAcceptedUserIndexAndGenerationWithoutBlockingOtherListeners() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val attach = methodBody(host, "    private fun attachBubbleChatListener(")
        assertTrue(attach.contains("handler.post"))
        assertTrue(attach.contains("catch (error: Exception)"))
        assertTrue(attach.contains("session.addListener(listener)"))
        assertTrue(attach.contains("bubbleSignalPayload(snapshot)"))
        assertTrue(attach.contains("Looper.myLooper() == Looper.getMainLooper()"))
        // A non-main callback posts only the token and then reads the current
        // snapshot on main; no text/history is captured by the pending post.
        val post = attach.indexOf("handler.post")
        val currentSnapshot = attach.indexOf("session.snapshot()", post)
        assertTrue(post in 0 until currentSnapshot)
        assertFalse(host.contains("MAX_BUBBLE_SIGNAL_CODE_UNITS"))
        assertFalse(host.contains(".take(MAX_BUBBLE"))
        val payload = methodBody(host, "    private fun bubbleSignalPayload(")
        assertTrue(payload.contains("userIndex + 1"))
        assertTrue(payload.contains("ChatRole.ASSISTANT"))
        assertTrue(payload.contains("ChatPhase.FAILED"))
        assertTrue(payload.contains("ChatPhase.STOPPED"))
        assertTrue(payload.contains("snapshot.workerBusy"))
        assertFalse(payload.contains("take("))
        // The posted path never carries the whole session snapshot across post.
        assertFalse(attach.contains("deliverBubbleSignal(token, snapshot)"))

        val deliver = methodBody(host, "    private fun deliverBubbleSignal(")
        val generationCheck = deliver.indexOf("signal.requestGeneration != bubbleSignalGeneration")
        val indexCheck = deliver.indexOf("!signal.indexValid")
        assertTrue(generationCheck in 0 until indexCheck)
        assertTrue(deliver.contains("BubblePreviewChatSignal("))

        val send = methodBody(host, "    private fun sendFromOverlay()")
        assertTrue(send.contains("ChatDraft.state.captureSend()"))
        assertTrue(send.contains("ChatDraft.state.onSendOutcome(capture, outcome)"))
        assertTrue(send.contains("snapshot.lines.lastIndex"))
        assertTrue(send.contains("snapshot.requestGeneration"))
    }

    @Test
    fun recordAndChatEntriesShareThePanelWithTheRequiredUiContract() {
        val layout = source("src/main/res/layout/overlay_orb.xml")
        assertTrue(layout.contains("android:id=\"@+id/overlay_open_records\""))
        assertTrue(layout.contains("@string/orb_records"))
        assertTrue(layout.contains("android:id=\"@+id/overlay_orb_unconfigured\""))
        assertTrue(layout.contains("android:id=\"@+id/overlay_unconfigured_settings\""))
        assertTrue(layout.contains("android:id=\"@+id/overlay_unconfigured_cancel\""))
        assertTrue(layout.contains("android:id=\"@+id/overlay_chat_title\""))
        assertTrue(layout.contains("@string/chat_records_title"))
        assertTrue(layout.contains("@string/chat_input_hint_overlay"))
        assertTrue(layout.contains("android:id=\"@+id/overlay_chat_stop\""))
        assertTrue(layout.contains("android:id=\"@+id/overlay_chat_collapse\""))
        assertTrue(layout.contains("@string/chat_collapse"))
        assertTrue(layout.contains("@string/chat_full_view"))

        val strings = source("src/main/res/values/strings.xml")
        assertTrue(strings.contains("<string name=\"chat_records_title\">对话记录</string>"))
        assertTrue(strings.contains("<string name=\"chat_input_hint_overlay\">说点什么…</string>"))
        assertTrue(strings.contains("<string name=\"chat_busy_hint\">正在回复，可先停止。</string>"))
        assertTrue(strings.contains("<string name=\"orb_records\">记录</string>"))
        assertTrue(strings.contains("还没接通模型，要去设置里填写吗？"))

        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val panel = methodBody(host, "    private fun openChatPanel(")
        assertTrue(panel.contains("if (requestInput && !ModelEndpointStore.isReady(appContext))"))
        assertTrue(panel.contains("showOrbUnconfiguredPrompt()"))
        assertTrue(panel.contains("if (requestInput) {"))
        assertTrue(panel.contains("showPanelIme()"))
        assertTrue(panel.contains("releasePanelIme()"))
        val full = methodBody(host, "    private fun openFullChat()")
        assertTrue(full.contains("dismissBubblePreview()"))
        assertTrue(full.contains("collapseOrb()"))
        assertTrue(full.contains("ChatEntry.openRecords(appContext)"))
    }

    @Test
    fun busyBlankDraftAndStopBehaviorStayBoundToTheRealSessionWorker() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val sendState = methodBody(host, "    private fun updateSendState()")
        assertTrue(sendState.contains("snapshot.workerBusy"))
        assertTrue(sendState.contains("chatSend.isEnabled = configured && !busy && !blank"))
        assertTrue(sendState.contains("chatStop.visibility"))

        val send = methodBody(host, "    private fun sendFromOverlay()")
        assertTrue(send.contains("if (text.isBlank())"))
        assertTrue(send.contains("ChatDraftSendOutcome.STARTED"))
        assertTrue(send.contains("ChatDraftSendOutcome.NOT_CONFIGURED"))
        assertTrue(send.contains("ChatDraftSendOutcome.BUSY"))
        assertTrue(send.contains("ChatDraftSendOutcome.EMPTY"))

        val stop = methodBody(host, "            override fun onBubbleStopRequested()")
        assertTrue(stop.contains("session.stop()"))
        assertFalse(stop.contains("send("))
    }

    @Test
    fun controllerUsesOneTickerOneDeadlineAndTextOnlyUpdates() {
        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        val after = methodBody(controller, "    private fun afterSnapshot(")
        assertTrue(after.contains("snapshot.visibleState == BubbleVisibleState.TYPING"))
        assertTrue(after.contains("snapshot.visibleGraphemes < snapshot.targetGraphemes"))
        assertTrue(after.contains("if (hasBacklog) scheduleTick() else cancelTick()"))

        val tick = methodBody(controller, "    private fun scheduleTick()")
        assertTrue(tick.contains("if (tickRunnable != null) return"))
        assertTrue(tick.contains("BubblePreviewText.TICK_MILLIS"))

        val deadline = methodBody(controller, "    private fun scheduleDeadline(")
        assertTrue(deadline.contains("scheduledDeadlineAtMs == deadline"))
        assertTrue(deadline.contains("cancelDeadline()"))

        val render = methodBody(controller, "    private fun render(")
        assertTrue(render.contains("window?.updateText(snapshot, failureTextFor(snapshot))"))
        assertTrue(render.contains("applyWindowState(snapshot, computed.first, computed.second)"))

        assertFalse(controller.contains("assistantHistory"))
        assertFalse(controller.contains("messageId"))
        assertFalse(controller.contains("Thread.sleep"))
        assertTrue(controller.contains("retireCurrentRoundForReplacement()"))
        val dismiss = methodBody(controller, "    private fun dismissInternal(")
        assertTrue(dismiss.contains("val finishedToken = token"))
        assertTrue(dismiss.indexOf("val finishedToken = token") < dismiss.indexOf("token = null"))
        assertTrue(dismiss.contains("notifyFinished(finishedToken, reason)"))
    }

    @Test
    fun windowStateChangeIsTheOnlyPathThatTouchesWindowManagerDimensions() {
        val window = source("src/main/java/$modulePath/BubblePreviewWindow.kt")
        val updateText = methodBody(window, "    fun updateText(")
        assertFalse(updateText.contains("updateViewLayout"))
        assertFalse(updateText.contains("params.width"))
        assertFalse(updateText.contains("params.height"))
        assertFalse(updateText.contains("rebuild()"))

        val apply = methodBody(window, "    fun applyState(")
        assertTrue(apply.contains("rebuild("))
        assertTrue(apply.contains("updateWindow()"))

        val placement = methodBody(window, "    fun updatePlacement(")
        assertTrue(placement.contains("updateWindow()"))
        assertFalse(placement.contains("rebuild()"))

        val updateWindow = methodBody(window, "    private fun updateWindow(")
        assertEquals(1, Regex("updateViewLayout\\(").findAll(updateWindow).count())
    }

    @Test
    fun panelResizeUsesRealInsetsAndKeepsTheHistoryScrollable() {
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val applyWrapper = methodBody(host, "    private fun applyChatPanelInsets()")
        assertTrue(applyWrapper.contains("catch (error: Exception)"))
        assertTrue(applyWrapper.contains("applyChatPanelInsetsContained()"))
        val apply = methodBody(host, "    private fun applyChatPanelInsetsContained()")
        assertTrue(apply.contains("BubblePreviewPanelGeometry.chatPanelFrame("))
        assertTrue(apply.contains("chatVisibleFrame()"))
        assertTrue(apply.contains("compactChatControlsHeightPx()"))
        assertTrue(apply.contains("applyChatPanelCompact(target.compact)"))
        assertTrue(apply.contains("if (lp.height == target.heightPx && lp.topMargin == targetTopMargin) return"))
        assertFalse(apply.contains("screenSize()"))
        assertFalse(apply.contains("currentWindowMetrics"))

        val minimum = methodBody(host, "    private fun minimumChatControlsHeightPx()")
        assertTrue(minimum.contains("includeOptional = true"))
        val controls = methodBody(host, "    private fun chatControlsHeight(")
        assertTrue(controls.contains("chatTitle"))
        assertTrue(controls.contains("chatStatus"))
        assertTrue(controls.contains("chatInput"))
        assertTrue(controls.contains("chatSend"))
        assertTrue(controls.contains("chatStop"))
        assertTrue(controls.contains("chatFull"))
        assertTrue(controls.contains("chatCollapse"))
        val compact = methodBody(host, "    private fun compactChatControlsHeightPx()")
        assertTrue(compact.contains("includeOptional = false"))

        assertFalse(host.contains("dp(300)"))
        assertFalse(host.contains("300L"))
    }
}
