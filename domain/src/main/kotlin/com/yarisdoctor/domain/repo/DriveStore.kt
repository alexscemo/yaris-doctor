package com.yarisdoctor.domain.repo

import com.yarisdoctor.domain.health.Finding
import com.yarisdoctor.domain.model.Regen
import com.yarisdoctor.domain.model.Sample
import com.yarisdoctor.domain.model.Trip

/** What a drive reads and writes besides the regen log: the app's Db (the Debug one in Debug mode), or a fake in tests. */
interface DriveStore : RegenLog {
    /** Every answered request of one cycle, as request code → cleaned reply, at car odometer [odoM] (metres). */
    fun frames(t: Long, replies: Map<String, String>, odoM: Double)

    /** Makes the open alerts match [active], opening and closing them at car odometer [odoM]; returns the ones newly opened. */
    fun syncAlerts(t: Long, active: List<Finding>, odoM: Double): List<Finding>

    /**
     * The cluster showed [miles] at [t]: the drive's odometer went from [before] to [after] metres. The first read ever
     * moves every older row by the same step, from the old GPS figures onto the car's odometer.
     */
    fun odometerRead(t: Long, miles: Long, before: Double, after: Double) {}

    /** Deletes closed regens under a minute with no distance: the engine-stop blip of the regen bit, not a burn. */
    fun dropStopBlips()

    /** The newest [n] regens with their soot floor and peak temperature filled in, oldest first. */
    fun recentRegens(n: Int): List<Regen>

    /** Newest DPF pressure readings (kPa) taken with the engine stopped and the ignition on, newest first. */
    fun engineOffDp(limit: Int = 5): List<Double>

    /** This phone's drives, oldest first. */
    fun trips(): List<Trip>

    /** The newest reading, own or another phone's. */
    fun lastSample(): Sample?

    /** Car distance since the last finished regen, or null before the first. */
    fun metresSinceRegen(): Double?

    /** Keeps the first frame per request per minute in [[from], [to]). */
    fun thinFrames(from: Long, to: Long)
}
