package com.yarisdoctor.domain.maintenance

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun decimalTakesEitherSeparator() {
        assertEquals(58.3, decimal("58,30")!!, 1e-9)
        assertEquals(58.3, decimal(" 58.30 ")!!, 1e-9)
        assertNull(decimal("0"))
        assertNull(decimal("abc"))
        assertNull(decimal(""))
    }
}
