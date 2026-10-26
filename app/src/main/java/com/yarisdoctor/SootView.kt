package com.yarisdoctor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Background of the Overview's instrument card: soot fills it from the bottom up to the soot load. A change animates
 * the level, with specks settling onto the surface when soot rose, or coral embers lifting off it when it fell (a regen
 * burning it). Still otherwise, and no motion at all when the system turns animations off.
 */
class SootView(ctx: Context, fill: Int, private val ember: Int) : View(ctx) {

    private class Bit(var x: Float, var y: Float, val r: Float, val speed: Float, var life: Float)

    private val d = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val bitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val surface = Path()
    private val bits = ArrayList<Bit>()
    private var level = 0f      // 0..1 of the card's height, as drawn
    private var target = -1f    // last level asked for; -1 = never set
    private var rising = true
    private var activeUntil = 0L // particles and the moving surface run until then
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        // Purely decorative: the soot % is the text next to it, so TalkBack skips the fill and specks
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [soot] in %, null when unknown (drawn empty). 100 % fills the card. */
    fun setSoot(soot: Int?) {
        val next = ((soot ?: 0) / 100f).coerceIn(0f, 1f)
        if (next == target) return
        rising = next > target
        target = next
        animator?.cancel()
        if (!Ui.animationsOn()) {
            level = next
            activeUntil = 0L
            bits.clear()
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(level, next).apply {
            duration = 1500
            interpolator = DecelerateInterpolator()
            addUpdateListener { level = it.animatedValue as Float; invalidate() }
            start()
        }
        activeUntil = System.currentTimeMillis() + PARTICLE_MS
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // end(), not cancel(): jump to the target, or a tab switch mid-slide leaves the fill stuck (setSoot skips same values)
        animator?.end()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val motion = Ui.animationsOn()
        // Switched off mid-slide: settle at once and drop the particles instead of freezing them
        if (!motion) {
            if (animator?.isRunning == true) animator?.cancel()
            if (target >= 0f) level = target
            activeUntil = 0L
            bits.clear()
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val top = h * (1 - level)
        val active = motion && System.currentTimeMillis() < activeUntil
        if (level > 0f) {
            // A gentle wave on the surface while it moves; flat once settled
            val amp = if (active) 3 * d else 0f
            surface.reset()
            surface.moveTo(0f, h)
            var x = 0f
            while (x <= w) {
                surface.lineTo(x, top + amp * sin(2 * PI.toFloat() * x / (w / 2) + phase))
                x += 8 * d
            }
            surface.lineTo(w, top)
            surface.lineTo(w, h)
            surface.close()
            canvas.drawPath(surface, fillPaint)
        }
        if (!active && bits.isEmpty()) return

        if (active && bits.size < 28 && Random.nextFloat() < 0.5f) {
            bits += if (rising) Bit(Random.nextFloat() * w, top - (20 + Random.nextFloat() * 60) * d, (1 + Random.nextFloat() * 1.5f) * d, (0.4f + Random.nextFloat()) * d, 1f)
            else Bit(Random.nextFloat() * w, top, (1 + Random.nextFloat()) * d, -(0.6f + Random.nextFloat()) * d, 1f)
        }
        val it = bits.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.y += b.speed
            b.life -= 0.012f
            // Specks vanish into the surface, embers fade as they rise
            if (b.life <= 0f || (rising && b.y >= top) || b.y < 0f) { it.remove(); continue }
            bitPaint.color = if (rising) fillPaint.color else ember
            bitPaint.alpha = (255 * b.life).toInt()
            canvas.drawCircle(b.x, b.y, b.r, bitPaint)
        }
        phase += 0.08f
        postInvalidateOnAnimation()
    }

    companion object {
        private const val PARTICLE_MS = 4_000L
    }
}
