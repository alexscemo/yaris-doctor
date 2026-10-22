package com.yarisdoctor

import com.yarisdoctor.domain.drive.Sim
import kotlin.random.Random

// Stays in the app until Recorder writes through a repository interface instead of Db

/** Fills [db] with ~14 days of two drives a day, one tick per driving minute at ~37 mph. */
fun Sim.Companion.seed(db: Db, now: Long, rnd: Random = Random.Default) {
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
