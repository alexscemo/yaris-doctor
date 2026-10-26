package com.yarisdoctor

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * Single-series chart: a 2dp line on a time axis, or columns by index. A line chart given a gap limit
 * collapses pauses longer than it (the car parked), so the line runs on driving time and stays continuous. The top row reads out the
 * latest point, or the touched one (tap/drag horizontally to inspect). Pinch zooms the x axis, two fingers pan,
 * double-tap resets. A line chart can shade time ranges (regens) behind the data.
 */
class ChartView(ctx: Context, private val columns: Boolean, private val p: Colors) : View(ctx) {

    /** Resolved colour tokens; see Ui.chartColors(). [surface] is the card the chart sits on. */
    class Colors(val surface: Int, val series: Int, val marker: Int, val grid: Int, val baseline: Int, val axisText: Int, val readout: Int, val band: Int)

    private var xs = LongArray(0)
    private var ys = FloatArray(0)
    private var pos = DoubleArray(0) // each point's place on the x axis: its time, less the collapsed gaps before it
    private var bands: List<Pair<Long, Long>> = emptyList()
    private var readoutOf: (Int) -> String = { "" }
    private var axisLabel: (Long) -> String = { "" }
    private var sel = -1
    private var selX: Long? = null // touched point's x, so it survives refreshes that shift indices
    private var downX = 0f
    private var downY = 0f
    private var yMax = 1f
    private var reveal = 1f     // share of the plot drawn, left to right
    private var revealed = false
    private var revealAnim: ValueAnimator? = null
    // Visible share of the x range, 0..1: a window kept as fractions so new data on the right stays in view when vHi = 1
    private var vLo = 0f
    private var vHi = 1f
    private var pinching = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var lastFocus = 0f
        override fun onScaleBegin(g: ScaleGestureDetector): Boolean {
            pinching = true; lastFocus = g.focusX
            parent.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(g: ScaleGestureDetector): Boolean {
            val span = vHi - vLo
            val at = vLo + span * ((g.focusX - left) / (right - left)).coerceIn(0f, 1f) // the fraction under the fingers stays put
            val next = (span / g.scaleFactor).coerceIn(MIN_SPAN, 1f)
            val pan = (lastFocus - g.focusX) / (right - left) * next
            lastFocus = g.focusX
            var lo = at - (at - vLo) * next / span + pan
            lo = lo.coerceIn(0f, 1f - next)
            vLo = lo; vHi = lo + next
            invalidate()
            return true
        }
    })
    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { vLo = 0f; vHi = 1f; sel = -1; selX = null; invalidate(); return true }
    })

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; color = p.series
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.series }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.surface }
    private val grid = Paint().apply { strokeWidth = 1f; color = p.grid }
    private val axis = Paint().apply { strokeWidth = 1f; color = p.baseline }
    private val band = Paint().apply { color = p.band }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.marker }
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; color = p.axisText; typeface = Ui.mono(ctx) }
    private val readout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * d; color = p.readout; typeface = Ui.sans(ctx); fontVariationSettings = "'wght' 600"
    }
    private val path = Path()
    private val rect = RectF()

    init {
        // Interactive readout only, so the summary is the whole story for TalkBack: focusable, not clickable
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // Plot area, set in onDraw
    private var left = 0f
    private var right = 0f
    private var top = 0f
    private var bottom = 0f

    /**
     * [bands]: time ranges (start, end) shaded behind a line chart; columns ignore them.
     * [gapMs]: on a line chart, a pause between two points longer than this takes no width, so the
     * line stays continuous across it (0 = a plain time axis).
     */
    fun set(xs: LongArray, ys: FloatArray, readoutOf: (Int) -> String, axisLabel: (Long) -> String, bands: List<Pair<Long, Long>> = emptyList(), gapMs: Long = 0) {
        this.xs = xs; this.ys = ys; this.readoutOf = readoutOf; this.axisLabel = axisLabel; this.bands = bands
        pos = DoubleArray(xs.size)
        for (i in 1 until xs.size) {
            val dt = xs[i] - xs[i - 1]
            pos[i] = pos[i - 1] + if (gapMs > 0 && dt > gapMs) 0.0 else dt.toDouble()
        }
        sel = selX?.let { xs.indexOf(it) } ?: -1
        yMax = niceMax(ys.maxOrNull() ?: 0f)
        // First data: the series draws in left to right (600 ms) so the time direction reads at once
        if (!revealed && ys.isNotEmpty()) {
            revealed = true
            if (Ui.animationsOn()) revealAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600; interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        // Data summary for TalkBack: name (from the readout), points, span, last/min/max. Never epoch millis.
        contentDescription = chartDescription(if (ys.isEmpty()) "" else chartLabel(readoutOf(ys.lastIndex)), xs, ys, axisLabel)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        revealAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        // Animations turned off mid-reveal: show the whole series rather than a frozen clip
        if (reveal < 1f && !Ui.animationsOn()) {
            revealAnim?.cancel()
            reveal = 1f
        }
        left = 36 * d; right = width - 8 * d; top = 28 * d; bottom = height - 20 * d
        if (ys.isEmpty()) {
            c.drawText("No data yet", left, top + (bottom - top) / 2, muted)
            return
        }
        // At least 3dp wide, or an 11-minute regen vanishes on a 14-day axis
        if (!columns && xs.size > 1 && pos.last() > 0.0) for ((a, b) in bands) {
            val x0 = xAt(a).coerceAtLeast(left)
            val x1 = maxOf(xAt(b), xAt(a) + 3 * d).coerceAtMost(right)
            if (x1 > x0) c.drawRect(x0, top, x1, bottom, band)
        }
        // Recessive grid: 0, half, max
        for (f in floatArrayOf(0.5f, 1f)) c.drawLine(left, yOf(yMax * f), right, yOf(yMax * f), grid)
        for (f in floatArrayOf(0f, 0.5f, 1f)) c.drawText(fmtTick(yMax * f), 0f, yOf(yMax * f) + 4 * d, muted)
        c.drawLine(left, bottom, right, bottom, axis)

        c.save()
        c.clipRect(left - 8 * d, 0f, minOf(right + 8 * d, left + (right - left + 8 * d) * reveal), height.toFloat())
        if (columns) drawColumns(c) else drawLine(c)
        c.restore()

        val seen = visible()
        if (!seen.isEmpty()) {
            val first = axisLabel(xs[seen.first])
            val last = axisLabel(xs[seen.last])
            c.drawText(first, left, height - 4 * d, muted)
            if (seen.last > seen.first) c.drawText(last, right - muted.measureText(last), height - 4 * d, muted)
        }
        if (vHi - vLo < 1f) "×%.0f · double-tap resets".format(1f / (vHi - vLo)).let { c.drawText(it, (left + right - muted.measureText(it)) / 2, height - 4 * d, muted) } // between the axis ends
        val shown = if (sel >= 0) sel else seen.last.coerceAtLeast(0)
        c.drawText(readoutOf(shown), left, 16 * d, readout)
    }

    private fun drawLine(c: Canvas) {
        path.reset()
        for (i in ys.indices) if (i == 0) path.moveTo(xOf(i), yOf(ys[i])) else path.lineTo(xOf(i), yOf(ys[i]))
        c.drawPath(path, line)
        val i = if (sel >= 0) sel else visible().last.coerceAtLeast(0)
        if (sel >= 0) c.drawLine(xOf(i), top, xOf(i), bottom, axis)
        // 8dp marker with a 2dp surface ring
        c.drawCircle(xOf(i), yOf(ys[i]), 6 * d, ring)
        c.drawCircle(xOf(i), yOf(ys[i]), 4 * d, dotPaint)
    }

    private fun drawColumns(c: Canvas) {
        val slot = (right - left) / ys.size / (vHi - vLo)
        val w = min(24 * d, slot - 2 * d)
        for (i in ys.indices) {
            val h = bottom - yOf(ys[i])
            val r = min(4 * d, min(w / 2, h))
            rect.set(xOf(i) - w / 2, yOf(ys[i]), xOf(i) + w / 2, bottom)
            path.reset()
            // Rounded data end, square at the baseline
            path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
            fill.alpha = if (sel < 0 || sel == i) 255 else 110
            c.drawPath(path, fill)
        }
        fill.alpha = 255
    }

    /** Where point [i] sits along the whole x range, 0..1. */
    private fun frac(i: Int): Float = when {
        columns -> (i + 0.5f) / ys.size
        pos.size == 1 || pos.last() <= 0.0 -> 0.5f
        else -> (pos[i] / pos.last()).toFloat()
    }

    /** Time [t] as a place on the x axis: interpolated between the points around it; inside a collapsed gap, the gap's single place. */
    private fun posAt(t: Long): Double {
        if (t <= xs.first()) return 0.0
        if (t >= xs.last()) return pos.last()
        var i = xs.binarySearch(t)
        if (i >= 0) return pos[i]
        i = -i - 1 // xs[i - 1] < t < xs[i]
        val span = pos[i] - pos[i - 1]
        return pos[i - 1] + span * (t - xs[i - 1]) / (xs[i] - xs[i - 1])
    }

    private fun xOf(i: Int): Float = left + (right - left) * (frac(i) - vLo) / (vHi - vLo)

    /** Where time [t] sits on a line chart, in pixels (off the plot when outside the zoom window). */
    private fun xAt(t: Long): Float = left + (right - left) * ((posAt(t) / pos.last()).toFloat() - vLo) / (vHi - vLo)

    /** Indices inside the zoom window (empty only when there is no data). */
    private fun visible(): IntRange {
        val lo = ys.indices.firstOrNull { frac(it) >= vLo } ?: return IntRange.EMPTY
        val hi = ys.indices.lastOrNull { frac(it) <= vHi } ?: return IntRange.EMPTY
        return if (hi >= lo) lo..hi else IntRange.EMPTY
    }

    private fun yOf(v: Float) = bottom - (bottom - top) * (v / yMax)

    @SuppressLint("ClickableViewAccessibility") // readout-only; values are also in contentDescription and the history list
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (ys.isEmpty()) return false
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pinching = false; downX = e.x; downY = e.y; select(e.x) }
            // Claim the gesture only when it is horizontal, so vertical drags still scroll the page
            MotionEvent.ACTION_MOVE -> if (!pinching && e.pointerCount == 1 && abs(e.x - downX) > abs(e.y - downY)) {
                parent.requestDisallowInterceptTouchEvent(true)
                select(e.x)
            }
        }
        return true
    }

    private fun select(x: Float) {
        sel = visible().takeIf { !it.isEmpty() }?.minBy { abs(xOf(it) - x) } ?: return
        selX = xs[sel]
        invalidate()
    }

    private companion object { const val MIN_SPAN = 0.01f } // ×100 at most

    private fun fmtTick(v: Float) = if (yMax < 10) "%.1f".format(v) else "%.0f".format(v)

    private fun niceMax(v: Float): Float {
        if (v <= 0f) return 1f
        val mag = 10f.pow(floor(log10(v)))
        return floatArrayOf(1f, 2f, 2.5f, 5f, 10f).first { it * mag >= v } * mag
    }
}
