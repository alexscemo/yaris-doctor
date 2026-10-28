package com.yarisdoctor.domain.maintenance

import java.time.LocalDate

/**
 * One fill-up: [day] (epoch day), [litres] put in, [cents] paid in the phone's currency. [odoM]: this phone's GPS
 * odometer when it was logged on its own day, kept so consumption can be worked out later.
 */
data class Fill(val id: Long, val day: Long, val litres: Double, val cents: Long, val odoM: Double?)

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
