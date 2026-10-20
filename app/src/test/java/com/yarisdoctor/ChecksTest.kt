package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksTest {
    /** The captured idle replies, with [patch] (lid → byte index → value) applied. */
    private fun replies(vararg patch: Triple<String, Int, Int>): Map<String, String> = PID_IDLE.mapValues { (lid, hex) ->
        val b = hex.chunked(2).toMutableList()
        for ((l, i, v) in patch) if (l == lid) b[i] = "%02X".format(v)
        "61$lid" + b.joinToString("")
    }.mapKeys { "21${it.key}" }

    private fun keys(f: List<Finding>) = f.map { it.key }.toSet()

    @Test fun capturedIdleOnlyFlagsTheStoredCode() {
        // The car really had P252F stored; everything else in the capture is healthy
        val f = Checker().check(0, replies(), 0.0, false)
        assertEquals(setOf("dtc"), keys(f))
        assertEquals("1 fault code is stored in the engine ECU.", f.single().text)
    }

    @Test fun hotCoolantWarnsOnlyAfterItsHoldTime() {
        val c = Checker()
        val hot = replies(Triple("05", 0, 108 + 40))
        assertTrue("coolant_hot" !in keys(c.check(0, hot, 0.0, false)))
        assertTrue("coolant_hot" !in keys(c.check(8_000, hot, 0.0, false)))
        assertTrue("coolant_hot" in keys(c.check(10_000, hot, 0.0, false)))
        // A normal reading resets the clock
        c.check(12_000, replies(), 0.0, false)
        assertTrue("coolant_hot" !in keys(c.check(14_000, hot, 0.0, false)))
    }

    @Test fun overheatIsCritical() {
        val c = Checker()
        val boiling = replies(Triple("05", 0, 115 + 40))
        c.check(0, boiling, 0.0, false)
        val f = c.check(5_000, boiling, 0.0, false).single { it.key == "coolant_overheat" }
        assertEquals(CRIT, f.level)
    }

    @Test fun ecuOverfillFlagWarns() {
        val c = Checker()
        val overfill = replies(Triple("E6", 3, 0x41 or 0x20)) // bit 26 = 0x20 of payload byte 3
        c.check(0, overfill, 0.0, false)
        assertTrue("oil_overfill" in keys(c.check(10_000, overfill, 0.0, false)))
    }

    @Test fun sootRisingFastOverAFewKmWarns() {
        val c = Checker()
        assertTrue("soot_rate" !in keys(c.check(0, replies(Triple("DD", 17, 20)), 0.0, false)))        // 40 %
        assertTrue("soot_rate" !in keys(c.check(60_000, replies(Triple("DD", 17, 22)), 1_000.0, false))) // 44 % after 1 km: too short to judge
        assertTrue("soot_rate" in keys(c.check(180_000, replies(Triple("DD", 17, 25)), 3_000.0, false)))  // 50 % after 3 km
        // A regen resets the window: burning soot isn't a rise
        c.check(240_000, replies(Triple("DD", 17, 25)), 4_000.0, true)
        assertTrue("soot_rate" !in keys(c.check(300_000, replies(Triple("DD", 17, 26)), 7_000.0, false)))
    }

    @Test fun staleSlowValuesAreIgnored() {
        val c = Checker()
        c.check(0, replies(), 0.0, false)
        // E1 (fault codes) not read again for 7 min: the old count no longer counts as current
        val noCodes = replies().filterKeys { it != "21E1" }
        assertTrue("dtc" !in keys(c.check(7 * 60_000L, noCodes, 0.0, false)))
    }

    @Test fun regenHistoryTrends() {
        fun r(startMin: Long, mins: Long, gapKm: Double?) = Db.Regen(startMin * 60_000, (startMin + mins) * 60_000, gapKm?.times(1000), 60, 20)
        assertEquals(emptyList<Finding>(), trends(listOf(r(0, 20, null), r(1000, 20, 300.0))))
        assertEquals(setOf("regen_frequent"), keys(trends(listOf(r(0, 20, null), r(1000, 20, 90.0)))))
        assertEquals(setOf("regen_interrupted"), keys(trends(listOf(r(0, 5, null), r(1000, 6, 200.0), r(2000, 4, 200.0)))))
        // An ongoing regen isn't judged yet
        assertEquals(emptyList<Finding>(), trends(listOf(r(0, 20, null), Db.Regen(1000 * 60_000, null, 50_000.0, 60, null))))
    }
}
