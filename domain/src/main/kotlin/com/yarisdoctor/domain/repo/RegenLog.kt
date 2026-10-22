package com.yarisdoctor.domain.repo

/** Where [com.yarisdoctor.domain.drive.Recorder] writes: the app's Db, or a fake in tests. */
interface RegenLog {
    fun sample(t: Long, soot: Int?, temp: Int?, regen: Boolean, odoM: Double)

    /** Opens a regen row; returns its id for [regenEnd]. */
    fun regenStart(t: Long, odoM: Double, soot: Int?): Long

    fun regenEnd(id: Long, t: Long, odoM: Double, soot: Int?)
}
