package com.yarisdoctor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/** Light/dark tokens from the dataviz reference palette (series = categorical slot 1, blue). */
class Palette(val surface: Int, val text: Int, val secondary: Int, val muted: Int, val grid: Int, val baseline: Int, val series: Int) {
    companion object {
        val LIGHT = Palette(0xFFFCFCFB.toInt(), 0xFF0B0B0B.toInt(), 0xFF52514E.toInt(), 0xFF898781.toInt(), 0xFFE1E0D9.toInt(), 0xFFC3C2B7.toInt(), 0xFF2A78D6.toInt())
        val DARK = Palette(0xFF1A1A19.toInt(), 0xFFF0EFEC.toInt(), 0xFFC3C2B7.toInt(), 0xFF898781.toInt(), 0xFF2C2C2A.toInt(), 0xFF383835.toInt(), 0xFF3987E5.toInt())
    }
}

/**
 * Single-series chart: a 2dp line on a time axis, or columns by index. The top row reads out the
 * latest point, or the touched one (tap/drag horizontally to inspect).
 */
class ChartView(ctx: Context, private val columns: Boolean, private val p: Palette) : View(ctx) {

    private var xs = LongArray(0)
    private var ys = FloatArray(0)
    private var readoutOf: (Int) -> String = { "" }
    private var axisLabel: (Long) -> String = { "" }
    private var sel = -1
    private var downX = 0f
    private var downY = 0f
    private var yMax = 1f

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; color = p.series
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.series }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.surface }
    private val grid = Paint().apply { strokeWidth = 1f; color = p.grid }
    private val axis = Paint().apply { strokeWidth = 1f; color = p.baseline }
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; color = p.muted }
    private val readout = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * d; color = p.text }
    private val path = Path()
    private val rect = RectF()

    // Plot area, set in onDraw
    private var left = 0f
    private var right = 0f
    private var top = 0f
    private var bottom = 0f

    fun set(xs: LongArray, ys: FloatArray, readoutOf: (Int) -> String, axisLabel: (Long) -> String) {
        this.xs = xs; this.ys = ys; this.readoutOf = readoutOf; this.axisLabel = axisLabel
        sel = -1
        yMax = niceMax(ys.maxOrNull() ?: 0f)
        contentDescription = if (ys.isEmpty()) "No data yet" else "Latest: " + readoutOf(ys.lastIndex)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        left = 36 * d; right = width - 8 * d; top = 28 * d; bottom = height - 20 * d
        if (ys.isEmpty()) {
            c.drawText("No data yet", left, top + (bottom - top) / 2, muted)
            return
        }
        // Recessive grid: 0, half, max
        for (f in floatArrayOf(0.5f, 1f)) c.drawLine(left, yOf(yMax * f), right, yOf(yMax * f), grid)
        for (f in floatArrayOf(0f, 0.5f, 1f)) c.drawText(fmtTick(yMax * f), 0f, yOf(yMax * f) + 4 * d, muted)
        c.drawLine(left, bottom, right, bottom, axis)

        if (columns) drawColumns(c) else drawLine(c)

        val first = axisLabel(xs.first())
        val last = axisLabel(xs.last())
        c.drawText(first, left, height - 4 * d, muted)
        if (xs.size > 1) c.drawText(last, right - muted.measureText(last), height - 4 * d, muted)
        val shown = if (sel >= 0) sel else ys.lastIndex
        c.drawText(readoutOf(shown), left, 16 * d, readout)
    }

    private fun drawLine(c: Canvas) {
        path.reset()
        for (i in ys.indices) if (i == 0) path.moveTo(xOf(i), yOf(ys[i])) else path.lineTo(xOf(i), yOf(ys[i]))
        c.drawPath(path, line)
        val i = if (sel >= 0) sel else ys.lastIndex
        if (sel >= 0) c.drawLine(xOf(i), top, xOf(i), bottom, axis)
        // 8dp marker with a 2dp surface ring
        c.drawCircle(xOf(i), yOf(ys[i]), 6 * d, ring)
        c.drawCircle(xOf(i), yOf(ys[i]), 4 * d, fill)
    }

    private fun drawColumns(c: Canvas) {
        val slot = (right - left) / ys.size
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

    private fun xOf(i: Int): Float = when {
        columns -> left + (right - left) / ys.size * (i + 0.5f)
        xs.size == 1 || xs.last() == xs.first() -> (left + right) / 2
        else -> left + (right - left) * (xs[i] - xs.first()) / (xs.last() - xs.first()).toFloat()
    }

    private fun yOf(v: Float) = bottom - (bottom - top) * (v / yMax)

    @SuppressLint("ClickableViewAccessibility") // readout-only; values are also in contentDescription and the history list
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (ys.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; select(e.x) }
            // Claim the gesture only when it is horizontal, so vertical drags still scroll the page
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > abs(e.y - downY)) {
                parent.requestDisallowInterceptTouchEvent(true)
                select(e.x)
            }
        }
        return true
    }

    private fun select(x: Float) {
        sel = ys.indices.minBy { abs(xOf(it) - x) }
        invalidate()
    }

    private fun fmtTick(v: Float) = if (yMax < 10) "%.1f".format(v) else "%.0f".format(v)

    private fun niceMax(v: Float): Float {
        if (v <= 0f) return 1f
        val mag = 10f.pow(floor(log10(v)))
        return floatArrayOf(1f, 2f, 2.5f, 5f, 10f).first { it * mag >= v } * mag
    }
}
