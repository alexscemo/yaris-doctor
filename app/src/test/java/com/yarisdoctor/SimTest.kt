package com.yarisdoctor

import org.junit.Assert.assertEquals
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

    @Test fun simulatedFramesPassThePollerAndCarryTheReading() {
        val sim = Sim(growth = 0.4f, drop = 1.5f, regenTicks = 45, metresPerTick = 400.0, rnd = Random(1))
        val frames = sim.frames(Sim.Reading(soot = 64, temp = 600, regen = true))
        assertEquals(PID_LENGTHS.keys, frames.keys)
        val p = Poller { frames[it.take(4)] } // the fake dongle answers what the real one would
        val got = p.cycle(0, emptyList())
        assertTrue(got.keys.containsAll(Poller.FAST))
        for ((code, reply) in got) assertEquals(frames[code], reply)
        fun v(name: String) = PIDS.first { it.name == name }.let { it.text(frames.getValue("21${it.lid}").substring(4)) }
        assertEquals("64 %", v("PM Accumulation Ratio"))
        assertEquals("Operate", v("DPNR/DPF Status Reju(PM)"))
        assertEquals("600.0 °C", v("Exhaust Temperature B1S2"))
        assertEquals(600, tempOf(frames.getValue("21F1")))
        assertEquals(64, sootOf(frames.getValue("21DD")))
        assertEquals(true, regenOf(frames.getValue("21DF")))
    }

    @Test fun idleFixtureMatchesTheTable() {
        assertEquals(PID_LENGTHS.keys, PID_IDLE.keys.map { "21$it" }.toSet())
        for ((lid, hex) in PID_IDLE) assertEquals(lid, PID_LENGTHS.getValue("21$lid") * 2, hex.length)
    }

    @Test fun endRegenStopsTheBurnSoTheNextDriveStartsClean() {
        val sim = Sim(growth = 0.4f, drop = 1.5f, regenTicks = 45, metresPerTick = 400.0, rnd = Random(1))
        while (!sim.next().regen) Unit
        sim.endRegen()
        assertTrue(List(20) { sim.next() }.none { it.regen })
    }
}
