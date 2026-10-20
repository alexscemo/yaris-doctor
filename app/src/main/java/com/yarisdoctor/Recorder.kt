package com.yarisdoctor

/** Turns ECU readings into sample and regen rows. Shared by the real dongle session, Debug mode and its fake history. */
class Recorder(private val db: Db) {
    private var regenId = -1L
    var regenStart = 0L
        private set
    var regenSootStart: Int? = null
        private set
    val inRegen get() = regenId >= 0
    /** Time of the last reading, where a regen cut short by a lost link is closed. */
    var lastT = 0L
        private set

    fun record(t: Long, soot: Int?, temp: Int?, regen: Boolean?, odoM: Double) {
        lastT = t
        val isRegen = regen ?: inRegen // keep last state on a failed read, like DPFViewer
        db.sample(t, soot, temp, isRegen, odoM)
        if (isRegen && !inRegen) {
            regenId = db.regenStart(t, odoM, soot)
            regenStart = t
            regenSootStart = soot
        } else if (!isRegen && inRegen) {
            db.regenEnd(regenId, t, odoM, soot)
            regenId = -1
        }
    }

    /** Engine off or link lost mid-regen: close the record at the last moment we saw it. */
    fun close(t: Long, odoM: Double) {
        if (inRegen) db.regenEnd(regenId, t, odoM, null)
        regenId = -1
    }
}

/** How much of the soot present when the regen began has burned off (0-100), or null when unknown. */
fun burnPercent(sootStart: Int?, sootNow: Int?): Int? =
    if (sootStart == null || sootNow == null || sootStart <= 0) null
    else ((sootStart - sootNow) * 100 / sootStart).coerceIn(0, 100)
