package com.yarisdoctor

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * The design system applied to framework views: type scale, radii, and the few components the app uses.
 * Colours come from res/values(-night)/colors.xml so dark mode needs no code here.
 */
class Ui(private val a: Activity) {

    private val d = a.resources.displayMetrics.density

    /** Hanken Grotesk (variable, weight via font variation) for everything; PT Mono for uppercase technical labels. */
    enum class Type(val sp: Float, val mono: Boolean, val weight: Int, val trackingEm: Float, val line: Float, val caps: Boolean = false) {
        NUMERAL(80f, false, 900, -0.04f, 1.0f),
        HERO(56f, false, 900, -0.035f, 1.0f),
        STAT(32f, false, 800, -0.02f, 1.05f),
        DISPLAY_MD(32f, false, 800, -0.02f, 1.1f),
        DISPLAY_SM(26f, false, 800, -0.015f, 1.15f),
        TITLE_LG(22f, false, 800, -0.01f, 1.2f),
        TITLE_MD(18f, false, 600, 0f, 1.35f),
        TITLE_SM(16f, false, 600, 0f, 1.35f),
        BODY_MD(16f, false, 400, 0f, 1.5f),
        BODY_SM(14f, false, 400, 0f, 1.5f),
        CAPTION(13f, false, 600, 0f, 1.4f),
        CAPTION_UPPER(11f, true, 400, 0.12f, 1.4f, caps = true),
    }

    fun px(dp: Int) = (dp * d).toInt()
    fun color(id: Int) = a.getColor(id)

    fun text(type: Type, colorId: Int, s: CharSequence = "") = TextView(a).apply {
        text = s
        textSize = type.sp
        setTextColor(color(colorId))
        style(this, type)
    }

    fun style(v: TextView, type: Type) {
        v.typeface = if (type.mono) mono(a) else sans(a)
        if (!type.mono) v.fontVariationSettings = "'wght' ${type.weight}"
        v.letterSpacing = type.trackingEm
        v.setLineSpacing(0f, type.line)
        v.isAllCaps = type.caps
        v.includeFontPadding = false
    }

    /** [s] ended by the signal square (the brand's full stop) in [colorId]. Keep the span to pulse or recolour it. */
    fun signal(s: CharSequence, colorId: Int = R.color.primary): Pair<CharSequence, SquareSpan> {
        val span = SquareSpan(color(colorId))
        return signalWith(s, span) to span
    }

    fun signalWith(s: CharSequence, span: SquareSpan): CharSequence =
        SpannableStringBuilder(s).append(" ").apply { setSpan(span, length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    fun shape(fillId: Int, radiusDp: Int, strokeId: Int? = null) = GradientDrawable().apply {
        setColor(color(fillId))
        cornerRadius = radiusDp * d
        if (strokeId != null) setStroke(px(1).coerceAtLeast(1), color(strokeId))
    }

    fun column(vararg children: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it) }
    }

    /** Flat card: 20dp radius, no shadow; light cards get a soft hairline so they hold on the paper canvas. */
    fun card(fillId: Int, padDp: Int, vararg children: View) = column(*children).apply {
        background = shape(fillId, 20, if (fillId == R.color.surface_card) R.color.hairline_soft else null)
        setPadding(px(padDp), px(padDp), px(padDp), px(padDp))
    }

    fun pill(label: TextView, fillId: Int = R.color.surface_alt, dot: View? = null, strokeId: Int? = null) = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = shape(fillId, 999, strokeId)
        setPadding(px(12), px(6), px(12), px(6))
        if (dot != null) addView(dot, LinearLayout.LayoutParams(px(8), px(8)).apply { marginEnd = px(8) })
        addView(label)
    }

    /** Tab / filter chip: an outlined pill, solid signal orange when selected. */
    fun chip(label: String, on: Boolean, onClick: () -> Unit) =
        pill(text(Type.CAPTION, if (on) R.color.on_primary else R.color.ink, label),
            if (on) R.color.primary else R.color.canvas, strokeId = if (on) null else R.color.control_border).apply {
            minimumHeight = px(44)
            setPadding(px(18), 0, px(18), 0)
            isClickable = true
            isSelected = on
            ripple(this)
            setOnClickListener { onClick() }
        }

    fun dot(colorId: Int) = View(a).apply { background = shape(colorId, 999) }

    /** Circular outline icon button: 44dp circle inside a 48dp touch target. */
    fun iconButton(iconId: Int, description: String, onClick: () -> Unit) = FrameLayout(a).apply {
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        val circle = ImageView(a).apply {
            setImageResource(iconId)
            setColorFilter(color(R.color.ink))
            background = shape(R.color.surface_card, 999, R.color.control_border)
            setPadding(px(11), px(11), px(11), px(11))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(circle, FrameLayout.LayoutParams(px(44), px(44), Gravity.CENTER))
        pressScale(this)
        layoutParams = ViewGroup.LayoutParams(px(48), px(48))
    }

    /** Top bar: the title set heavy and ended by the signal square. */
    fun topBar(title: String, start: View? = null, end: View? = null) = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(64)
        setPadding(px(if (start == null) 20 else 8), 0, px(12), 0)
        if (start != null) addView(start)
        addView(text(Type.TITLE_LG, R.color.ink, signal(title).first).apply { setPadding(px(if (start == null) 0 else 8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) addView(end)
    }

    /** Uppercase mono label above a block. */
    fun eyebrow(s: String, colorId: Int = R.color.muted) = text(Type.CAPTION_UPPER, colorId, s)

    /** Opaque tints: the theme's default track is the accent at ~30% alpha, which vanishes on paper. */
    fun styleSwitch(sw: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(color(R.color.on_primary), color(R.color.surface_card)))
        sw.trackTintList = ColorStateList(states, intArrayOf(color(R.color.primary), color(R.color.muted_soft)))
    }

    fun divider(colorId: Int = R.color.hairline_soft) = View(a).apply {
        setBackgroundColor(color(colorId))
        layoutParams = LinearLayout.LayoutParams(-1, px(1).coerceAtLeast(1))
    }

    fun ripple(v: View) {
        val tv = TypedValue().also { a.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        v.foreground = a.getDrawable(tv.resourceId)
        pressScale(v)
    }

    /** Pressed targets settle to 97 % and spring back (120 / 180 ms); none when the system turns animations off. */
    fun pressScale(v: View) {
        if (!animationsOn()) return
        fun to(s: Float, ms: Long) = ObjectAnimator.ofPropertyValuesHolder(v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, s), PropertyValuesHolder.ofFloat(View.SCALE_Y, s)).apply {
            duration = ms; interpolator = DecelerateInterpolator(2f)
        }
        v.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), to(0.97f, 120))
            addState(intArrayOf(), to(1f, 180))
        }
    }

    /** Rises [views] into place in reading order: 520 ms, 70 ms apart (capped at 240 ms), once. */
    fun enter(views: List<View>, dyDp: Int = 16) {
        if (!animationsOn()) return
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = px(dyDp).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(minOf(i * 70L, 240L))
                .setDuration(520).setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    /** Pads [root] by the system bars so the canvas runs edge to edge (Android 15 enforces edge-to-edge). */
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
        baseline = color(R.color.hairline),
        axisText = color(R.color.muted_soft),
        readout = color(R.color.ink),
    )

    /** The brand's full stop: a square on the baseline, 0.16 of the text size. [alpha] pulses it during a regen. */
    class SquareSpan(var color: Int) : ReplacementSpan() {
        var alpha = 1f
        override fun getSize(p: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) =
            (p.textSize * 0.22f).toInt()

        override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
            val side = p.textSize * 0.16f
            val left = x + p.textSize * 0.04f
            val keep = p.color
            p.color = color
            p.alpha = (255 * alpha).toInt()
            c.drawRect(left, y - side, left + side, y.toFloat(), p)
            p.color = keep
        }
    }

    companion object {
        private var sansFace: Typeface? = null
        private var monoFace: Typeface? = null
        fun sans(c: Context): Typeface = sansFace ?: c.resources.getFont(R.font.hanken_grotesk).also { sansFace = it }
        fun mono(c: Context): Typeface = monoFace ?: c.resources.getFont(R.font.pt_mono).also { monoFace = it }
        fun animationsOn() = ValueAnimator.areAnimatorsEnabled()
    }
}
