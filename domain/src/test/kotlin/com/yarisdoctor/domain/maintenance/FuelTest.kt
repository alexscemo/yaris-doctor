package com.yarisdoctor.domain.maintenance

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelTest {
    private fun day(s: String) = LocalDate.parse(s).toEpochDay()

    @Test fun spendingSplitsByMonthAndYear() {
        val fills = listOf(
            Fill(1, day("2025-12-30"), 40.0, 7000, null),
            Fill(2, day("2026-09-15"), 30.0, 5400, null),
            Fill(3, day("2026-10-02"), 35.5, 6390, 1000.0),
        )
        val s = spending(fills, day("2026-10-28"))
        assertEquals(6390, s.monthCents)
        assertEquals(11790, s.yearCents)
        assertEquals(18790, s.totalCents)
        assertEquals(105.5, s.litres, 1e-9)
        assertEquals(18790 / 105.5, s.perLitreCents!!, 1e-9)
    }

    @Test fun noFillsNoAverage() = assertNull(spending(emptyList(), 0).perLitreCents)

    private val zone = ZoneId.of("Europe/London")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    /** The owner's 29 Oct 2026 reads: near empty on a short drive to the pump, 39 L (a full tank) after, with sloshing dips. */
    private val oct29 = listOf(
        "08:55:25" to 7.5, "08:56:25" to 7.5, "08:57:25" to 10.5, "08:58:25" to 7.5, "08:59:26" to 6.5, "09:00:26" to 6.5,
        "09:06:18" to 39.0, "09:07:19" to 39.0, "09:08:19" to 39.0, "09:38:43" to 37.5, "09:39:43" to 39.0,
        "09:47:46" to 35.5, "09:48:46" to 37.5, "09:49:47" to 39.0,
    ).map { (t, l) -> at("2026-10-29T$t") to l }

    @Test fun refillIsTheRiseAcrossTheStop() {
        val r = refills(oct29).single()
        assertEquals(at("2026-10-29T09:00:26"), r.before)
        assertEquals(at("2026-10-29T09:06:18"), r.after)
        assertEquals(32.5, r.litres, 1e-9)
    }

    @Test fun sloshingWhileDrivingIsNoRefill() {
        // The 08:57 spike and the 35.5 → 39 swings: neither is a 5 L rise across a pause
        assertTrue(refills(oct29.drop(6)).isEmpty())
        assertTrue(refills(oct29.take(6)).isEmpty())
    }

    @Test fun aSmallRiseAcrossAStopIsNoRefill() {
        // Parked on a slope: the reading moves a few litres across a stop
        assertTrue(refills(listOf(at("2026-10-29T08:00") to 20.0, at("2026-10-29T12:00") to 23.5)).isEmpty())
    }

    @Test fun loggedFillUpMatchesItsDaysRefill() {
        val found = listOf(
            Refill(at("2026-10-28T18:00"), at("2026-10-28T18:10"), 30.0),
            Refill(at("2026-10-29T09:00"), at("2026-10-29T09:06"), 32.5),
            Refill(at("2026-10-29T17:00"), at("2026-10-29T17:08"), 10.0),
        )
        assertEquals(found[1], matchRefill(found, day("2026-10-29"), 34.67, zone))
        assertEquals(found[2], matchRefill(found, day("2026-10-29"), 12.0, zone))
        assertNull(matchRefill(found, day("2026-10-27"), 34.67, zone))
    }

    @Test fun decimalTakesEitherSeparator() {
        assertEquals(58.3, decimal("58,30")!!, 1e-9)
        assertEquals(58.3, decimal(" 58.30 ")!!, 1e-9)
        assertNull(decimal("0"))
        assertNull(decimal("abc"))
        assertNull(decimal(""))
    }
}
