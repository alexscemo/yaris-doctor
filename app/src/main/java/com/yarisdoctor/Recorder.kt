package com.yarisdoctor

/** Turns ECU readings into sample and regen rows. Shared by the real dongle session, Debug mode and its fake history. */
class Recorder(private val db: Db) {
    private var regenId = -1L
    var regenStart = 0L
        private set
    val inRegen get() = regenId >= 0

    fun record(t: Long, soot: Int?, temp: Int?, regen: Boolean?, odoM: Double) {
        val isRegen = regen ?: inRegen // keep last state on a failed read, like DPFViewer
        db.sample(t, soot, temp, isRegen, odoM)
        if (isRegen && !inRegen) {
            regenId = db.regenStart(t, odoM, soot)
            regenStart = t
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
