package com.yarisdoctor.domain.drive

import com.yarisdoctor.domain.repo.RegenLog

/** Turns ECU readings into sample and regen rows. Shared by the real dongle session, Debug mode and its fake history. */
class Recorder(private val db: RegenLog) {
    private var regenId = -1L
    var regenStart = 0L
        private set
    var regenSootStart: Int? = null
        private set
    val inRegen get() = regenId >= 0
    /** Time of the last reading, where a regen cut short by a lost link is closed. */
    var lastT = 0L
        private set

    /** The end* values are where a closing regen really ended, when the signal only says so later. */
    fun record(t: Long, soot: Int?, temp: Int?, regen: Boolean?, odoM: Double, endT: Long = t, endOdoM: Double = odoM, endSoot: Int? = soot) {
        lastT = t
        val isRegen = regen ?: inRegen // keep last state on a failed read, like DPFViewer
        db.sample(t, soot, temp, isRegen, odoM)
        if (isRegen && !inRegen) {
            regenId = db.regenStart(t, odoM, soot)
            regenStart = t
            regenSootStart = soot
        } else if (!isRegen && inRegen) {
            db.regenEnd(regenId, endT, endOdoM, endSoot)
            regenId = -1
        }
    }

    /** Engine off or link lost mid-regen: close the record at the last moment we saw it. */
    fun close(t: Long, odoM: Double) {
        if (inRegen) db.regenEnd(regenId, t, odoM, null)
        regenId = -1
    }
}
