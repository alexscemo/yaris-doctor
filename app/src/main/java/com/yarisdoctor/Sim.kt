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

    /**
     * Replies to the whole table for [r], as Poller.cycle returns them: the captured idle payloads with the DPF values,
     * revs, speed and coolant swapped in, so the All data tab has something to show in Debug mode.
     */
    fun frames(r: Reading): Map<String, String> = PID_IDLE.mapValues { (lid, idle) ->
        val b = idle.chunked(2).toMutableList()
        fun put(at: Int, v: Int, n: Int = 1) { for (i in 0 until n) b[at + i] = "%02X".format(v shr (8 * (n - 1 - i)) and 0xFF) }
        when (lid) {
            "05" -> put(0, 85 + 40 + rnd.nextInt(4))
            "0C" -> put(0, (if (r.regen) 1800 else 800 + rnd.nextInt(1700)) * 4, 2)
            "0D" -> put(0, rnd.nextInt(110))
            "DD" -> put(17, (r.soot / 2).coerceIn(0, 255))
            "DF" -> put(9, if (r.regen) 2 else 0)
            "F1" -> { put(4, (r.temp * 1.6 + 40).toInt(), 2); put(6, (r.temp * 1.6).toInt(), 2) }
        }
        "61$lid" + b.joinToString("")
    }.mapKeys { "21${it.key}" }

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
