package com.yarisdoctor

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SimTest {
    @Test fun liveSimCyclesThroughRegensWithPlausibleValues() {
        val sim = Sim(growth = 0.4f, drop = 1.5f, regenTicks = 45, metresPerTick = 400.0, rnd = Random(1))
        val readings = List(2000) { sim.next() }
        val starts = readings.zipWithNext().count { (a, b) -> !a.regen && b.regen }
        assertTrue("expected several regens, got $starts", starts >= 3)
        assertTrue(readings.all { it.soot in 5..100 })
        assertTrue(readings.filter { it.regen }.all { it.temp in 560..649 })
        assertTrue(readings.filter { !it.regen }.all { it.temp in 200..319 })
    }

    @Test fun endRegenStopsTheBurnSoTheNextDriveStartsClean() {
        val sim = Sim(growth = 0.4f, drop = 1.5f, regenTicks = 45, metresPerTick = 400.0, rnd = Random(1))
        while (!sim.next().regen) Unit
        sim.endRegen()
        assertTrue(List(20) { sim.next() }.none { it.regen })
    }
}
