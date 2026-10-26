package com.yarisdoctor.domain.drive

import com.yarisdoctor.domain.health.sootLimits

/**
 * The Overview's "Next regen" tile. Every field is null when it can't be told from the data; none is ever NaN or
 * Infinity, so the screen can print them without a guard.
 */
data class RegenForecast(
    /** Km left before soot reaches [triggerSoot]: 0 = due now, null = no trustworthy trend yet. */
    val kmToRegen: Double?,
    /** How fast soot builds up between regens, % per 100 km. */
    val sootPer100Km: Double?,
    /** The soot % this car's regens actually start at, inside Toyota's band (see [sootLimits]). */
    val triggerSoot: Double?,
)

/** Under this the soot rise is mostly reading noise: a rate from it would be wild. */
private const val BASELINE_KM = 20.0
/** A rise of 0.02 % per km or less is flat, and the soot would never get there. */
private const val FLAT_RATE = 0.02
/** Past this the number says little: "drive more" is the honest answer. */
private const val MAX_KM = 1000.0

/**
 * Forecasts when the next regen is due from the soot since the last one ended. [regenSootStarts] are this car's own
 * regen start soot values, oldest first: their median is the level regens actually start at, which Toyota's band
 * ([sootLimits]) keeps sane when a start was read badly.
 *
 * The trend needs a baseline: at least 20 km since the last regen ended and soot above where it ended. Without it (and
 * when soot is flat) [RegenForecast.kmToRegen] is null; the trigger is still reported when soot is known.
 */
fun forecast(
    sootNow: Int?,
    sootAtLastRegenEnd: Int?,
    metresSinceRegen: Double?,
    regenSootStarts: List<Int>,
    model2015: Boolean,
): RegenForecast {
    val now = sootNow ?: return RegenForecast(null, null, null)
    val trigger = regenTriggerSoot(regenSootStarts, model2015)
    // Soot already at the trigger: due, whatever the trend says
    if (now >= trigger) return RegenForecast(0.0, null, trigger)

    val end = sootAtLastRegenEnd ?: return RegenForecast(null, null, trigger)
    val km = metresSinceRegen?.let { it / 1000 } ?: return RegenForecast(null, null, trigger)
    // isFinite also rejects NaN, which would otherwise slip through every comparison below
    if (!km.isFinite() || km < BASELINE_KM || now <= end) return RegenForecast(null, null, trigger)

    val rate = (now - end) / km // % per km
    if (rate <= FLAT_RATE) return RegenForecast(null, null, trigger)
    return RegenForecast(((trigger - now) / rate).coerceAtMost(MAX_KM), rate * 100, trigger)
}

/** Median of the last up to 3 non-zero starts, clamped to 0.8 × regen-needed .. the fault level; regen-needed alone when there is no history. */
private fun regenTriggerSoot(starts: List<Int>, model2015: Boolean): Double {
    val (needed, fault) = sootLimits(model2015)
    val recent = starts.filter { it > 0 }.takeLast(3).sorted()
    val median = when {
        recent.isEmpty() -> needed
        recent.size % 2 == 1 -> recent[recent.size / 2].toDouble()
        else -> (recent[recent.size / 2 - 1] + recent[recent.size / 2]) / 2.0
    }
    return median.coerceIn(needed * 0.8, fault)
}
