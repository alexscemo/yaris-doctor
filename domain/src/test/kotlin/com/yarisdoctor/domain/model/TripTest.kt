package com.yarisdoctor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTest {
    @Test fun windowEndsOneTripGapAfterTheLastSampleAndLiveUntilThen() {
        val trip = Trip(start = 0, end = 30 * 60_000L, metres = 25_000.0, regens = 1)
        assertEquals(30 * 60_000L + TRIP_GAP_MS, trip.until)
        assertTrue(trip.live(trip.end + TRIP_GAP_MS - 1))
        assertFalse(trip.live(trip.end + TRIP_GAP_MS))
    }
}
