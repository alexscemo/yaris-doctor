package com.yarisdoctor.domain.drive

import com.yarisdoctor.domain.car.RUNNING_RPM

/**
 * Fuel burnt, L/h, from the ECU's Injection Volume ([mm3] per cylinder per stroke, 21AD) and [rpm]: 4 cylinders, each
 * injecting once every 2 revolutions. An estimate: not yet checked against the fill-up log.
 */
fun litresPerHour(mm3: Double, rpm: Double) = mm3 * rpm * 2 * 60 / 1e6

/** [per100Km] is null when the car was mostly standing still: show [litresPerHour] then. */
class Consumption(val litresPerHour: Double, val per100Km: Double?)

/**
 * Over [cycles] of (injection mm³/st, rpm, km/h): total fuel ÷ total distance, so slow cycles don't blow the figure up as
 * an average of per-cycle L/100 km would. Engine-off cycles are left out; null when none is left.
 */
fun consumption(cycles: List<Triple<Double, Double, Double>>): Consumption? {
    val run = cycles.filter { it.second >= RUNNING_RPM }
    if (run.isEmpty()) return null
    val lph = run.sumOf { litresPerHour(it.first, it.second) } / run.size
    val kmh = run.sumOf { it.third } / run.size
    return Consumption(lph, if (kmh >= MOVING_KMH) lph / kmh * 100 else null)
}

const val MOVING_KMH = 5.0

/** How far back the Overview's consumption reaches from the newest reading. */
const val CONSUMPTION_MS = 5 * 60_000L
