package com.yarisdoctor.domain.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextRegenTest {

    @Test fun noDataHasNoForecast() {
        val f = forecast(null, null, null, emptyList(), false)
        assertNull(f.kmToRegen)
        assertNull(f.sootPer100Km)
        assertNull(f.triggerSoot)
    }

    @Test fun aShortDistanceSinceTheRegenGivesNoTrend() {
        // Just under the 20 km baseline: 30 % of rise would read as 1.5 % per km
        val f = forecast(40, 10, 19_999.0, emptyList(), false)
        assertNull(f.kmToRegen)
        assertNull(f.sootPer100Km)
        assertEquals(100.0, f.triggerSoot!!, 1e-9)
        // At exactly 20 km it is enough: rate 30/20 = 1.5, so (100 - 40) / 1.5 = 40 km left
        assertEquals(40.0, forecast(40, 10, 20_000.0, emptyList(), false).kmToRegen!!, 1e-9)
    }

    @Test fun theSootAtTheLastRegenEndIsRequired() {
        val f = forecast(40, null, 100_000.0, emptyList(), false)
        assertNull(f.kmToRegen)
        assertNull(f.sootPer100Km)
        assertEquals(100.0, f.triggerSoot!!, 1e-9)
    }

    @Test fun flatSootHasNoTrend() {
        // 20 → 21 over 100 km is 0.01 % per km, under the 0.02 floor
        val f = forecast(21, 20, 100_000.0, emptyList(), false)
        assertNull(f.kmToRegen)
        assertNull(f.sootPer100Km)
        assertEquals(100.0, f.triggerSoot!!, 1e-9)
    }

    @Test fun aNormalRiseProjectsTheRemainingDistance() {
        // 10 → 25 over 75 km = 0.2 % per km; 75 % left of the 100 % trigger = 375 km
        val f = forecast(25, 10, 75_000.0, emptyList(), false)
        assertEquals(375.0, f.kmToRegen!!, 1e-9)
        assertEquals(20.0, f.sootPer100Km!!, 1e-9)
        assertEquals(100.0, f.triggerSoot!!, 1e-9)
    }

    @Test fun sootAtTheTriggerIsDueNow() {
        val f = forecast(120, 10, 30_000.0, emptyList(), false)
        assertEquals(0.0, f.kmToRegen!!, 0.0)
        assertNull(f.sootPer100Km) // a rate adds nothing once it is due
        assertEquals(100.0, f.triggerSoot!!, 1e-9)
        // Even a flat rise is due when the soot is already at the trigger
        assertEquals(0.0, forecast(100, 99, 100_000.0, emptyList(), false).kmToRegen!!, 0.0)
    }

    @Test fun theForecastIsCappedAt1000km() {
        // 10 → 11 over 20 km = 0.05 % per km, so the 89 % left would be 1780 km
        val f = forecast(11, 10, 20_000.0, emptyList(), false)
        assertEquals(1000.0, f.kmToRegen!!, 1e-9)
        assertEquals(5.0, f.sootPer100Km!!, 1e-9)
    }

    @Test fun the2015ScaleMovesTheTrigger() {
        // 10 → 20 over 50 km = 0.2 % per km; 2011-2014 needs 100 %, 2015+ 118 %
        assertEquals(400.0, forecast(20, 10, 50_000.0, emptyList(), false).kmToRegen!!, 1e-9)
        val new = forecast(20, 10, 50_000.0, emptyList(), true)
        assertEquals(490.0, new.kmToRegen!!, 1e-9)
        assertEquals(118.0, new.triggerSoot!!, 1e-9)
    }

    @Test fun theTriggerIsTheMedianOfTheLastThreeStarts() {
        // The zero is ignored, so the median of 112, 121, 130 is 121
        assertEquals(121.0, forecast(50, 10, 100_000.0, listOf(85, 112, 0, 121, 130), false).triggerSoot!!, 1e-9)
        // Two starts: their average
        assertEquals(120.0, forecast(50, 10, 100_000.0, listOf(110, 130), false).triggerSoot!!, 1e-9)
        // The oldest starts are dropped; the newest three are 112, 121, 130
        assertEquals(121.0, forecast(50, 10, 100_000.0, listOf(90, 112, 121, 130), false).triggerSoot!!, 1e-9)
        // No usable start at all: Toyota's regen-needed level
        assertEquals(100.0, forecast(50, 10, 100_000.0, listOf(0, 0), false).triggerSoot!!, 1e-9)
    }

    @Test fun theTriggerStaysInsideToyotasBand() {
        // A start read at 60 % is below 0.8 × 100, one at 200 % is past the 150 % fault level
        assertEquals(80.0, forecast(50, 10, 100_000.0, listOf(60), false).triggerSoot!!, 1e-9)
        assertEquals(150.0, forecast(50, 10, 100_000.0, listOf(200), false).triggerSoot!!, 1e-9)
        // 2015+: 0.8 × 118 = 94.4 and the fault level is 174
        assertEquals(94.4, forecast(50, 10, 100_000.0, listOf(60), true).triggerSoot!!, 1e-9)
        assertEquals(174.0, forecast(50, 10, 100_000.0, listOf(200), true).triggerSoot!!, 1e-9)
    }

    @Test fun aFlatOrFallingSootHasNoForecast() {
        // Unchanged since the regen ended: no rise to project
        val same = forecast(20, 20, 50_000.0, emptyList(), false)
        assertNull(same.kmToRegen)
        assertNull(same.sootPer100Km)
        // Lower than when the regen ended: a bad baseline, not a negative rate
        val lower = forecast(8, 12, 50_000.0, emptyList(), false)
        assertNull(lower.kmToRegen)
        assertNull(lower.sootPer100Km)
    }

    @Test fun aBrokenDistanceNeverProducesNaN() {
        assertNull(forecast(40, 10, Double.NaN, emptyList(), false).kmToRegen)
        assertNull(forecast(40, 10, Double.POSITIVE_INFINITY, emptyList(), false).kmToRegen)
        assertNull(forecast(40, 10, -5_000.0, emptyList(), false).kmToRegen)
    }
}
