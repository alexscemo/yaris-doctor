package com.yarisdoctor

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * DESIGN.md applied to framework views: type scale, radii, and the few components the app uses.
 * Colours come from res/values(-night)/colors.xml so dark mode needs no code here.
 */
class Ui(private val a: Activity) {

    private val d = a.resources.displayMetrics.density

    // shortcut: system serif (Noto Serif) and Roboto stand in for Copernicus / StyreneB; bundle fonts if the look needs to be exact
    enum class Type(val sp: Float, val serif: Boolean, val weight: Int, val trackingPx: Float, val line: Float, val caps: Boolean = false) {
        DISPLAY_MD(36f, true, 400, -0.5f, 1.15f),
        DISPLAY_SM(28f, true, 400, -0.3f, 1.2f),
        TITLE_MD(18f, false, 500, 0f, 1.4f),
        TITLE_SM(16f, false, 500, 0f, 1.4f),
        BODY_MD(16f, false, 400, 0f, 1.55f),
        BODY_SM(14f, false, 400, 0f, 1.55f),
        CAPTION(13f, false, 500, 0f, 1.4f),
        CAPTION_UPPER(12f, false, 500, 1.5f, 1.4f, caps = true),
    }

    fun px(dp: Int) = (dp * d).toInt()
    fun color(id: Int) = a.getColor(id)

    fun text(type: Type, colorId: Int, s: CharSequence = "") = TextView(a).apply {
        text = s
        textSize = type.sp
        setTextColor(color(colorId))
        typeface = Typeface.create(if (type.serif) Typeface.SERIF else Typeface.SANS_SERIF, type.weight, false)
        letterSpacing = type.trackingPx / type.sp // DESIGN.md gives px; TextView wants em
        setLineSpacing(0f, type.line)
        isAllCaps = type.caps
        includeFontPadding = false
    }

    fun shape(fillId: Int, radiusDp: Int, strokeId: Int? = null) = GradientDrawable().apply {
        setColor(color(fillId))
        cornerRadius = radiusDp * d
        if (strokeId != null) setStroke(px(1).coerceAtLeast(1), color(strokeId))
    }

    fun column(vararg children: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it) }
    }

    /** feature-card / product-mockup-card-dark: rounded.lg, padded, no shadow. */
    fun card(fillId: Int, padDp: Int, vararg children: View) = column(*children).apply {
        background = shape(fillId, 12)
        setPadding(px(padDp), px(padDp), px(padDp), px(padDp))
    }

    /** badge-pill (or badge-coral), optionally led by a status dot. */
    fun pill(label: TextView, fillId: Int = R.color.surface_card, dot: View? = null) = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = shape(fillId, 999)
        setPadding(px(12), px(4), px(12), px(4))
        if (dot != null) addView(dot, LinearLayout.LayoutParams(px(8), px(8)).apply { marginEnd = px(8) })
        addView(label)
    }

    /** Filter / tab chip: a pill that is ink when selected. */
    fun chip(label: String, on: Boolean, onClick: () -> Unit) =
        pill(text(Type.CAPTION, if (on) R.color.canvas else R.color.ink, label), if (on) R.color.ink else R.color.surface_card).apply {
            minimumHeight = px(36)
            isClickable = true
            isSelected = on
            ripple(this)
            setOnClickListener { onClick() }
        }

    fun dot(colorId: Int) = View(a).apply { background = shape(colorId, 999) }

    /** button-icon-circular: 36dp cream circle with hairline, inside a 48dp touch target. */
    fun iconButton(iconId: Int, description: String, onClick: () -> Unit) = FrameLayout(a).apply {
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        val circle = ImageView(a).apply {
            setImageResource(iconId)
            setColorFilter(color(R.color.ink))
            background = shape(R.color.canvas, 999, R.color.hairline)
            setPadding(px(8), px(8), px(8), px(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(circle, FrameLayout.LayoutParams(px(36), px(36), Gravity.CENTER))
        layoutParams = ViewGroup.LayoutParams(px(48), px(48))
    }

    /** top-nav: 64dp cream bar with a serif title. */
    fun topBar(title: String, start: View? = null, end: View? = null) = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(64)
        setPadding(px(if (start == null) 16 else 4), 0, px(4), 0)
        if (start != null) addView(start)
        addView(text(Type.DISPLAY_SM, R.color.ink, title).apply { setPadding(px(if (start == null) 0 else 4), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) addView(end)
    }

    /** Opaque tints: the theme's default track is the accent at ~30% alpha, which vanishes on cream. */
    fun styleSwitch(sw: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(color(R.color.on_primary), color(R.color.on_primary)))
        sw.trackTintList = ColorStateList(states, intArrayOf(color(R.color.primary), color(R.color.muted_soft)))
    }

    fun divider(colorId: Int = R.color.hairline_soft) = View(a).apply {
        setBackgroundColor(color(colorId))
        layoutParams = LinearLayout.LayoutParams(-1, px(1).coerceAtLeast(1))
    }

    fun ripple(v: View) {
        val tv = TypedValue().also { a.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        v.foreground = a.getDrawable(tv.resourceId)
    }

    /** Pads [root] by the system bars so the cream canvas runs edge to edge (Android 15 enforces edge-to-edge). */
    fun fitSystemBars(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    fun chartColors() = ChartView.Colors(
        surface = color(R.color.surface_card),
        series = color(R.color.primary),
        marker = color(R.color.ink),
        grid = color(R.color.chart_grid),
        baseline = color(R.color.muted_soft),
        axisText = color(R.color.muted_soft),
        readout = color(R.color.ink),
    )
}
