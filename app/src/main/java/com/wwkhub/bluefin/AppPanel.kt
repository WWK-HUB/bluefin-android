package com.wwkhub.bluefin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/** A full-height surface with a fixed toolbar and a separately scrolling body. */
@SuppressLint("ViewConstructor") // Created in code with an explicit navigation callback.
internal class AppPanel(context: Context, onBack: () -> Unit) : LinearLayout(context) {
    private val toolbar = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(8), 0, dp(12), 0)
        setBackgroundColor(context.getColor(R.color.paper))
    }
    val back = ImageButton(context).apply {
        id = R.id.settings_back
        contentDescription = context.getString(R.string.panel_back)
        setImageResource(R.drawable.ic_panel_back)
        selectable()
        setOnClickListener { onBack() }
    }
    val title = label("", 21, R.color.ink, bold = true).apply {
        id = R.id.settings_title
        ViewCompat.setAccessibilityHeading(this, true)
    }
    val action = AppCompatButton(context, null, android.R.attr.borderlessButtonStyle).apply {
        visibility = View.GONE
        minHeight = dp(48)
        setTextColor(context.getColor(R.color.accent))
        isAllCaps = false
    }
    val content = FrameLayout(context)

    init {
        orientation = VERTICAL
        isClickable = true
        isFocusableInTouchMode = true
        setBackgroundColor(context.getColor(R.color.ice))
        toolbar.addView(back, LayoutParams(dp(48), dp(48)))
        toolbar.addView(title, LayoutParams(0, WRAP_CONTENT, 1f))
        toolbar.addView(action)
        addView(toolbar, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(View(context).apply { setBackgroundColor(context.getColor(R.color.blue_line)) }, LayoutParams(MATCH_PARENT, dp(1)))
        addView(content, LayoutParams(MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
    }

    fun scroll(body: LinearLayout) = ScrollView(context).apply {
        id = R.id.settings_scroll
        isFillViewport = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun column() = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(28))
        isFocusableInTouchMode = true
    }

    fun card(color: Int = R.color.paper) = GradientDrawable().apply {
        setColor(context.getColor(color))
        cornerRadius = dp(18).toFloat()
    }

    fun label(text: CharSequence, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(context.getColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun View.selectable() {
        val value = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            setBackgroundResource(value.resourceId)
        }
    }

    fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
