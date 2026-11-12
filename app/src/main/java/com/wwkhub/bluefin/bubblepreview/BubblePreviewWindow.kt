package com.wwkhub.bluefin.bubblepreview

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.wwkhub.bluefin.PackBubbleColors
import com.wwkhub.bluefin.R
import com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewPackPaletteInput
import com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewPalette
import com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewTailDirection
import com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewTailGeometry
import com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewTailRect
import com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewTailRequest
import kotlin.math.ceil
import kotlin.math.max
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
 * The one compact, read-only native preview window.
 *
 * Formal 0.7 shape/tail/icon/dot resources are used for the default day/night
 * palette. A cached pack palette may override only the character zone; those
 * dynamic colors use a lightweight Canvas path so fill and stroke stay separate
 * (tinting a whole vector would merge them).
 *
 * The window is always content-sized (never MATCH_PARENT), NOT_FOCUSABLE,
 * NOT_TOUCH_MODAL, translucent and touchable only inside its own rectangle.
 * There is no full-screen layer, dim, outside-touch watcher, EditText or
 * additional service.
 *
 * Text-only updates never touch WindowManager dimensions. A state/plan change
 * performs exactly one in-window rebuild and one `updateViewLayout`.
 */
internal class BubblePreviewWindow(
    context: Context,
    private val windowManager: WindowManager,
    initialContent: BubbleTurnSnapshot,
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
    private val palette = BubblePreviewPalette.resolve(night, packInput)
    private val density = context.resources.displayMetrics.density.coerceAtLeast(0.5f)
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
        .takeIf { it.isFinite() && it > 0f } ?: density
    private val root = FrameLayout(context)
    private var content: BubbleTurnSnapshot = initialContent
    private var failureText: String = initialFailureText
    private var plan: BubblePreviewLayoutPlan = initialPlan
    private var side: BubblePreviewSide = placement.side
    private var anchor: BubblePreviewPoint? = initialAnchor
    private var bodyView: TextView? = null
    private var chipView: TextView? = null
    private var thinkingDots: ImageView? = null
    private var contentView: View? = null
    private var tailView: View? = null
    private var closingForUser = false
    private var exitRunnable: Runnable? = null
    private val params = WindowManager.LayoutParams(
        plan.windowWidthPx,
        plan.windowHeightPx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            // Same-display TOP|LEFT origin: placement rects are screen pixels,
            // so the compact window must be laid out against the full display
            // origin rather than a content area shifted by system bars.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )
    private val displayLeft = geometry.displayBounds.left
    private val displayTop = geometry.displayBounds.top
    private var appliedX = placement.rect.left - displayLeft
    private var appliedY = placement.rect.top - displayTop

    init {
        root.setBackgroundColor(Color.TRANSPARENT)
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
        rebuild(animate = false)
    }

    fun add() {
        windowManager.addView(root, params)
        root.post {
            if (!destroyedForCleanup && animationsEnabled()) {
                try {
                    contentView?.startAnimation(
                        AnimationUtils.loadAnimation(root.context, R.anim.bf_bubble_enter),
                    )
                } catch (error: Exception) {
                    // A broken animator resource must not leave the add path
                    // uncontained; the window is already visible and usable.
                    contentView?.clearAnimation()
                }
            }
        }
    }

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
        // Any side change (including ABOVE->BELOW or LEFT->RIGHT on the same
        // axis) changes which tail resource is needed. A fresh pet anchor can
        // move the tail along the body even when the side is unchanged. Both
        // only rearrange children inside the already-sized window; they never
        // call WindowManager a second time for the same frame.
        if (sideChanged || anchorChanged) layoutVisual(animate = false)
        windowManager.updateViewLayout(root, params)
    }

    /**
     * One state/plan change: exactly one in-window rebuild and one window
     * geometry update. Text-only deltas must use [updateText] instead.
     */
    fun applyState(
        snapshot: BubbleTurnSnapshot,
        failure: String,
        nextPlan: BubblePreviewLayoutPlan,
        placement: BubblePreviewPlacement,
        nextAnchor: BubblePreviewPoint?,
    ) {
        if (destroyedForCleanup || closingForUser) return
        val wasChip = isChip()
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
        val nowChip = isChip()
        rebuild(animate = wasChip != nowChip)
        windowManager.updateViewLayout(root, params)
    }

    /** Text-only update: no rebuild, no WindowManager call, no re-measure of bounds. */
    fun updateText(snapshot: BubbleTurnSnapshot, failure: String) {
        if (destroyedForCleanup || closingForUser) return
        content = snapshot
        failureText = failure
        val body = bodyView
        if (body != null) {
            body.text = bodyText()
            applyBodyDecoration(body)
        }
        val chip = chipView
        if (chip != null) {
            chip.text = chipText()
        }
        updateThinkingDots()
        root.contentDescription = rootDescription()
    }

    fun currentX(): Int = appliedX

    fun currentY(): Int = appliedY

    /** Hides the root before the bounded removal attempt, so no touch/visual leak remains. */
    fun hideForCleanup() {
        destroyedForCleanup = true
        closingForUser = false
        exitRunnable?.let { root.removeCallbacks(it) }
        exitRunnable = null
        stopThinkingDots()
        contentView?.clearAnimation()
        contentView = null
        root.visibility = View.GONE
        root.isClickable = false
        bodyView = null
        chipView = null
        tailView = null
    }

    fun removeImmediateOnce() {
        val outcome = BubblePreviewRemovalPolicy.remove(
            object : BubblePreviewRemovalPort {
                // Parent evidence is deliberately checked first: a window added
                // successfully (or partially during a throwing add) is managed
                // before first traversal even when isAttachedToWindow is false.
                override fun isManaged(): Boolean =
                    root.parent != null || root.isAttachedToWindow

                override fun removeOnce() {
                    windowManager.removeViewImmediate(root)
                }
            },
        )
        if (!outcome.removed) {
            // Rethrowing the original exception keeps the boundary's sanitized
            // type-only fault accurate; a still-attached root without an error
            // is itself a bounded cleanup failure.
            throw outcome.lastError ?: IllegalStateException("bubble root still attached")
        }
    }

    fun view(): View = root

    private var destroyedForCleanup = false

    private fun rebuild(animate: Boolean) {
        stopThinkingDots()
        bodyView = null
        chipView = null
        thinkingDots = null
        tailView = null
        contentView = null
        root.removeAllViews()
        val built: View = if (isChip()) {
            buildChip(root.context)
        } else {
            buildColumn(root.context)
        }
        contentView = built
        root.addView(built, FrameLayout.LayoutParams(plan.windowWidthPx, plan.windowHeightPx))
        layoutVisual(animate = animate)
        root.setOnClickListener {
            if (isChip()) {
                if (content.isFailureChip) onRecord() else onExpand()
            } else {
                onRecord()
            }
        }
        root.contentDescription = rootDescription()
    }

    private fun layoutVisual(animate: Boolean) {
        root.removeView(tailView)
        tailView = null
        val built = contentView ?: return
        if (isChip()) {
            built.layoutParams = FrameLayout.LayoutParams(
                plan.windowWidthPx,
                plan.windowHeightPx,
            )
        } else {
            applyColumnLayout(built as LinearLayout)
        }
        if (animate) {
            if (animationsEnabled()) {
                built.startAnimation(
                    AnimationUtils.loadAnimation(root.context, R.anim.bf_bubble_crossfade),
                )
            }
        }
    }

    /**
     * Resolve the worst-case tail reserve into an actual body rectangle and a
     * tail view. If the tail cannot fit (narrow/short body, UP with a user
     * summary, or an off-window rectangle), the body expands into the reserve
     * so there is no transparent margin.
     */
    private fun applyColumnLayout(column: LinearLayout) {
        val userHeight = if (plan.includeUserSummary) {
            ceil(2f * plan.padVerticalPx + plan.userLineHeightPx).toInt().coerceAtLeast(1)
        } else {
            0
        }
        val gap = if (plan.includeUserSummary) {
            ceil(plan.zoneGapPx).toInt().coerceAtLeast(1)
        } else {
            0
        }
        val userOffset = userHeight + gap
        val reserve = ceil(plan.tailBudgetPx.toDouble()).toInt().coerceAtLeast(0)
        val direction = tailDirectionFor(side)
        val windowWidth = plan.windowWidthPx
        val windowHeight = plan.windowHeightPx

        // Reserve the tail strip on the side it actually extends toward. An UP
        // tail is never requested through a user summary, so that case keeps no
        // top strip and the character body fills the window.
        val topReserve = if (direction == BubblePreviewTailDirection.UP &&
            !plan.includeUserSummary
        ) {
            reserve
        } else {
            0
        }
        val bottomReserve =
            if (direction == BubblePreviewTailDirection.DOWN) reserve else 0
        val leftReserve =
            if (direction == BubblePreviewTailDirection.LEFT) reserve else 0
        val rightReserve =
            if (direction == BubblePreviewTailDirection.RIGHT) reserve else 0

        // The tail attaches to the character zone, not to the user summary.
        val candidateBody = BubblePreviewTailRect(
            left = leftReserve.toFloat(),
            top = (if (topReserve > 0) topReserve else userOffset).toFloat(),
            right = (windowWidth - rightReserve).toFloat(),
            bottom = (windowHeight - bottomReserve).toFloat(),
        )
        val resolved = resolveTail(direction, candidateBody)
        val visibleTail = resolved?.rect

        val columnLeft: Int
        val columnTop: Int
        val columnRight: Int
        val columnBottom: Int
        if (visibleTail != null) {
            columnLeft = leftReserve
            columnTop = topReserve
            columnRight = windowWidth - rightReserve
            columnBottom = windowHeight - bottomReserve
            addTailView(visibleTail, candidateBody.left.toInt(), candidateBody.top.toInt())
        } else {
            // No room for the tail: expand the character body into the whole
            // reserve instead of leaving an empty transparent margin.
            columnLeft = 0
            columnTop = 0
            columnRight = windowWidth
            columnBottom = windowHeight
        }
        val columnWidth = (columnRight - columnLeft).coerceAtLeast(1)
        val columnHeight = (columnBottom - columnTop).coerceAtLeast(1)
        val characterTop = if (visibleTail != null) {
            candidateBody.top.toInt()
        } else {
            userOffset
        }
        val characterHeight = (columnBottom - characterTop).coerceAtLeast(1)
        val naturalCharacter = (column.getChildAt(column.childCount - 1) as? View)
        naturalCharacter?.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            characterHeight,
        )
        if (naturalCharacter is LinearLayout && naturalCharacter.childCount >= 4) {
            // Rasterised child boxes can exceed a float plan height by a few
            // pixels after ceil(). Reserve the name/actions/padding first and
            // let the body slot absorb the rounding, so buttons are never
            // clipped by the tail plan.
            val fixed = ceil(plan.nameLineHeightPx).toInt() +
                ceil(plan.actionGapPx).toInt() +
                plan.actionRowPx +
                ceil(2f * plan.padVerticalPx).toInt()
            val bodyRow = naturalCharacter.getChildAt(1)
            val bodyHeight = (characterHeight - fixed).coerceAtLeast(1)
            bodyRow.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                bodyHeight,
            )
        }
        column.layoutParams = FrameLayout.LayoutParams(columnWidth, columnHeight).apply {
            leftMargin = columnLeft
            topMargin = columnTop
        }
        column.measure(
            View.MeasureSpec.makeMeasureSpec(columnWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(columnHeight, View.MeasureSpec.EXACTLY),
        )
        column.layout(columnLeft, columnTop, columnLeft + columnWidth, columnTop + columnHeight)
        column.requestLayout()
    }

    private fun resolveTail(
        direction: BubblePreviewTailDirection,
        body: BubblePreviewTailRect,
    ): com.wwkhub.bluefin.bubblepreview.visual.BubblePreviewTailLayout? {
        if (direction == BubblePreviewTailDirection.NONE) return null
        val point = anchor
        // Tail geometry works in the same coordinate space as the body: the
        // window's root-relative pixels, not raw screen coordinates. The root
        // origin is the display origin plus the window's currently applied
        // position; omitting appliedX/Y shifts the tail toward an edge whenever
        // the compact window is not at the display origin.
        val rootOriginX = displayLeft + appliedX
        val rootOriginY = displayTop + appliedY
        val anchorX = (point?.x?.minus(rootOriginX)) ?: (body.left + body.width / 2f)
        val anchorY = (point?.y?.minus(rootOriginY)) ?: (body.top + body.height / 2f)
        val resolved = BubblePreviewTailGeometry.resolve(
            BubblePreviewTailRequest(
                direction = direction,
                characterBody = body,
                anchorX = anchorX,
                anchorY = anchorY,
                characterScale = characterScaleForPlan(),
                density = density,
                hasUserSummary = plan.includeUserSummary,
            ),
        )
        val rect = resolved.rect ?: return null
        val rootLeft = body.left + rect.left
        val rootTop = body.top + rect.top
        if (!rootLeft.isFinite() || !rootTop.isFinite()) return null
        if (rootLeft < -0.5f || rootTop < -0.5f) return null
        if (rootLeft + rect.width > plan.windowWidthPx + 0.5f) return null
        if (rootTop + rect.height > plan.windowHeightPx + 0.5f) return null
        return resolved
    }

    private fun characterScaleForPlan(): Float {
        // Invert the k scale used by the layout plan: k = clamp(S/0.85,...).
        val base = plan.cornerRadiusPx / (BubblePreviewScalePolicy.CORNER_DP * density)
        return (base * 0.85f).coerceIn(0.50f, 1.30f)
    }

    private fun addTailView(
        rect: BubblePreviewTailRect,
        bodyLeft: Int,
        bodyTop: Int,
    ) {
        val direction = tailDirectionFor(side)
        val tailWidth = ceil(rect.width.toDouble()).toInt().coerceAtLeast(1)
        val tailHeight = ceil(rect.height.toDouble()).toInt().coerceAtLeast(1)
        val view: View = if (palette.usesPackCharacterColors) {
            BubbleTailView(
                context = root.context,
                direction = direction,
                fillColor = palette.character.fill.argb,
                strokeColor = palette.character.line.argb,
                density = density,
            )
        } else {
            ImageView(root.context).apply {
                setImageResource(tailResource(direction))
                scaleType = ImageView.ScaleType.FIT_XY
            }
        }
        tailView = view
        root.addView(
            view,
            FrameLayout.LayoutParams(tailWidth, tailHeight).apply {
                leftMargin = (bodyLeft + rect.left).roundToInt()
                topMargin = (bodyTop + rect.top).roundToInt()
            },
        )
    }

    private fun tailResource(direction: BubblePreviewTailDirection): Int = when (direction) {
        BubblePreviewTailDirection.DOWN -> R.drawable.bf_bubble_tail_down
        BubblePreviewTailDirection.UP -> R.drawable.bf_bubble_tail_up
        BubblePreviewTailDirection.LEFT -> R.drawable.bf_bubble_tail_left
        BubblePreviewTailDirection.RIGHT -> R.drawable.bf_bubble_tail_right
        BubblePreviewTailDirection.NONE -> R.drawable.bf_bubble_tail_down
    }

    private fun tailDirectionFor(side: BubblePreviewSide): BubblePreviewTailDirection =
        when (side) {
            // The tail points from the bubble toward the pet.
            BubblePreviewSide.ABOVE -> BubblePreviewTailDirection.DOWN
            BubblePreviewSide.BELOW -> BubblePreviewTailDirection.UP
            BubblePreviewSide.RIGHT -> BubblePreviewTailDirection.LEFT
            BubblePreviewSide.LEFT -> BubblePreviewTailDirection.RIGHT
        }

    private fun isChip(): Boolean =
        content.visibleState == BubbleVisibleState.COLLAPSED || plan.collapsedChip

    private fun rootDescription(): String = when {
        isChip() && content.isFailureChip -> "打开对话记录"
        isChip() -> "展开气泡"
        else -> "打开对话记录"
    }

    private fun buildColumn(context: Context): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val userHeight = if (plan.includeUserSummary) {
            ceil(2f * plan.padVerticalPx + plan.userLineHeightPx).toInt().coerceAtLeast(1)
        } else {
            0
        }
        if (plan.includeUserSummary) {
            column.addView(
                TextView(context).apply {
                    text = "${root.context.getString(R.string.bf_bubble_user_label)}：${content.userSummary}"
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.bodyTextPx)
                    setTextColor(palette.user.text.argb)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        plan.padHorizontalPx.roundToInt(),
                        0,
                        plan.padHorizontalPx.roundToInt(),
                        0,
                    )
                    background = bodyDrawable(
                        resId = R.drawable.bf_bubble_user_body,
                        fill = palette.user.fill.argb,
                        stroke = palette.user.line.argb,
                        usePack = false,
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        userHeight,
                    )
                },
            )
            column.addView(
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        ceil(plan.zoneGapPx).toInt().coerceAtLeast(1),
                    )
                },
            )
        }

        val character = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                plan.padHorizontalPx.roundToInt(),
                plan.padVerticalPx.roundToInt(),
                plan.padHorizontalPx.roundToInt(),
                plan.padVerticalPx.roundToInt(),
            )
            background = bodyDrawable(
                resId = R.drawable.bf_bubble_character_body,
                fill = palette.character.fill.argb,
                stroke = palette.character.line.argb,
                usePack = palette.usesPackCharacterColors,
            )
        }
        character.addView(
            TextView(context).apply {
                text = content.roleName
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.labelTextPx)
                setTextColor(palette.character.text.argb)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ceil(plan.nameLineHeightPx).toInt(),
                )
            },
        )
        character.addView(buildBody(context))
        character.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ceil(plan.actionGapPx).toInt().coerceAtLeast(1),
                )
            },
        )
        character.addView(buildActions(context))
        column.addView(
            character,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ceil(
                    2f * plan.padVerticalPx + plan.nameLineHeightPx +
                        plan.bodyLines * plan.bodyLineHeightPx +
                        plan.actionGapPx + plan.actionRowPx,
                ).toInt(),
            ),
        )
        return column
    }

    /**
     * Body slot: a single-line thinking row with the formal animated three dots,
     * or the fixed multi-line body text. The slot height never changes with the
     * text content, so a stream delta cannot grow the window.
     */
    private fun buildBody(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ceil(plan.bodyLines.coerceAtLeast(1) * plan.bodyLineHeightPx).toInt(),
            )
        }
        val body = TextView(context).apply {
            text = bodyText()
            maxLines = plan.bodyLines.coerceAtLeast(1)
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.bodyTextPx)
            setTextColor(palette.character.text.argb)
            includeFontPadding = false
            gravity = Gravity.TOP or Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        bodyView = body
        row.addView(body)
        if (content.visibleState == BubbleVisibleState.THINKING) {
            val dots = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(3)).apply {
                    leftMargin = dp(4)
                }
            }
            thinkingDots = dots
            row.addView(dots)
            startThinkingDots(dots)
        }
        applyBodyDecoration(body)
        return row
    }

    private fun applyBodyDecoration(body: TextView) {
        val icon = when {
            content.visibleState == BubbleVisibleState.THINKING -> 0
            content.phase == BubbleTurnPhase.FAILED ||
                content.visibleState == BubbleVisibleState.ERROR ->
                R.drawable.bf_bubble_ic_error
            content.status == BubbleTurnStatus.STOPPED ||
                content.phase == BubbleTurnPhase.STOPPED ->
                R.drawable.bf_bubble_ic_stop
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
        val shouldThink = content.visibleState == BubbleVisibleState.THINKING
        val dots = thinkingDots
        if (dots == null) return
        dots.visibility = if (shouldThink) View.VISIBLE else View.GONE
        if (shouldThink) {
            startThinkingDots(dots)
        } else {
            (dots.drawable as? Animatable)?.stop()
        }
    }

    private fun startThinkingDots(dots: ImageView) {
        (dots.drawable as? Animatable)?.stop()
        if (animationsEnabled()) {
            dots.setImageResource(R.drawable.bf_bubble_dots)
            (dots.drawable as? Animatable)?.start()
        } else {
            dots.setImageResource(R.drawable.bf_bubble_ellipsis_static)
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

    private fun buildChip(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                plan.padHorizontalPx.roundToInt(),
                0,
                plan.padHorizontalPx.roundToInt(),
                0,
            )
            background = bodyDrawable(
                resId = R.drawable.bf_bubble_chip,
                fill = palette.character.fill.argb,
                stroke = palette.character.line.argb,
                usePack = palette.usesPackCharacterColors,
            )
        }
        val chip = TextView(context).apply {
            text = chipText()
            maxLines = 1
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.bodyTextPx)
            setTextColor(palette.character.text.argb)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, plan.windowHeightPx, 1f)
        }
        chipView = chip
        row.addView(chip)
        row.addView(
            action(
                context = context,
                action = BubblePreviewAction.CLOSE,
                sizePx = plan.windowHeightPx,
            ),
            LinearLayout.LayoutParams(actionWidthPx(BubblePreviewAction.CLOSE), plan.windowHeightPx),
        )
        return row
    }

    private fun buildActions(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                plan.actionRowPx,
            )
        }
        val labels = plan.actionLabels.ifEmpty {
            listOf(BubblePreviewAction.RECORDS, BubblePreviewAction.CLOSE)
        }
        labels.forEach { action ->
            row.addView(
                action(context, action, plan.actionRowPx),
                LinearLayout.LayoutParams(actionWidthPx(action), plan.actionRowPx),
            )
        }
        return row
    }

    private fun action(
        context: Context,
        action: BubblePreviewAction,
        sizePx: Int,
    ): TextView {
        val label = labelFor(action)
        val target = actionWidthPx(action)
        return TextView(context).apply {
            text = label
            contentDescription = descriptionFor(action)
            gravity = Gravity.CENTER
            maxLines = 1
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.labelTextPx)
            setTextColor(
                if (action == BubblePreviewAction.STOP) {
                    palette.character.text.argb
                } else {
                    palette.character.text.argb
                },
            )
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            includeFontPadding = false
            minWidth = target
            minHeight = sizePx
            iconFor(action)?.let { icon ->
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawablePadding = dp(4)
            }
            isClickable = true
            isFocusable = false
            setOnClickListener {
                when (action) {
                    BubblePreviewAction.STOP -> onStop()
                    BubblePreviewAction.VIEW_ALL,
                    BubblePreviewAction.RECORDS,
                    -> onRecord()
                    BubblePreviewAction.CLOSE -> requestUserClose()
                }
            }
        }
    }

    private fun requestUserClose() {
        if (destroyedForCleanup || closingForUser) return
        closingForUser = true
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
            child.startAnimation(AnimationUtils.loadAnimation(root.context, R.anim.bf_bubble_exit))
            exitRunnable = finish
            // Ordinary user close may show the 80 ms exit; every invalidation
            // path (pref, click-through, hidden, revoke) still removes
            // immediately.
            root.postDelayed(finish, EXIT_ANIMATION_MILLIS)
        } catch (error: Exception) {
            finish.run()
        }
    }

    private fun iconFor(action: BubblePreviewAction): Int? = when (action) {
        BubblePreviewAction.STOP -> R.drawable.bf_bubble_ic_stop
        BubblePreviewAction.RECORDS -> R.drawable.bf_bubble_ic_record
        BubblePreviewAction.CLOSE -> R.drawable.bf_bubble_ic_close
        BubblePreviewAction.VIEW_ALL -> null
    }

    /**
     * Real text width plus icon/padding, never below the 44 dp target and never
     * truncated or shrunk. The controller already selected a label set that fits
     * the planned content width (including the side-tail consumption).
     */
    private fun actionWidthPx(action: BubblePreviewAction): Int {
        val minimum = plan.actionRowPx.coerceAtLeast(1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = plan.labelTextPx
        }
        val textWidth = paint.measureText(labelFor(action))
        val padding = 2f * BubblePreviewActionLayout.HORIZONTAL_PADDING_DP * density
        val icon = if (iconFor(action) != null) {
            (BubblePreviewActionLayout.ICON_DP + BubblePreviewActionLayout.ICON_GAP_DP) * density
        } else {
            0f
        }
        return max(minimum, ceil(textWidth + padding + icon).toInt())
    }

    private fun labelFor(action: BubblePreviewAction): String = when (action) {
        BubblePreviewAction.STOP -> root.context.getString(R.string.bf_bubble_stop)
        BubblePreviewAction.VIEW_ALL -> root.context.getString(R.string.bf_bubble_view_all)
        BubblePreviewAction.RECORDS -> root.context.getString(R.string.bf_bubble_record)
        BubblePreviewAction.CLOSE -> ""
    }

    private fun descriptionFor(action: BubblePreviewAction): String = when (action) {
        BubblePreviewAction.STOP -> root.context.getString(R.string.bf_bubble_stop_cd)
        BubblePreviewAction.VIEW_ALL -> root.context.getString(R.string.bf_bubble_view_all_cd)
        BubblePreviewAction.RECORDS -> root.context.getString(R.string.bf_bubble_record_cd)
        BubblePreviewAction.CLOSE -> root.context.getString(R.string.bf_bubble_close_cd)
    }

    private fun bodyText(): String {
        val visible = content.visibleText
        val state = content.visibleState
        if (state == BubbleVisibleState.THINKING) {
            return root.context.getString(R.string.bf_bubble_thinking)
        }
        if (state == BubbleVisibleState.ERROR) {
            val failure = failureText.ifBlank {
                root.context.getString(R.string.bf_bubble_no_content)
            }
            return if (visible.isBlank()) failure else visible + "\n" + failure
        }
        var text = visible
        if (content.status == BubbleTurnStatus.STOPPED || content.phase == BubbleTurnPhase.STOPPED) {
            val suffix = root.context.getString(R.string.bf_bubble_stopped_suffix)
            text = if (text.isBlank()) {
                root.context.getString(R.string.bf_bubble_stopped)
            } else {
                text + "\n" + suffix
            }
        }
        if ((content.hasMore || content.overflow) && text.isNotBlank() && !text.endsWith("…")) {
            text += "…"
        }
        return text.ifBlank { root.context.getString(R.string.bf_bubble_thinking) }
    }

    private fun chipText(): String {
        if (content.isFailureChip) return root.context.getString(R.string.bf_bubble_error_chip)
        val visible = content.visibleText.ifBlank {
            if (content.status == BubbleTurnStatus.STOPPED) {
                root.context.getString(R.string.bf_bubble_stopped)
            } else {
                "…"
            }
        }
        return root.context.getString(
            R.string.bf_bubble_chip_format,
            content.roleName,
            visible,
        )
    }

    /**
     * Default day/night modes use the formal shape resource (mutating only the
     * k-scaled corner radius). A dynamic pack overrides only the character zone,
     * so that path uses a runtime GradientDrawable with separate fill/stroke.
     */
    private fun bodyDrawable(
        resId: Int,
        fill: Int,
        stroke: Int,
        usePack: Boolean,
    ): Drawable {
        if (!usePack) {
            val resource = ContextCompat.getDrawable(root.context, resId)?.mutate()
            if (resource is GradientDrawable) {
                resource.cornerRadius = plan.cornerRadiusPx
                return resource
            }
            if (resource != null) return resource
        }
        return rounded(fill, stroke, plan.cornerRadiusPx)
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(1, stroke)
            cornerRadius = radius
        }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}

/**
 * Lightweight Canvas tail for a cached pack palette. The path mirrors the
 * formal bf_bubble_tail_* vector (12x6 / 6x12 viewport) but keeps fill and
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
