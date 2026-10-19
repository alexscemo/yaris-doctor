package com.yarisdoctor

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams

/**
 * Coral glow around the screen edges while a regen runs, drawn over other apps (Google Maps while driving), where the
 * notification is easy to miss. Needs "Display over other apps"; without it this does nothing. Safe to call from any thread.
 */
class EdgeLight(private val c: Context) {
    private val wm = c.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null // main thread only

    fun show(on: Boolean) {
        main.post {
            val v = view
            if (on && v == null && Settings.canDrawOverlays(c)) {
                view = Glow(c).also { wm.addView(it, params()) }
            } else if (!on && v != null) {
                wm.removeView(v)
                view = null
            }
        }
    }

    private fun params() = LayoutParams(
        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_NOT_FOCUSABLE or
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        alpha = 0.8f // Android 12+ blocks touches through an overlay above 0.8 opacity: Maps must stay usable
        fitInsetsTypes = 0 // reach under the status and navigation bars, not just the app area
        layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /** Four edge gradients, accent to transparent. */
    private class Glow(c: Context) : View(c) {
        private val color = c.getColor(R.color.primary)
        private val depth = 14f * resources.displayMetrics.density
        private val paints = List(4) { Paint() }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val clear = color and 0x00FFFFFF
            fun shader(x0: Float, y0: Float, x1: Float, y1: Float) = LinearGradient(x0, y0, x1, y1, color, clear, Shader.TileMode.CLAMP)
            paints[0].shader = shader(0f, 0f, 0f, depth)                    // top
            paints[1].shader = shader(0f, h.toFloat(), 0f, h - depth)       // bottom
            paints[2].shader = shader(0f, 0f, depth, 0f)                    // left
            paints[3].shader = shader(w.toFloat(), 0f, w - depth, 0f)       // right
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, depth, paints[0])
            canvas.drawRect(0f, h - depth, w, h, paints[1])
            canvas.drawRect(0f, 0f, depth, h, paints[2])
            canvas.drawRect(w - depth, 0f, w, h, paints[3])
        }
    }
}
