package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ChartTextTest {

    private val stamp = SimpleDateFormat("d MMM HH:mm", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun at(t: Long) = stamp.format(Date(t))
    private val t0 = 1_760_000_000_000L // a fixed instant; the span strings are derived, never compared to epochs
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun emptySeriesSaysSo() {
        assertEquals("No data yet", chartDescription("soot", LongArray(0), FloatArray(0), ::at))
    }

    @Test
    fun onePointHasNoSpan() {
        val s = chartDescription("soot", longArrayOf(t0), floatArrayOf(42f), ::at)
        assertEquals("Soot chart · 1 point · ${at(t0)} · always 42", s)
        assertFalse(s.contains(" to "))
    }

    @Test
    fun normalSeriesSummarisesLastAndRange() {
        val s = chartDescription(
            "soot",
            longArrayOf(t0, t0 + 60_000, t0 + 120_000),
            floatArrayOf(10f, 90f, 42f),
            ::at,
        )
        assertEquals("Soot chart · 3 points · ${at(t0)} to ${at(t0 + 120_000)} · last 42, min 10, max 90", s)
    }

    @Test
    fun allEqualValuesReadAsAlways() {
        val s = chartDescription("soot", longArrayOf(t0, t0 + 60_000), floatArrayOf(42f, 42f), ::at)
        assertTrue(s.endsWith("always 42"))
        assertFalse(s.contains("min"))
    }

    @Test
    fun fourteenDaySpanKeepsEpochOut() {
        val s = chartDescription("soot", longArrayOf(t0, t0 + 14 * day), floatArrayOf(5f, 80f), ::at)
        assertTrue(s.contains(at(t0)))
        assertTrue(s.contains(at(t0 + 14 * day)))
        assertFalse("raw epoch leaked: $s", Regex("\\d{10,}").containsMatchIn(s))
        assertTrue("too long for one announcement: ${s.length}", s.length <= 140)
    }

    @Test
    fun labelComesFromTheReadout() {
        assertEquals("soot", chartLabel("Sat 8 Nov, 09:33 · 42% soot"))
        assertEquals("", chartLabel("Sat 8 Nov, 09:33 · 250 mi · 12 min"))
        assertEquals("", chartLabel("09:33:12 · 42 %"))
    }

    @Test
    fun unknownNameFallsBackToChart() {
        val s = chartDescription("", longArrayOf(t0, t0 + day), floatArrayOf(1f, 2f), ::at)
        assertTrue(s.startsWith("Chart · 2 points"))
    }
}
