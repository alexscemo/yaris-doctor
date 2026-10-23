package com.yarisdoctor.domain.model

/** A health finding (Finding) while it lasted; [end] null = still active. */
data class Alert(val key: String, val level: Int, val text: String, val start: Long, val end: Long?)

data class Sample(val t: Long, val soot: Int?, val temp: Int?, val regen: Boolean)

/**
 * [gapM]: metres driven between the previous regen's end and this one's start; null for the first logged regen.
 * [floor] and [peakTemp] are only filled by Db.withSigns.
 */
data class Regen(val start: Long, val end: Long?, val gapM: Double?, val sootStart: Int?, val sootEnd: Int?, val floor: Int? = null, val peakTemp: Int? = null)

/** What a drive did, for the end-of-drive notification: metres, first and last soot, regens and warnings started. */
class TripStats(val metres: Double, val sootStart: Int?, val sootEnd: Int?, val regens: Int, val warnings: Int)

/**
 * One drive of this phone's log: samples with no gap over [TRIP_GAP_MS]. [regens] started in it. Its rows (frames, warnings and
 * link stats written just after the last sample included) lie in [start]..[until], which never reaches the next trip.
 */
data class Trip(val start: Long, val end: Long, val metres: Double, val regens: Int) {
    val until get() = end + TRIP_GAP_MS
    /** Still being logged: the service may write to it, so it can't be deleted yet. */
    fun live(now: Long) = now - end < TRIP_GAP_MS
}

/** Regens from two phones (dongle hand-over mid-burn) closer than this are the same burn; matches the hub. */
const val MERGE_SLACK_MS = 2 * 60_000L
/** ECU silent longer than this ends a trip; the service's REGEN_GAP_MS is this value, so a drive ends at the same point. */
const val TRIP_GAP_MS = 2 * 60_000L
const val METRES_PER_MILE = 1609.344
/** A regen's soot floor is read this long after it ends (see Db.withSigns). */
const val FLOOR_AFTER_MS = 5 * 60_000L
