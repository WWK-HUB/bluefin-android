package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverlayLayoutTest {
    @Test
    fun stageHeightExtrapolatesFromTheAnchors() {
        assertEquals(173, OverlayLayout.stageHeightDp(0.50f))
        assertEquals(220, OverlayLayout.stageHeightDp(0.70f))
        assertEquals(255, OverlayLayout.stageHeightDp(0.85f))
        assertEquals(290, OverlayLayout.stageHeightDp(1.00f))
        assertEquals(360, OverlayLayout.stageHeightDp(1.30f))
        assertEquals(238, OverlayLayout.stageHeightDp(0.775f))
        assertEquals(173, OverlayLayout.stageHeightDp(0.20f))
        assertEquals(360, OverlayLayout.stageHeightDp(1.50f))
        assertEquals(255, OverlayLayout.stageHeightDp(Float.NaN))
        assertEquals(220, OverlayLayout.MIN_STAGE_HEIGHT_DP)
        assertEquals(290, OverlayLayout.MAX_STAGE_HEIGHT_DP)
        assertEquals(220, OverlayLayout.stageHeightDp(BluefinStore.SCALE_HEIGHT_ANCHOR_LO))
        assertEquals(290, OverlayLayout.stageHeightDp(BluefinStore.SCALE_HEIGHT_ANCHOR_HI))
    }

    @Test
    fun approvedSizesLandInScaleBandAndKeepFloorInset() {
        listOf(173f, 220f, 238f, 255f, 290f, 360f).forEach { height ->
            val scale = OverlayLayout.preferredScale(360f, height)
            assertTrue("S=$scale at h=$height", scale in 0.48f..1.35f)
        }
        assertEquals(40, OverlayLayout.FLOOR_INSET_PX)
        assertEquals(0.55f, OverlayLayout.preferredScale(360f, 173f))
        assertEquals(0.73f, OverlayLayout.preferredScale(360f, 220f))
        assertEquals(1.0f, OverlayLayout.preferredScale(360f, 290f))
        assertEquals(1.27f, OverlayLayout.preferredScale(360f, 360f))
    }

    @Test
    fun windowIsAtLeast360DpWideWhenTheScreenAllowsIt() {
        val density = 2.75f
        val (width, height) = OverlayLayout.windowSizePx(0.85f, density, 1080, 2400)
        assertEquals((360 * density).toInt(), width)
        val stageDp = OverlayLayout.stageHeightDp(0.85f)
        val expectedHeight = ((stageDp + OverlayLayout.CHROME_DP) * density).let { kotlin.math.round(it).toInt() }
        assertEquals(expectedHeight, height)
        assertTrue(height > width / 4)
    }

    @Test
    fun narrowOrShortScreensStayInsideTheDisplay() {
        val density = 2f
        val (width, _) = OverlayLayout.windowSizePx(1.00f, density, 320 * 2, 2000)
        assertTrue(width <= 320 * 2)
        val (_, shortHeight) = OverlayLayout.windowSizePx(1.00f, density, 1080, 200)
        assertTrue(shortHeight <= 200)

        // The display clamp never rewrites the saved S, so the anchor formula
        // still reports the full 360dp stage for S 1.30.
        val (_, cappedHeight) = OverlayLayout.windowSizePx(1.30f, density, 1080, 400)
        assertTrue(cappedHeight <= 400)
        assertEquals(360, OverlayLayout.stageHeightDp(1.30f))
    }

    @Test
    fun clampKeepsTheWindowOnScreen() {
        assertEquals(0 to 0, OverlayLayout.clamp(-20, -4, 100, 80, 500, 800))
        assertEquals(400 to 700, OverlayLayout.clamp(900, 900, 100, 100, 500, 800))
        assertEquals(12 to 40, OverlayLayout.clamp(12, 40, 100, 100, 500, 800))
        assertEquals(0 to 0, OverlayLayout.clamp(10, 10, 900, 900, 400, 600))
    }

    @Test
    fun orbDefaultsToTheRightAndStaysAboveTheRoamBand() {
        val defaultY = OverlayLayout.defaultOrbYDp(2400, 2.75f)
        val safeBottom = OverlayLayout.orbSafeBottomDp(2400, 2.75f)
        val y = OverlayLayout.clampOrbYDp(defaultY, safeBottom)
        assertEquals(48, OverlayLayout.ORB_SIZE_DP)
        assertTrue(y >= OverlayLayout.ORB_MARGIN_DP)
        assertTrue(y + OverlayLayout.ORB_SIZE_DP <= safeBottom)
        assertEquals(
            safeBottom - OverlayLayout.ORB_SIZE_DP,
            OverlayLayout.clampOrbYDp(10_000f, safeBottom),
        )
        assertEquals(
            OverlayLayout.ORB_MARGIN_DP.toFloat(),
            OverlayLayout.clampOrbYDp(Float.NaN, safeBottom),
        )
    }

    @Test
    fun orbClampUsesTheCurrentOrbSize() {
        val tiny36 = OverlayLayout.orbSafeBottomDp(200, 2f, 36f)
        val tiny64 = OverlayLayout.orbSafeBottomDp(200, 2f, 64f)
        assertEquals(96f, tiny36)
        assertEquals(124f, tiny64)

        val y36 = OverlayLayout.clampOrbYDp(10_000f, tiny36, 36f)
        val y64 = OverlayLayout.clampOrbYDp(10_000f, tiny64, 64f)
        assertEquals(60f, y36)
        assertEquals(60f, y64)
        assertTrue(y36 + 36f <= tiny36)
        assertTrue(y64 + 64f <= tiny64)
        assertTrue(y36 >= OverlayLayout.ORB_MARGIN_DP)
        assertTrue(y64 >= OverlayLayout.ORB_MARGIN_DP)

        val normal64 = OverlayLayout.orbSafeBottomDp(2400, 2.75f, 64f)
        val default64 = OverlayLayout.clampOrbYDp(
            OverlayLayout.defaultOrbYDp(2400, 2.75f),
            normal64,
            64f,
        )
        assertTrue(default64 + 64f <= normal64)
        assertEquals(
            OverlayLayout.ORB_MARGIN_DP.toFloat(),
            OverlayLayout.clampOrbYDp(Float.NaN, normal64, 64f),
        )
    }

    @Test
    fun initialPositionIsClamped() {
        val (x, y) = OverlayLayout.initialPosition(300, 200, 1000, 1800, 2f)
        val (cx, cy) = OverlayLayout.clamp(x, y, 300, 200, 1000, 1800)
        assertEquals(cx, x)
        assertEquals(cy, y)
        assertTrue(x >= 0 && y >= 0)
        assertTrue(x + 300 <= 1000)
        assertTrue(y + 200 <= 1800)
    }

    @Test
    fun toggleAsksForPermissionOnlyWhenHiddenAndUngranted() {
        assertEquals(OverlayLayout.Toggle.Hide, OverlayLayout.decideToggle(showing = true, canDrawOverlays = true))
        assertEquals(OverlayLayout.Toggle.Hide, OverlayLayout.decideToggle(showing = true, canDrawOverlays = false))
        assertEquals(OverlayLayout.Toggle.Show, OverlayLayout.decideToggle(showing = false, canDrawOverlays = true))
        assertEquals(
            OverlayLayout.Toggle.NeedPermission,
            OverlayLayout.decideToggle(showing = false, canDrawOverlays = false),
        )
    }

    @Test
    fun landscapeOrbPanelUsesTheShortSideWithoutThePortraitRoamClearance() {
        val density = 2f
        assertTrue(OverlayLayout.isLandscape(1280, 720))
        assertFalse(OverlayLayout.isLandscape(720, 1280))

        val landscape = OverlayLayout.orbPanelHeightPx(720, density, landscape = true)
        val portrait = OverlayLayout.orbPanelHeightPx(2400, density, landscape = false)
        // 720px - 48dp top - 12dp bottom margin, not safeBottom - top.
        assertEquals(720 - (OverlayLayout.ORB_SAFE_TOP_DP * 2) - (OverlayLayout.ORB_MARGIN_DP * 2), landscape)
        assertTrue(landscape > OverlayLayout.ORB_PANEL_MIN_HEIGHT_DP * 2)
        assertEquals(OverlayLayout.ORB_PANEL_MAX_HEIGHT_DP * 2, portrait)
        assertEquals(
            OverlayLayout.ORB_SAFE_TOP_DP * 2,
            OverlayLayout.orbPanelTopPx(720, density, true, landscape, 60f),
        )
    }

    @Test
    fun orbPanelStaysBesideTheOrbAndInsideTheScreen() {
        val density = 2f
        assertEquals(
            OverlayLayout.ORB_PANEL_WIDTH_DP * 2,
            OverlayLayout.orbPanelWidthPx(1280, density, 48f),
        )
        val narrow = OverlayLayout.orbPanelWidthPx(400, density, 64f)
        assertEquals(400 - 64 * 2 - OverlayLayout.ORB_MARGIN_DP * 2 * 2, narrow)
        assertTrue(narrow > 0)
    }

    @Test
    fun windowDoesNotAcceptTouchThroughFlags() {
        assertTrue(OverlayLayout.WINDOW_FLAGS and OverlayLayout.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(OverlayLayout.WINDOW_FLAGS and OverlayLayout.FLAG_NOT_TOUCH_MODAL != 0)
        assertEquals(0, OverlayLayout.WINDOW_FLAGS and OverlayLayout.FLAG_NOT_TOUCHABLE)
        assertEquals(2038, OverlayLayout.TYPE_APPLICATION_OVERLAY)
    }

    @Test
    fun stageAlphaStaysInsideAndroid12ObscuredTouchBudget() {
        assertEquals(1f, OverlayLayout.stageAlpha(30))
        assertEquals(0.8f, OverlayLayout.stageAlpha(31))
        assertEquals(0.8f, OverlayLayout.stageAlpha(34))
        assertEquals(0.8f, OverlayLayout.MAX_OBSCURING_OPACITY_FOR_TOUCH)
        listOf(31, 32, 33, 34, 35).forEach { sdk ->
            assertTrue(
                "stage alpha on API $sdk",
                OverlayLayout.stageAlpha(sdk) <= OverlayLayout.MAX_OBSCURING_OPACITY_FOR_TOUCH,
            )
        }
    }

    @Test
    fun stageAlphaHonorsTheQueriedMaximumWithinBounds() {
        assertEquals(1f, OverlayLayout.stageAlpha(30, 0.4f))
        assertEquals(0.8f, OverlayLayout.stageAlpha(31))
        assertEquals(1f, OverlayLayout.stageAlpha(31, 1f))
        assertEquals(0.55f, OverlayLayout.stageAlpha(31, 0.55f))
        assertEquals(0f, OverlayLayout.stageAlpha(31, -1f))
        assertEquals(0.8f, OverlayLayout.stageAlpha(31, Float.NaN))
        assertEquals(0.8f, OverlayLayout.stageAlpha(31, Float.POSITIVE_INFINITY))
    }

    @Test
    fun petOpacityMatchesTheSettingUpToTheWindowLimit() {
        val stage = OverlayLayout.stageAlpha(31)
        assertEquals(0.50f, stage * OverlayLayout.characterOpacity(0.50f, stage), 0.0001f)
        assertEquals(1f, OverlayLayout.characterOpacity(1f, stage))
        assertEquals(0.8f, OverlayLayout.effectivePetOpacity(1f, stage))
        assertEquals(0f, OverlayLayout.characterOpacity(0f, stage))
        assertEquals(0f, OverlayLayout.characterOpacity(1f, 0f))
        assertEquals(1f, OverlayLayout.effectivePetOpacity(1f, OverlayLayout.stageAlpha(30)))
    }

    @Test
    fun onlyTheRenderStageIsNotTouchable() {
        val stageFlags = OverlayLayout.WINDOW_FLAGS or OverlayLayout.FLAG_NOT_TOUCHABLE
        assertTrue(stageFlags and OverlayLayout.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(0, OverlayLayout.WINDOW_FLAGS and OverlayLayout.FLAG_NOT_TOUCHABLE)
        assertTrue(OverlayLayout.WINDOW_FLAGS and OverlayLayout.FLAG_NOT_TOUCH_MODAL != 0)
    }

    @Test
    fun auditTitlesAreStableDistinctAndDescribeEachLayer() {
        val titles = listOf(
            OverlayLayout.WINDOW_TITLE_STAGE,
            OverlayLayout.WINDOW_TITLE_PET,
            OverlayLayout.WINDOW_TITLE_ORB,
        )
        assertEquals(
            listOf("BluefinOverlay:stage", "BluefinOverlay:pet", "BluefinOverlay:orb"),
            titles,
        )
        assertEquals(titles.size, titles.toSet().size)
        assertEquals(3, OverlayLayout.EXPECTED_WINDOW_COUNT)
    }

    @Test
    fun denialDoesNotRearmTheGuide() {
        val session = OverlayPermissionSession()
        assertEquals(OverlayPermissionSession.Result.Idle, session.onResume(canDraw = false))
        session.onDeclinedInApp()
        assertEquals(OverlayPermissionSession.Result.Idle, session.onResume(canDraw = false))

        session.onOpenSettings()
        assertEquals(OverlayPermissionSession.Result.Denied, session.onResume(canDraw = false))
        assertEquals(OverlayPermissionSession.Result.Idle, session.onResume(canDraw = false))
        assertFalse(session.awaitingSettings)
        assertTrue(session.promptDismissed)
    }

    @Test
    fun declinedPromptSurvivesANewSessionUntilReset() {
        val first = OverlayPermissionSession()
        first.onDeclinedInApp()
        val restored = OverlayPermissionSession(
            awaiting = first.awaitingSettings,
            dismissed = first.promptDismissed,
        )
        assertFalse(restored.shouldOfferGuide())
        assertEquals(OverlayPermissionSession.Result.Idle, restored.onResume(canDraw = false))
        restored.onResetPrompt()
        assertTrue(restored.shouldOfferGuide())
        assertFalse(restored.promptDismissed)
    }

    @Test
    fun returningFromSettingsShowsOnce() {
        val session = OverlayPermissionSession()
        session.onOpenSettings()
        assertEquals(OverlayPermissionSession.Result.Granted, session.onResume(canDraw = true))
        assertEquals(OverlayPermissionSession.Result.Idle, session.onResume(canDraw = true))
    }

    @Test
    fun guideCopyAndVersionMatchTheApprovedScope() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("允许蓝鳍显示在其他应用上层？"))
        assertTrue(strings.contains("开启后蓝鳍可以像桌宠一样浮在屏幕上走动、被戳和拖拽。你可以随时在设置里关闭。若暂不允许，仍可在应用内互动。"))
        assertTrue(strings.contains(">去开启<"))
        assertTrue(strings.contains(">先在应用内用<"))
        assertTrue(strings.contains(">显示悬浮窗<"))
        assertTrue(strings.contains(">隐藏悬浮窗<"))
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionName = \"0.8.0-rc1\""))
        assertTrue(gradle.contains("versionCode = 23"))
        assertTrue(strings.contains("重新询问悬浮窗权限"))
        assertTrue(strings.contains(">透明度<"))
        assertTrue(strings.contains(">实<"))
        assertTrue(strings.contains(">透<"))
        assertFalse(strings.contains(">淡<"))
        assertTrue(strings.contains(">隐藏悬浮窗<"))
        assertTrue(strings.contains(">回到应用<"))
        assertTrue(strings.contains("小球大小"))
        assertFalse(strings.contains("拖动顶部把手"))
        assertFalse(strings.contains("蓝鳐"))
        assertFalse(strings.contains("蓝鱿"))
        assertFalse(strings.contains("蓝鳞"))
        assertFalse(strings.contains("蓝鲗"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW\" tools:node=\"remove\""))
    }
}
