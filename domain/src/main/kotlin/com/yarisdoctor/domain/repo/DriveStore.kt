package com.yarisdoctor.domain.repo

import com.yarisdoctor.domain.health.Finding
import com.yarisdoctor.domain.model.Regen

/** What a drive reads and writes besides the regen log: the app's Db (the Debug one in Debug mode), or a fake in tests. */
interface DriveStore : RegenLog {
    /** Every answered request of one cycle, as request code → cleaned reply. */
    fun frames(t: Long, replies: Map<String, String>)

    /** Makes the open alerts match [active]; returns the ones newly opened. */
    fun syncAlerts(t: Long, active: List<Finding>): List<Finding>

    /** Deletes closed regens under a minute with no distance: the engine-stop blip of the regen bit, not a burn. */
    fun dropStopBlips()

    /** The newest [n] regens with their soot floor and peak temperature filled in, oldest first. */
    fun recentRegens(n: Int): List<Regen>

    /** Newest DPF pressure readings (kPa) taken with the engine stopped and the ignition on, newest first. */
    fun engineOffDp(limit: Int = 5): List<Double>

    /** Whether any frame was ever stored for [lid] (a whole code such as "0904" for the reads kept under it). */
    fun hasFrame(lid: String): Boolean

    /** Keeps the first frame per request per minute in [[from], [to]). */
    fun thinFrames(from: Long, to: Long)
}
