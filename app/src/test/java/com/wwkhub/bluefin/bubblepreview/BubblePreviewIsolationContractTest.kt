package com.wwkhub.bluefin.bubblepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Stage-B source/reachability contract. It replaces the obsolete whole-file
 * "no host reference" assertion: a nullable inert controller field is allowed,
 * while the synchronous rc3 opening methods stay hash-locked and the optional
 * graph may only be reached from an explicit overlay-chat STARTED action.
 */
class BubblePreviewIsolationContractTest {
    private val modulePath = "com/wwkhub/bluefin/bubblepreview"
    private val baselineCommit = "aea44cc2a89a167596355ff1b0e46eeceb82e175"
    private val expectedLauncherSha =
        "0361d4428c8e8a2aac639568c0650e6faa3fe574a11c6d389a9ca7abc73a3904"

    private fun source(path: String): String = File(path).readText()

    private fun moduleSources(): List<File> {
        val dir = File("src/main/java/$modulePath")
        assertTrue("missing $dir", dir.isDirectory)
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("module skeleton is empty", files.isNotEmpty())
        return files
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

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
    fun masterGateReleaseContractKeepsAClosedPredicateInert() {
        // Final release default (not test-faked): the shipped module is on.
        assertTrue(BubblePreviewModule.MASTER_GATE_ENABLED)
        assertTrue(BubblePreviewModule.isEnabled())
        assertEquals(3, BubblePreviewModule.FAILURE_THRESHOLD)
        assertEquals(2_000L, BubblePreviewModule.SUSTAINED_SUCCESS_MS)
        assertEquals("BluefinOverlay:bubble-preview", BubblePreviewModule.AUDIT_WINDOW_TITLE)

        var factoryCalls = 0
        val closed = BubblePreviewModule.whenEnabled(false) {
            factoryCalls += 1
            "must not be constructed"
        }
        assertNull(closed)
        assertEquals("a closed gate must not run any factory", 0, factoryCalls)

        val open = BubblePreviewModule.whenEnabled(true) {
            factoryCalls += 1
            "constructed"
        }
        assertEquals("constructed", open)
        assertEquals(1, factoryCalls)
    }

    @Test
    fun launcherRemainsByteIdenticalToTheApprovedBaseline() {
        val launcher = source("src/main/java/com/wwkhub/bluefin/OverlayLauncher.kt")
        assertEquals(
            "OverlayLauncher.kt changed from baseline $baselineCommit",
            expectedLauncherSha,
            sha256(launcher),
        )
        assertFalse(launcher.contains("BubblePreview"))
        assertFalse(launcher.contains("bubblepreview"))
    }

    @Test
    fun explicitStartIsTheOnlyControllerConstructionSite() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val send = methodBody(window, "    private fun sendFromOverlay() {")
        assertTrue(send.contains("session.send(text)"))
        assertTrue(send.contains("scheduleBubblePreviewAfterStarted("))
        assertFalse(send.contains("BubblePreviewController("))

        val schedule = methodBody(window, "    private fun scheduleBubblePreviewAfterStarted(")
        val gate = schedule.indexOf("BubblePreviewModule.isEnabled()")
        val pref = schedule.indexOf("readBubblePreview")
        val circuit = schedule.indexOf("BubblePreviewExplicitChatEntry.failureCircuitOrNull()")
        val construct = schedule.indexOf("BubblePreviewController(")
        val startedPost = schedule.indexOf("handler.post")
        assertTrue(startedPost in 0 until gate)
        assertTrue(gate in 0 until pref)
        assertTrue(pref in 0 until circuit)
        assertTrue(circuit in 0 until construct)
        assertTrue(schedule.contains("Settings.canDrawOverlays"))
        val start = methodBody(window, "    private fun startAcceptedBubbleRound(")
        assertTrue(start.contains("controller.showStarted("))
        assertTrue(start.contains("attachBubbleChatListener(session)"))

        val outsideModule = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains(modulePath) }
            .toList()
        val constructorSites = outsideModule.filter { file ->
            file.readText().contains("BubblePreviewController(")
        }
        assertEquals(
            "only OverlayWindow may construct the controller: $constructorSites",
            listOf("OverlayWindow.kt"),
            constructorSites.map { it.name },
        )
    }

    @Test
    fun synchronousOpeningAndPageLoadPathsDoNotTouchTheOptionalModule() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        listOf(
            "    fun show() {",
            "    private fun layoutOrb() {",
            "    private fun applyStageSize() {",
            "    private fun screenSize(): Pair<Int, Int> {",
            "    private fun setupOrb() {",
            "    private fun setupWebView() {",
            "    private fun inflateRoot(): FrameLayout {",
            "    fun syncFromPrefs() {",
        ).forEach { signature ->
            val body = methodBody(window, signature)
            assertFalse("$signature must not construct the module", body.contains("BubblePreviewController("))
            assertFalse("$signature must not construct the window", body.contains("BubblePreviewWindow("))
            assertFalse("$signature must not read the bubble pref", body.contains("readBubblePreview"))
            // rc3's screenSize reads WindowMetrics by design and is locked byte-for-byte
            // by OverlayOpeningBaselineContractTest. Other opening methods must not add reads.
            if (!signature.contains("fun screenSize")) {
                assertFalse("$signature must not load window metrics", body.contains("currentWindowMetrics"))
            }
            assertFalse("$signature must not add/remove bubble views", body.contains("bubble-preview"))
            assertFalse("$signature must not touch lazy process state", body.contains("BubblePreviewProcessSession"))
            assertFalse("$signature must not touch the explicit entry", body.contains("BubblePreviewExplicitChatEntry"))
        }
    }

    @Test
    fun bubbleFeedRunsAfterPetEdgeAndSleepHandling() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val layout = methodBody(window, "    private fun onLayout(")
        val placeHit = layout.indexOf("placeHit(parsed)")
        val edge = layout.indexOf("OverlayPetPolicy.edgeTarget")
        val sleep = layout.indexOf("OverlayPetPolicy.shouldStartSleep")
        val feed = layout.indexOf("publishBubbleHostTail(parsed, now)")
        assertTrue(placeHit in 0 until edge)
        assertTrue(edge in 0 until sleep)
        assertTrue(sleep in 0 until feed)
        assertFalse(layout.contains("publishBubbleGeometry("))

        val tail = methodBody(window, "    private fun publishBubbleHostTail(")
        assertTrue(tail.contains("bubbleSignalToken == null && bubbleFirstAttach == null"))
        assertTrue(tail.contains("catch (error: Exception)"))

        val publish = methodBody(window, "    private fun publishBubbleGeometry(")
        assertTrue(publish.contains("web.getLocationOnScreen(location)"))
        assertTrue(publish.contains("hitParams.x - bubbleHitBaseLeft"))
        assertTrue(publish.contains("BubblePreviewHitGeometry.characterBounds(hits)"))
        assertFalse(publish.contains("12f * d"))
        assertFalse(publish.contains("screenSize()"))
    }

    @Test
    fun permissionClickThroughSwitchAndCircuitAreRecheckedBeforeAdd() {
        val controller = source(
            "src/main/java/$modulePath/BubblePreviewController.kt",
        )
        val eligible = methodBody(controller, "    private fun isEligible(")
        listOf(
            "BubblePreviewModule.isEnabled()",
            "readBubblePreview",
            "circuit.isTripped()",
            "attempt?.isFailed()",
            "frame.stageAttached",
            "frame.pageReady",
            "frame.clickThrough",
            "frame.hidden",
            "Settings.canDrawOverlays(context)",
        ).forEach { token ->
            assertTrue("isEligible must contain $token", eligible.contains(token))
        }
        val attach = methodBody(controller, "    private fun tryAttach(")
        val create = attach.indexOf("BubblePreviewOperation.CREATE")
        val recheck = attach.indexOf("if (!isEligible(frame))", create)
        val add = attach.indexOf("BubblePreviewOperation.ADD", recheck)
        assertTrue(create in 0 until recheck)
        assertTrue(recheck in 0 until add)
        assertTrue(attach.contains("candidate.add()"))
    }

    @Test
    fun clickThroughAndHostUiSuppressionClearTheRoundWithoutReplay() {
        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        val click = methodBody(controller, "    fun onClickThroughEnabled()")
        assertTrue(click.contains("clearRound = true"))
        assertTrue(click.contains("CLICK_THROUGH"))
        val layout = methodBody(controller, "    fun onHostLayout(")
        assertTrue(layout.indexOf("if (frame.clickThrough)") < layout.indexOf("computePlacement("))
        assertTrue(layout.contains("clearRound = true"))
        assertTrue(layout.contains("suspendForGeometry"))

        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        assertTrue(window.contains("runBubbleHostCallback(\"click-through\")"))
        assertTrue(window.contains("it.onClickThroughEnabled()"))
        assertTrue(window.contains("dismissBubblePreview()"))
        assertTrue(methodBody(window, "    private fun dismissBubblePreview()").contains("dismissForHostUi()"))
        assertFalse(controller.contains("session.stop()"))
    }

    @Test
    fun oneCompactTouchableTranslucentWindowWithNoFullScreenWatchersOrService() {
        val window = source("src/main/java/$modulePath/BubblePreviewWindow.kt")
        assertTrue(window.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"))
        assertTrue(window.contains("PixelFormat.TRANSLUCENT"))
        assertTrue(window.contains("FLAG_NOT_FOCUSABLE"))
        assertTrue(window.contains("FLAG_NOT_TOUCH_MODAL"))
        assertTrue(window.contains("FLAG_LAYOUT_IN_SCREEN"))
        assertTrue(window.contains("Gravity.TOP or Gravity.LEFT"))
        assertFalse(window.contains("FLAG_NOT_TOUCHABLE"))
        assertFalse(window.contains("WATCH_OUTSIDE_TOUCH"))
        assertFalse(window.contains("FLAG_DIM_BEHIND"))
        assertFalse(window.contains("dimAmount"))
        assertFalse(window.contains("MATCH_PARENT,\n            WindowManager.LayoutParams"))
        assertFalse(window.contains("extends Service"))
        assertFalse(window.contains("startService("))
        assertTrue(window.contains("getSystemService(InputMethodManager::class.java)"))
        assertTrue(window.contains("plan.windowWidthPx"))
        assertTrue(window.contains("plan.windowHeightPx"))
        assertTrue(window.contains("title = BubblePreviewModule.AUDIT_WINDOW_TITLE"))
        assertEquals(
            1,
            Regex("class BubblePreviewWindow\\b").findAll(window).count(),
        )

        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        // One speech window at a time: the reply and input faces each have one
        // bounded constructor site inside the same controller.
        assertEquals(2, Regex("BubblePreviewWindow\\(").findAll(controller).count())
    }

    @Test
    fun geometryAndThrottleBudgetsAreBoundedAndPure() {
        val placement = source("src/main/java/$modulePath/BubblePreviewPlacementPolicy.kt")
        assertTrue(placement.contains("MAX_CANDIDATES_PER_PLAN = 4"))
        assertTrue(placement.contains("MAX_PLANS = 2"))
        assertTrue(placement.contains("SIDE_HYSTERESIS_DP = 16"))
        assertTrue(placement.contains("SIDE_HYSTERESIS_MS = 200L"))
        assertFalse(placement.contains("while ("))
        assertTrue(placement.contains("plans.take(MAX_PLANS)"))
        assertTrue(placement.contains("sideOrder"))

        val update = source("src/main/java/$modulePath/BubblePreviewUpdatePolicy.kt")
        assertTrue(update.contains("minimumIntervalMs: Long = 50L"))
        assertTrue(update.contains("minimumDeltaPx: Int = 2"))
        assertTrue(update.contains("minimumIntervalMs - elapsed"))

        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        assertTrue(controller.contains("MINIMUM_UPDATE_DELTA_DP = 2f"))
        assertTrue(controller.contains("ceil(MINIMUM_UPDATE_DELTA_DP * density)"))

        val scale = source("src/main/java/$modulePath/BubblePreviewScalePolicy.kt")
        assertTrue(scale.contains("WIDTH_SAFE_FRACTION = 0.72f"))
        assertTrue(scale.contains("HEIGHT_CAP_DP = 220f"))
        assertTrue(scale.contains("HEIGHT_SAFE_FRACTION = 0.28f"))
        assertTrue(scale.contains("ACTION_TARGET_DP = 44f"))
        assertTrue(scale.contains("add(4 to true)"))
        assertTrue(scale.contains("add(2 to true)"))
        assertTrue(scale.contains("add(2 to false)"))
        assertFalse(scale.contains("while ("))
    }

    @Test
    fun metricsAreCapturedOncePerGeometryEpochNotPerLayoutFrame() {
        val window = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val capture = methodBody(window, "    private fun bubbleScreenSnapshot(")
        assertTrue(capture.contains("if (bubbleScreenSnapshotGeneration == geometryGeneration)"))
        assertTrue(capture.contains("bubbleScreenSnapshotGeneration = geometryGeneration"))
        assertTrue(capture.contains("captureBubbleScreenSnapshot()"))
        assertTrue(window.contains("currentWindowMetrics"))
        assertFalse(window.contains("screenSize() to capture"))
        assertFalse(window.contains("dp(300)"))
        assertTrue(window.contains("rootWindowInsets"))

        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        assertFalse(controller.contains("currentWindowMetrics"))
        assertFalse(controller.contains("screenSize"))
    }

    @Test
    fun cleanupIsBoundedHidesTheRootAndCannotSkipHostCleanup() {
        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        val remove = methodBody(controller, "    private fun removeWindow()")
        assertTrue(remove.indexOf("hideForCleanup()") < remove.indexOf("removeImmediateOnce()"))
        assertTrue(remove.contains("BubblePreviewOperation.CLEANUP"))
        assertTrue(remove.contains("BubblePreviewOperation.REMOVE, attempt"))
        assertTrue(remove.contains("retainOrphan(current)"))
        assertTrue(controller.contains("orphanWindow"))
        assertFalse(remove.contains("while ("))
        assertFalse(controller.contains("Thread.sleep"))

        val retain = methodBody(controller, "    private fun retainOrphan(")
        assertTrue(retain.contains("unresolvedCleanup = true"))
        assertTrue(retain.contains("failedRound = true"))

        val window = source("src/main/java/$modulePath/BubblePreviewWindow.kt")
        assertTrue(window.contains("const val MAX_ATTEMPTS: Int = 2"))
        assertTrue(window.contains("root.isAttachedToWindow"))
        assertTrue(window.contains("BubblePreviewRemovalPolicy.remove"))

        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val close = methodBody(host, "    fun close() {")
        assertTrue(close.contains("bubble?.close()"))
        assertTrue(close.contains("finally"))
        assertTrue(close.indexOf("bubble?.close()") < close.indexOf("chatSession?.stop()"))
        assertTrue(close.contains("hasUnresolvedCleanup()"))
    }

    @Test
    fun settingIsPersistedOnAndTheChangePathNeverConstructsTheModule() {
        val store = source("src/main/java/com/wwkhub/bluefin/BluefinStore.kt")
        assertTrue(store.contains("KEY_BUBBLE_PREVIEW = \"bubble_preview\""))
        assertTrue(store.contains("prefs.getBoolean(KEY_BUBBLE_PREVIEW, true)"))
        val layout = source("src/main/res/layout/sheet_settings.xml")
        assertEquals(
            1,
            Regex("android:id=\"@\\+id/bubble_preview_switch\"").findAll(layout).count(),
        )
        assertTrue(layout.contains("@string/bubble_preview"))
        assertTrue(layout.contains("@string/bubble_preview_help"))
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(strings.contains(">对话气泡<"))
        assertTrue(strings.contains("在角色旁预览本轮对话。点击穿透开启时不显示。"))

        val activity = source("src/main/java/com/wwkhub/bluefin/MainActivity.kt")
        assertFalse(activity.contains("BubblePreviewController("))
        assertFalse(activity.contains("BubblePreviewWindow("))
        assertTrue(activity.contains("BluefinStore.writeBubblePreview"))
        assertTrue(activity.contains("OverlayAppVisibility.setForeground(true)"))
        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        assertTrue(controller.contains("KEY_BUBBLE_PREVIEW"))
        assertTrue(controller.contains("registerOnSharedPreferenceChangeListener"))
    }

    @Test
    fun formalBubbleAssetsAreImportedWithTheStageDRenames() {
        val res = File("src/main/res")
        val expected = setOf(
            "drawable/bf_bubble_user_body.xml",
            "drawable/bf_bubble_character_body.xml",
            "drawable/bf_bubble_chip.xml",
            "drawable/bf_bubble_tail_down.xml",
            "drawable/bf_bubble_tail_up.xml",
            "drawable/bf_bubble_tail_left.xml",
            "drawable/bf_bubble_tail_right.xml",
            "drawable/bf_bubble_ic_close.xml",
            "drawable/bf_bubble_ic_error.xml",
            "drawable/bf_bubble_ic_stop.xml",
            "drawable/bf_bubble_ic_record.xml",
            "drawable/bf_bubble_dots_static.xml",
            "drawable/bf_bubble_dots.xml",
            "drawable/bf_bubble_ellipsis_static.xml",
            "anim/bf_bubble_enter.xml",
            "anim/bf_bubble_exit.xml",
            "anim/bf_bubble_crossfade.xml",
            "animator/bf_bubble_dot_1.xml",
            "animator/bf_bubble_dot_2.xml",
            "animator/bf_bubble_dot_3.xml",
            "values/bf_bubble_config.xml",
            "values/bf_bubble_strings.xml",
            "values/bf_bubble_colors.xml",
            "values/bf_bubble_dimens.xml",
            "values-night/bf_bubble_colors.xml",
        )
        val actual = res.walkTopDown()
            .filter { it.isFile && it.name.contains("bf_bubble", ignoreCase = true) }
            .map { it.relativeTo(res).invariantSeparatorsPath }
            .toSet()
        assertEquals("only the 25 runtime bubble files belong in res", expected, actual)
        assertFalse(File("src/main/res/values/colors.xml").readText().contains("bf_bubble"))
        File("src/main/res/values/dimens.xml").takeIf { it.isFile }?.let {
            assertFalse(it.readText().contains("bf_bubble"))
        }
        assertFalse(File("src/main/res/values/strings.xml").readText().contains("bf_bubble"))

        val allSources = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val legacyNames = listOf("OverlayBubbles", "BubblePolicy", "BubbleView")
        allSources.forEach { file ->
            val text = file.readText()
            legacyNames.forEach { legacy ->
                assertFalse("${file.path} must not revive $legacy", text.contains(legacy))
            }
        }
    }

    @Test
    fun latePostedCallbacksCannotReviveADismissedRound() {
        val controller = source("src/main/java/$modulePath/BubblePreviewController.kt")
        assertTrue(controller.contains("roundGeneration"))
        assertTrue(controller.contains("generation != roundGeneration"))

        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        assertTrue(host.contains("bubbleRequestToken"))
        assertTrue(host.contains("requestToken != bubbleRequestToken"))
        assertTrue(methodBody(host, "    private fun dismissBubblePreview()").contains("bubbleRequestToken += 1"))
    }

    @Test
    fun allExceptionContainmentLeavesNoRethrowWhitelist() {
        moduleSources().forEach { file ->
            val text = file.readText()
            assertFalse("${file.path} must not whitelist/rethrow", text.contains("isRecoverable"))
            assertFalse("${file.path} must not whitelist types", text.contains("recoverableNames"))
        }
        val host = source("src/main/java/com/wwkhub/bluefin/OverlayWindow.kt")
        val publish = methodBody(host, "    private fun publishBubbleGeometry(")
        assertTrue(publish.contains("catch (error: Exception)"))
        assertFalse(publish.contains("RuntimeException"))
        val capture = methodBody(host, "    private fun bubbleScreenSnapshot(")
        assertTrue(capture.contains("BubblePreviewMeasure.capture { captureBubbleScreenSnapshot() }"))
        assertTrue(capture.contains("recordBubbleOptionalFailure()"))
        assertFalse(capture.contains("RuntimeException"))
        assertFalse(capture.contains("catch (error: Throwable)"))
    }

    @Test
    fun optionalModuleDoesNotCatchThrowableOrHideVmErrors() {
        moduleSources().forEach { file ->
            val text = file.readText()
            assertFalse("${file.path} must not catch Throwable", text.contains("catch (Throwable"))
            assertFalse("${file.path} must not catch Throwable", text.contains("catch(Throwable"))
            assertFalse("${file.path} must not catch VM errors", text.contains("StackOverflowError"))
            assertFalse("${file.path} must not read credentials", text.contains("ApiKeyVault"))
            assertFalse("${file.path} must not read chat history", text.contains("TranscriptFile"))
            assertFalse("${file.path} must not call session.stop", text.contains("session.stop"))
            assertFalse("${file.path} must not name a size callback screenSize", text.contains("screenSize"))
        }
    }
}
