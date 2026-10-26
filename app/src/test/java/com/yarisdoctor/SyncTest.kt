package com.yarisdoctor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    /** A sync run stops between pages once its wall-clock budget is gone, and never before. */
    @Test fun syncStopsOnlyAfterItsBudgetIsUsedUp() {
        val start = 1_700_000_000_000L
        assertFalse(overBudget(start, start))
        assertFalse(overBudget(start, start + SYNC_BUDGET_MS - 1))
        assertTrue(overBudget(start, start + SYNC_BUDGET_MS))
        assertTrue(overBudget(start, start + SYNC_BUDGET_MS * 3))
        assertFalse(overBudget(start, start - 5_000L)) // a clock that jumped back must not end the run for good
        assertTrue(overBudget(start, start + 30_000L, budgetMs = 30_000L))
    }

    /**
     * A run that cannot reach the hub is a status line, never an exception, and it always stops reporting itself as
     * running (the service's pause-at-home check depends on that).
     */
    @Test fun aFailedRunReportsItselfAndStopsRunning() {
        val c = ApplicationProvider.getApplicationContext<Application>()
        val status = Sync.run(c)
        assertFalse(Sync.isRunning())
        assertTrue(status, status.startsWith("Hub not reachable"))
        assertEquals(status, Prefs.str(c, Prefs.SYNC_STATUS))
    }
}
