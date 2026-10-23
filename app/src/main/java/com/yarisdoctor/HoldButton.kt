package com.yarisdoctor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * A destructive confirm that fires [onDone] only after being held for [HOLD_MS]: a band sweeps across it while pressed
 * and resets on release. Timed by the clock, not an animator, because animators finish at once when the system turns
 * animations off, which would make the hold a tap. TalkBack's double-tap-and-hold (a long click) confirms too.
 */
@SuppressLint("ViewConstructor")
class HoldButton(c: Context, label: String, private val onDone: () -> Unit) : TextView(c) {

    // Ink over the fill moves it away from the text colour in both themes: darker under white, lighter under black
    private val sweep = Paint().apply { color = c.getColor(R.color.ink); alpha = 77 }
    private var downAt = -1L
    private var done = false

    private val tick = object : Runnable {
        override fun run() {
            if (downAt < 0 || done) return
            invalidate()
            if (progress() >= 1f) finish() else postOnAnimation(this)
        }
    }

    init {
        text = label
        gravity = Gravity.CENTER
        isClickable = true
        isLongClickable = true
        isFocusable = true
        clipToOutline = true // the band follows the background's rounded corners
        contentDescription = "$label. Hold to confirm"
    }

    private fun progress() = if (downAt < 0) 0f else ((SystemClock.uptimeMillis() - downAt).toFloat() / HOLD_MS).coerceAtMost(1f)

    @SuppressLint("ClickableViewAccessibility") // confirming is a hold, not a click; accessibility gets the long click below
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || done) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = SystemClock.uptimeMillis()
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true) // the dialog's scroll must not steal the hold
                postOnAnimation(tick)
            }
            MotionEvent.ACTION_MOVE -> if (e.x < 0 || e.y < 0 || e.x > width || e.y > height) reset()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    private fun reset() {
        downAt = -1
        isPressed = false
        removeCallbacks(tick)
        invalidate()
    }

    private fun finish() {
        done = true
        isPressed = false
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onDone()
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && isEnabled && !done) {
            finish()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDraw(canvas: Canvas) {
        val p = progress()
        if (p > 0f) canvas.drawRect(0f, 0f, width * p, height.toFloat(), sweep)
        super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    companion object {
        const val HOLD_MS = 1500L
    }
}
