package com.yarisdoctor.domain.maintenance

import com.yarisdoctor.domain.health.Finding
import com.yarisdoctor.domain.health.OIL_CHANGE_KM
import com.yarisdoctor.domain.health.WARN
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One logbook line. Done: work carried out on [day] (epoch day), at odometer [km] if the owner gave it. Planned: work due
 * on [day] and/or at [km], at least one. [odoM]: the logged odometer (metres) when a done line was written on its
 * own day; with [km] it anchors the car's mileage ([carKm]) until the cluster's odometer has been read. [everyKm] / [everyMonths]: remind again after that much.
 * Distances are km, whatever the display units.
 */
data class Job(
    val id: Long,
    val day: Long?,
    val kind: String,
    val note: String,
    val done: Boolean,
    val km: Double?,
    val odoM: Double?,
    val everyKm: Int?,
    val everyMonths: Int?,
)

const val OIL = "Oil and filter"
const val OTHER = "Other"
/** A dashboard odometer reading, nothing else: the "Set mileage" button writes these. */
const val MILEAGE = "Mileage reading"

/** What the Add button offers. Only oil has a default interval: the others have no sourced figure for this engine. */
val KINDS = listOf(OIL, "Air filter", "Fuel filter", "Cabin filter", "Brake fluid", "Coolant", "Brakes", "Tyres", "Battery", "DPF", "Inspection", OTHER)

fun defaultEveryKm(kind: String): Int? = if (kind == OIL) OIL_CHANGE_KM else null

/** "Other" lines are told apart by their note, so a "Wipers" reminder doesn't replace an "Aircon" one. */
fun group(j: Job): String = if (j.kind == OTHER) "$OTHER:${j.note.trim().lowercase(Locale.ROOT)}" else j.kind

/** The heading a line shows: its kind, or for "Other" what the note says. */
fun title(j: Job): String = if (j.kind == OTHER) j.note.trim().ifEmpty { OTHER } else j.kind

/** Later day first, then the line written last. */
val NEWEST = compareBy<Job>({ it.day }, { it.id })

/** The newest done line with both a reading and the logged odometer: what [carKm] counts from before the cluster is read. */
fun anchor(jobs: List<Job>): Job? = jobs.filter { it.done && it.km != null && it.odoM != null }.maxWithOrNull(NEWEST)

/**
 * The car's odometer now, km. [odoIsCar]: the cluster's odometer has been read, so the logged [odoM] (metres) is the
 * car's own. Before that, the [anchor]'s reading plus the distance logged since; null without one. Deleting the newest
 * trips winds [odoM] back, so the distance is never negative.
 */
fun carKm(jobs: List<Job>, odoM: Double, odoIsCar: Boolean = false): Double? =
    if (odoIsCar) odoM / 1000 else anchor(jobs)?.let { it.km!! + (odoM - it.odoM!!).coerceAtLeast(0.0) / 1000 }

/** Km driven since done line [j]: the logged odometer when the line has it, else the car's readings; null when unknown. */
fun kmSince(j: Job, odoM: Double, carKm: Double?): Double? = when {
    j.odoM != null -> (odoM - j.odoM).coerceAtLeast(0.0) / 1000
    j.km != null && carKm != null -> (carKm - j.km).coerceAtLeast(0.0)
    else -> null
}

/**
 * A reminder: a planned line, or the newest done line of a kind whose interval runs from it. [dueDay] / [dueKm] (car
 * odometer) are when it falls due, [kmLeft] how far that is from now; each null when not counted that way or unknown.
 */
data class Due(val job: Job, val dueDay: Long?, val dueKm: Double?, val kmLeft: Double?, val overdue: Boolean)

/**
 * Every reminder, overdue first, then soonest. A planned line replaces its kind's interval reminder. A done line that
 * set no interval ends its kind's reminders, so changing the oil without one doesn't keep an older interval running.
 */
fun upcoming(jobs: List<Job>, odoM: Double, today: Long, odoIsCar: Boolean = false): List<Due> {
    val car = carKm(jobs, odoM, odoIsCar)
    val planned = jobs.filter { !it.done }
    val plannedGroups = planned.map(::group).toSet()
    fun due(j: Job, day: Long?, km: Double?, left: Double?) =
        Due(j, day, km, left, (left != null && left <= 0) || (day != null && day <= today))
    val fromPlans = planned.map { j -> due(j, j.day, j.km, j.km?.let { k -> car?.let { k - it } }) }
    val fromIntervals = jobs.filter { it.done && it.kind != MILEAGE }.groupBy(::group).filterKeys { it !in plannedGroups }
        .values.map { it.maxWith(NEWEST) }
        .filter { it.everyKm != null || it.everyMonths != null }
        .map { j ->
            val since = kmSince(j, odoM, car)
            val left = j.everyKm?.let { e -> since?.let { e - it } }
            val dueKm = j.everyKm?.let { e -> j.km?.plus(e) ?: if (car != null && left != null) car + left else null }
            due(j, j.everyMonths?.let { LocalDate.ofEpochDay(j.day!!).plusMonths(it.toLong()).toEpochDay() }, dueKm, left)
        }
    return (fromPlans + fromIntervals).sortedWith(
        compareBy<Due>({ !it.overdue }, { it.dueDay ?: Long.MAX_VALUE }, { it.kmLeft ?: Double.MAX_VALUE }),
    )
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** The hub refuses an alert text longer than this, and with it the whole sync. */
const val ALERT_TEXT_MAX = 300

/**
 * One warning for everything overdue, null when nothing is. Metric and without thousands separators, so the
 * display's Units.text() converts the distances. Kept under [ALERT_TEXT_MAX]: what doesn't fit is counted ("and 2 more").
 */
fun maintenanceDue(due: List<Due>, today: Long): Finding? {
    val over = due.filter { it.overdue }.ifEmpty { return null }
    val items = over.map { d ->
        val j = d.job
        val why = listOfNotNull(
            d.kmLeft?.takeIf { it <= 0 }?.let { left ->
                // ROOT: Units.text() reads ASCII digits back
                if (j.done) "%.0f km since, every %d km".format(Locale.ROOT, j.everyKm!! - left, j.everyKm) else "planned at %.0f km".format(Locale.ROOT, j.km)
            },
            d.dueDay?.takeIf { it <= today }?.let { "due ${DATE.format(LocalDate.ofEpochDay(it))}" },
        )
        val name = title(j).let { if (it.length > 40) it.take(39) + "…" else it } // an "Other" note can be 200 characters
        "$name (${why.joinToString(", ")})"
    }
    val prefix = "Maintenance due: "
    var n = items.size
    fun text(k: Int) = prefix + items.take(k).joinToString("; ") + (if (k < items.size) "; and ${items.size - k} more" else "") + "."
    while (n > 1 && text(n).length > ALERT_TEXT_MAX) n--
    return Finding("maintenance_due", WARN, text(n).take(ALERT_TEXT_MAX))
}
