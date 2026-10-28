package com.yarisdoctor.domain.maintenance

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceTest {

    private val today = LocalDate.of(2026, 10, 28).toEpochDay()
    private var nextId = 1L

    private fun job(
        kind: String = OIL, day: Long? = today, done: Boolean = true, km: Double? = null, odoM: Double? = null,
        everyKm: Int? = null, everyMonths: Int? = null, note: String = "",
    ) = Job(nextId++, day, kind, note, done, km, odoM, everyKm, everyMonths)

    @Test fun mileageIsTheNewestReadingPlusGpsSince() {
        assertNull(carKm(listOf(job(km = 140_000.0)), 0.0)) // a reading without the GPS odometer anchors nothing
        val jobs = listOf(
            job(MILEAGE, day = today - 30, km = 145_000.0, odoM = 10_000.0),
            job(MILEAGE, day = today - 2, km = 145_500.0, odoM = 20_000.0),
        )
        assertEquals(145_530.0, carKm(jobs, 50_000.0)!!, 1e-9)
        // Trips deleted below the anchor: never less than the reading itself
        assertEquals(145_500.0, carKm(jobs, 5_000.0)!!, 1e-9)
    }

    /** The oil change migrated from Settings has only the GPS odometer: it must still warn at 15000 km without a mileage. */
    @Test fun migratedOilChangeStillWarnsWithoutMileage() {
        val oil = job(day = today - 100, odoM = 1_000_000.0, everyKm = 15_000)
        assertFalse(upcoming(listOf(oil), 15_999_000.0, today).single().overdue)
        val due = upcoming(listOf(oil), 16_200_000.0, today)
        assertTrue(due.single().overdue)
        val f = maintenanceDue(due, today)!!
        assertEquals("maintenance_due", f.key)
        assertEquals("Maintenance due: Oil and filter (15200 km since, every 15000 km).", f.text)
    }

    @Test fun pastLineCountsFromItsReadingOnceTheMileageIsSet() {
        val oil = job(day = today - 200, km = 130_000.0, everyKm = 15_000)
        assertNull(upcoming(listOf(oil), 0.0, today).single().kmLeft) // no mileage yet: unknown, not overdue
        val jobs = listOf(oil, job(MILEAGE, km = 144_000.0, odoM = 0.0))
        val d = upcoming(jobs, 2_000_000.0, today).single()
        assertEquals(-1_000.0, d.kmLeft!!, 1e-9)
        assertEquals(145_000.0, d.dueKm!!, 1e-9)
        assertTrue(d.overdue)
    }

    @Test fun monthsUseTheCalendar() {
        val fluid = job("Brake fluid", day = LocalDate.of(2024, 10, 31).toEpochDay(), everyMonths = 24)
        val d = upcoming(listOf(fluid), 0.0, today).single()
        assertEquals(LocalDate.of(2026, 10, 31).toEpochDay(), d.dueDay)
        assertFalse(d.overdue)
        assertTrue(upcoming(listOf(fluid), 0.0, LocalDate.of(2026, 10, 31).toEpochDay()).single().overdue)
        assertEquals("Maintenance due: Brake fluid (due 31 Oct 2026).",
            maintenanceDue(upcoming(listOf(fluid), 0.0, today + 5), today + 5)!!.text)
    }

    @Test fun newestDoneLineDecidesAndAPlanReplacesTheInterval() {
        val old = job(day = today - 400, odoM = 0.0, everyKm = 15_000)
        val noInterval = job(day = today - 10, odoM = 0.0)
        assertTrue(upcoming(listOf(old, noInterval), 30_000_000.0, today).isEmpty())

        val plan = job(day = today + 20, done = false)
        val due = upcoming(listOf(old, plan), 30_000_000.0, today)
        assertEquals(listOf(plan), due.map { it.job })
        assertFalse(due.single().overdue)
        assertNull(maintenanceDue(due, today))
    }

    @Test fun plannedLinesFallDueByDateOrOdometer() {
        val byKm = job("Coolant", day = null, done = false, km = 150_000.0)
        val anchor = job(MILEAGE, km = 149_000.0, odoM = 0.0)
        assertEquals(1_000.0, upcoming(listOf(byKm, anchor), 0.0, today).single().kmLeft!!, 1e-9)
        val over = upcoming(listOf(byKm, anchor), 1_500_000.0, today)
        assertEquals("Maintenance due: Coolant (planned at 150000 km).", maintenanceDue(over, today)!!.text)
        assertTrue(upcoming(listOf(job("Tyres", day = today, done = false)), 0.0, today).single().overdue)
    }

    @Test fun otherLinesAreGroupedByTheirNote() {
        val a = job(OTHER, day = today - 30, everyMonths = 12, note = "Aircon")
        val b = job(OTHER, day = today - 10, note = "Wipers")
        assertEquals(listOf("Aircon"), upcoming(listOf(a, b), 0.0, today).map { title(it.job) })
        // Mileage readings never remind, and overdue sorts first
        val late = job("Air filter", day = today - 800, everyMonths = 12)
        assertEquals(listOf("Air filter", "Aircon"), upcoming(listOf(a, b, late, job(MILEAGE, km = 1.0, odoM = 0.0)), 0.0, today).map { title(it.job) })
    }

    /** The hub refuses a longer alert text, and the whole sync with it: whatever doesn't fit is counted instead. */
    @Test fun warningFitsTheHubLimit() {
        val many = (1..10).map { job(OTHER, day = today - 400, everyMonths = 12, note = "Job number $it with a long description of the work") }
        val text = maintenanceDue(upcoming(many, 0.0, today), today)!!.text
        assertTrue(text, text.length <= ALERT_TEXT_MAX)
        assertTrue(text, Regex("""; and \d+ more\.$""").containsMatchIn(text))
        val one = job(OTHER, day = today - 400, everyMonths = 12, note = "x".repeat(200))
        assertTrue(maintenanceDue(upcoming(listOf(one), 0.0, today), today)!!.text.length <= ALERT_TEXT_MAX)
    }
}
