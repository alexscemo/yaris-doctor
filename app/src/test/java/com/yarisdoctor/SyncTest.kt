package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTest {
    @Test fun hubUrlFillsInSchemeAndDefaultPort() {
        assertEquals("http://192.168.1.50:8765", hubUrl("192.168.1.50"))
        assertEquals("http://192.168.1.50:9000", hubUrl(" 192.168.1.50:9000/ "))
        assertEquals("https://hub.lan:8765", hubUrl("https://hub.lan"))
        assertEquals("http://hub.lan:80/x", hubUrl("http://hub.lan:80/x"))
        assertEquals("", hubUrl("  "))
    }

    @Test fun deletedTripRangesRoundTripThroughPrefs() {
        val ranges = listOf(1_000L..181_000L, 1_700_000_000_000L..1_700_000_600_000L)
        assertEquals("1000-181000,1700000000000-1700000600000", formatRanges(ranges))
        assertEquals(ranges, parseRanges(formatRanges(ranges)))
        assertNull(formatRanges(emptyList()))
        assertEquals(emptyList<LongRange>(), parseRanges(null))
        assertEquals(listOf(5L..9L), parseRanges("x-1,5-9,7,"))
    }
}
