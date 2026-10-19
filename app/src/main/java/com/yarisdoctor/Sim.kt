package com.yarisdoctor

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Fake dongle for Debug mode: soot builds up, a regen burns it off, repeat, with random noise.
 * The numbers only need to look plausible on screen, not match the car.
 */
class Sim(
    private val growth: Float,
    private val drop: Float,
    private val regenTicks: Int,
    val metresPerTick: Double,
    private val rnd: Random = Random.Default,
) {
    data class Reading(val soot: Int, val temp: Int, val regen: Boolean)

    private var soot = 15f + rnd.nextFloat() * 30
    private var left = 0
    private var trigger = nextTrigger()

    fun next(): Reading {
        if (left > 0) {
            soot = max(5f, soot - drop * (0.5f + rnd.nextFloat()))
            left--
        } else {
            soot += growth * (0.5f + rnd.nextFloat())
            if (soot >= trigger) {
                left = regenTicks
                trigger = nextTrigger()
            }
        }
        val regen = left > 0
        val temp = if (regen) 560 + rnd.nextInt(90) else 200 + rnd.nextInt(120)
        return Reading(soot.roundToInt(), temp, regen)
    }

    /**
     * Engine off: finish any burn so the next drive doesn't open a regen 0 miles after this one.
     * Fake history only needs to look plausible; the real car may resume an interrupted regen.
     */
    fun endRegen() {
        if (left > 0) soot = 10f
        left = 0
    }

    private fun nextTrigger() = 65f + rnd.nextFloat() * 20

    companion object {
        /** One tick per second, compressed so a regen comes round every few minutes. */
        fun live() = Sim(growth = 0.4f, drop = 1.5f, regenTicks = 45, metresPerTick = 400.0)

        /** Fills [db] with ~14 days of two drives a day, one tick per driving minute at ~37 mph. */
        fun seed(db: Db, now: Long, rnd: Random = Random.Default) {
            val sim = Sim(growth = 0.12f, drop = 3f, regenTicks = 15, metresPerTick = 1000.0, rnd = rnd)
            val rec = Recorder(db)
            val day = 86_400_000L
            val today = now - now % day
            var odoM = 0.0
            for (d in 14 downTo 0) {
                for (leaveHour in intArrayOf(8, 17)) {
                    val start = today - d * day + leaveHour * 3_600_000L + rnd.nextLong(3_600_000L)
                    var t = start
                    for (m in 0 until 20 + rnd.nextInt(40)) {
                        if (start + m * 60_000L >= now) break
                        t = start + m * 60_000L
                        val r = sim.next()
                        odoM += sim.metresPerTick
                        rec.record(t, r.soot, r.temp, r.regen, odoM)
                    }
                    rec.close(t, odoM) // engine off: a regen still running is cut short, as on the car
                    sim.endRegen()
                }
            }
        }
    }
}
