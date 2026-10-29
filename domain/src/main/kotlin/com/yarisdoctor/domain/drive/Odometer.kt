package com.yarisdoctor.domain.drive

/** One mile, metres: the cluster counts the odometer in whole miles. */
const val MILE_M = 1609.344

/**
 * The car's odometer during a drive, in metres. The cluster's 0x611 broadcast gives the dash odometer in whole miles
 * ([Meter.odometer], read every [Meter.ODO_MS]); in between, [advance] adds up the ECU's vehicle speed. [snap] keeps the
 * total inside the mile the cluster shows, so speed error never builds up past one read.
 */
object Odometer {
    /** A gap longer than this between two speed reads is a stall or a dropped link, not distance: nothing is added. */
    const val MAX_STEP_MS = 10_000L

    /** [metres] plus the distance at the mean of two speed reads [dtMs] apart (km/h); unchanged across a longer gap. */
    fun advance(metres: Double, kmh0: Double, kmh1: Double, dtMs: Long): Double =
        if (dtMs <= 0 || dtMs > MAX_STEP_MS) metres else metres + (kmh0 + kmh1) / 2 / 3.6 * dtMs / 1000.0

    /**
     * The odometer once the cluster shows [miles]: [metres] if it is inside that mile, else the nearest end of it. The
     * first read of a drive that falls outside (driven without the phone, a fresh install or an old GPS figure) takes the
     * middle of the mile instead, so the error is half a mile at most until the next read narrows it.
     */
    fun snap(metres: Double, miles: Long, firstOfDrive: Boolean): Double {
        val lo = miles * MILE_M
        val hi = (miles + 1) * MILE_M
        return when {
            metres >= lo && metres < hi -> metres
            firstOfDrive -> lo + MILE_M / 2
            metres < lo -> lo
            else -> hi - 1.0
        }
    }
}
