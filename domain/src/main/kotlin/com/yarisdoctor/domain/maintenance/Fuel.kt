package com.yarisdoctor.domain.maintenance

import com.yarisdoctor.domain.model.TRIP_GAP_MS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * One fill-up: [day] (epoch day), [litres] put in, [cents] paid in the phone's currency. [odoM]: this phone's GPS
 * odometer at the fill-up, kept so consumption can be worked out later: at the stop where the meter saw the tank rise
 * ([refills]), else when it was logged on its own day.
 */
data class Fill(val id: Long, val day: Long, val litres: Double, val cents: Long, val odoM: Double?)

/**
 * A fill-up the meter saw: the tank's level rose by [litres] across the engine-off stop between the read at [before]
 * and the one at [after].
 */
data class Refill(val before: Long, val after: Long, val litres: Double)

/** The smallest rise that counts as a fill-up: the level read while driving swings by up to ~3.5 L as the fuel sloshes. */
const val MIN_REFILL_L = 5.0

/**
 * The fill-ups in [levels] (the meter's reads as time → litres, oldest first): a rise of at least [MIN_REFILL_L] across a
 * pause of at least [TRIP_GAP_MS] between two reads. Only across a pause, as the engine is off to fill up, and each side
 * is the median of up to 3 reads, so sloshing never counts as one.
 */
fun refills(levels: List<Pair<Long, Double>>): List<Refill> {
    fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
    val out = ArrayList<Refill>()
    for (i in 1 until levels.size) {
        if (levels[i].first - levels[i - 1].first < TRIP_GAP_MS) continue
        val before = median(levels.subList(maxOf(0, i - 3), i).map { it.second })
        val after = median(levels.subList(i, minOf(levels.size, i + 3)).map { it.second })
        if (after - before >= MIN_REFILL_L) out += Refill(levels[i - 1].first, levels[i].first, after - before)
    }
    return out
}

/**
 * The fill-up in [found] that a logged one ([day] as an epoch day in [zone], [litres] put in) is: a stop on that day,
 * the one whose rise is nearest [litres] (a full tank reads a little low, so the rise rarely matches exactly). Null if none.
 */
fun matchRefill(found: List<Refill>, day: Long, litres: Double, zone: ZoneId): Refill? {
    fun dayOf(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toEpochDay()
    return found.filter { dayOf(it.before) == day || dayOf(it.after) == day }.minByOrNull { abs(it.litres - litres) }
}

/** Spent this calendar month, this year and in all (cents), and the average price per litre over every fill-up. */
data class Spending(val monthCents: Long, val yearCents: Long, val totalCents: Long, val litres: Double, val perLitreCents: Double?)

fun spending(fills: List<Fill>, today: Long): Spending {
    val now = LocalDate.ofEpochDay(today)
    fun sum(f: (LocalDate) -> Boolean) = fills.filter { f(LocalDate.ofEpochDay(it.day)) }.sumOf { it.cents }
    val litres = fills.sumOf { it.litres }
    val total = fills.sumOf { it.cents }
    return Spending(
        sum { it.year == now.year && it.month == now.month },
        sum { it.year == now.year },
        total,
        litres,
        if (litres > 0) total / litres else null,
    )
}

/** A typed amount, "58,30" (French keyboards) or "58.30"; null when it isn't a positive number. */
fun decimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
