package com.yarisdoctor

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.RoundedCorner
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

    /** Blurred accent stroke along the screen outline, following the display's own rounded corners. */
    private class Glow(c: Context) : View(c) {
        private val depth = 14f * resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c.getColor(R.color.primary)
            style = Paint.Style.STROKE
            strokeWidth = depth * 2 // half of it lies off-screen: a 14dp rim that the blur fades inward
            maskFilter = BlurMaskFilter(depth / 2, BlurMaskFilter.Blur.NORMAL)
        }
        private val outline = Path()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            // Corner radii the display reports (Android 12+), per corner and for the current rotation; 0 = square
            fun r(pos: Int) = (display?.getRoundedCorner(pos)?.radius ?: 0).toFloat()
            val tl = r(RoundedCorner.POSITION_TOP_LEFT)
            val tr = r(RoundedCorner.POSITION_TOP_RIGHT)
            val br = r(RoundedCorner.POSITION_BOTTOM_RIGHT)
            val bl = r(RoundedCorner.POSITION_BOTTOM_LEFT)
            outline.reset()
            outline.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), floatArrayOf(tl, tl, tr, tr, br, br, bl, bl), Path.Direction.CW)
        }

        override fun onDraw(canvas: Canvas) = canvas.drawPath(outline, paint)
    }
}
