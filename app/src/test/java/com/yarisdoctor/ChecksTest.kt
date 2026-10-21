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
        val f = Checker().check(0, replies(), false)
        assertEquals(setOf("dtc"), keys(f))
        assertEquals("1 fault code is stored in the engine ECU.", f.single().text)
    }

    @Test fun hotCoolantWarnsOnlyAfterItsHoldTime() {
        val c = Checker()
        val hot = replies(Triple("05", 0, 108 + 40))
        assertTrue("coolant_hot" !in keys(c.check(0, hot, false)))
        assertTrue("coolant_hot" !in keys(c.check(8_000, hot, false)))
        assertTrue("coolant_hot" in keys(c.check(10_000, hot, false)))
        // A normal reading resets the clock
        c.check(12_000, replies(), false)
        assertTrue("coolant_hot" !in keys(c.check(14_000, hot, false)))
    }

    @Test fun overheatIsCritical() {
        val c = Checker()
        val boiling = replies(Triple("05", 0, 115 + 40))
        c.check(0, boiling, false)
        val f = c.check(5_000, boiling, false).single { it.key == "coolant_overheat" }
        assertEquals(CRIT, f.level)
    }

    private val warm = Triple("05", 0, 85 + 40)

    /** True when [key] is not active at first but is once [holdMs] has passed, with the same readings throughout. */
    private fun firesAfter(key: String, holdMs: Long, r: Map<String, String>): Boolean {
        val c = Checker()
        val early = key in keys(c.check(0, r, false)) || key in keys(c.check(holdMs - 2_000, r, false))
        return !early && key in keys(c.check(holdMs, r, false))
    }

    @Test fun warmIdleCaptureOnlyFlagsTheStoredCode() {
        val c = Checker()
        c.check(0, replies(warm), false)
        assertEquals(setOf("dtc"), keys(c.check(600_000, replies(warm), false)))
    }

    @Test fun railPressureNeedsHalfAMinuteOffTarget() {
        // 25 MPa at the captured idle against a 59 MPa target: a pedal transient for 20 s is normal, half a minute is not
        val off = replies(Triple("DD", 9, 0x17), Triple("DD", 10, 0x0C))
        assertTrue(firesAfter("rail_pressure", 30_000, off))
    }

    @Test fun mafLowOnlyCountsWithTheEgrClosed() {
        val revs = arrayOf(Triple("0C", 0, 0x1F), Triple("0C", 1, 0x40)) // 2000 rpm
        assertTrue(firesAfter("maf_low", 30_000, replies(*revs, Triple("EA", 2, 0), Triple("A3", 6, 70))))  // EGR shut, 0.70
        assertTrue(!firesAfter("maf_low", 30_000, replies(*revs, Triple("EA", 2, 0), Triple("A3", 6, 92)))) // healthy 0.92
        assertTrue(!firesAfter("maf_low", 30_000, replies(*revs, Triple("A3", 6, 60))))                     // EGR open: dilution, not a fault
    }

    @Test fun egrAndActuatorsOffTarget() {
        assertTrue(firesAfter("egr_tracking", 15_000, replies(Triple("EA", 2, 230))))              // 90 % against 55 %
        assertTrue(firesAfter("throttle_tracking", 10_000, replies(Triple("DD", 2, 0x50))))        // 80 % against 41 %
        assertTrue(firesAfter("turbo_vanes", 10_000, replies(Triple("DD", 11, 100))))              // 50 % against 100 %
        // A few seconds off target while revving is normal
        val c = Checker()
        c.check(0, replies(Triple("EA", 2, 230)), false)
        c.check(6_000, replies(), false)
        assertTrue("egr_tracking" !in keys(c.check(16_000, replies(Triple("EA", 2, 230)), false)))
    }

    @Test fun egrCoolerStillBypassedWhenWarmAndDriving() {
        val driving = replies(warm, Triple("0D", 0, 80)) // the captured bypass byte is 3 = Bypass
        assertTrue(firesAfter("egr_bypass_warm", 180_000, driving))
        assertTrue("egr_bypass_warm" !in keys(Checker().let { it.check(0, replies(Triple("0D", 0, 80)), false); it.check(200_000, replies(Triple("0D", 0, 80)), false) }))
        val cooler = replies(warm, Triple("0D", 0, 80), Triple("EA", 5, 1))
        assertTrue("egr_bypass_warm" !in keys(Checker().let { it.check(0, cooler, false); it.check(200_000, cooler, false) }))
    }

    @Test fun learningIdleAndDpfFlow() {
        assertTrue(firesAfter("learn_status", 300_000, replies(warm, Triple("E6", 2, 0x04))))       // EGR closed position NG
        assertTrue(firesAfter("idle_rpm", 60_000, replies(warm, Triple("0C", 0, 0x14), Triple("0C", 1, 0x50)))) // 1300 rpm
        assertTrue(firesAfter("dpf_pressure_flow", 30_000, replies(Triple("10", 0, 0x0B), Triple("10", 1, 0xB8), Triple("EF", 0, 0x05), Triple("EF", 1, 0x1A))))
    }

    @Test fun ecuOverfillFlagWarns() {
        val c = Checker()
        val overfill = replies(Triple("E6", 3, 0x41 or 0x20)) // bit 26 = 0x20 of payload byte 3
        c.check(0, overfill, false)
        assertTrue("oil_overfill" in keys(c.check(10_000, overfill, false)))
    }

    @Test fun dpfSensorZeroNeedsThreeEngineOffReadings() {
        assertEquals(null, sensorZero(listOf(0.398, 0.398, 0.296, 0.296))) // the owner's car on 21 Oct
        assertEquals(null, sensorZero(listOf(2.0, 2.0)))                     // too few to judge
        assertEquals("dpf_sensor_zero", sensorZero(listOf(1.4, 1.5, 1.3, 0.4, 1.6))?.key)
        assertEquals("dpf_sensor_zero", sensorZero(listOf(-0.9, -0.8, -0.9))?.key)
    }

    @Test fun dpfRestrictionAndCondition() {
        // 5.8 kPa at 25.4 g/s and 380 °C after the DPF: 47 l/s of exhaust, so 12.3 kPa per 100 l/s
        assertEquals(12.34, restriction(5.8, 25.4, 380.0, 2400.0)!!, 0.01)
        assertEquals(null, restriction(5.8, 25.4, 380.0, 900.0)) // idle: too little flow to read
        assertEquals(null, restriction(0.5, 5.0, 150.0, 1500.0))
        val after = List(8) { 5.5 }
        val later = List(8) { 13.5 }
        val d = dpfCondition(0, after, later, 40.0)
        assertEquals(5.5, d.base!!, 0.0)
        assertEquals(0.2, d.perKm!!, 1e-9)
        assertEquals(null, dpfCondition(0, after, later, 3.0).perKm)   // too short a drive to give a rate
        assertEquals(null, dpfCondition(0, after.take(3), later, 40.0).base) // too few readings
        assertEquals(null, dpfCondition(0, after, later, Double.NaN).perKm)  // odometer missing
    }

    @Test fun tripSummaryReadsInOneLine() {
        assertEquals("25 mi · soot 40 → 70 % · 1 regen · no warnings", tripSummary(40_000.0, 40, 70, 1, 0, Db.METRES_PER_MILE, "mi"))
        assertEquals("3 km · no regen · 2 warnings", tripSummary(3_000.0, null, null, 0, 2, 1000.0, "km"))
    }

    @Test fun staleSlowValuesAreIgnored() {
        val c = Checker()
        c.check(0, replies(), false)
        // E1 (fault codes) not read again for 7 min: the old count no longer counts as current
        val noCodes = replies().filterKeys { it != "21E1" }
        assertTrue("dtc" !in keys(c.check(7 * 60_000L, noCodes, false)))
    }

    @Test fun regenHistoryTrends() {
        fun r(startMin: Long, mins: Long, gapKm: Double?) = Db.Regen(startMin * 60_000, (startMin + mins) * 60_000, gapKm?.times(1000), 60, 20)
        assertEquals(emptyList<Finding>(), trends(listOf(r(0, 20, null), r(1000, 20, 300.0))))
        assertEquals(setOf("regen_frequent"), keys(trends(listOf(r(0, 20, null), r(1000, 20, 90.0)))))
        assertEquals(setOf("regen_interrupted"), keys(trends(listOf(r(0, 5, null), r(1000, 6, 200.0), r(2000, 4, 200.0)))))
        // An ongoing regen isn't judged yet
        assertEquals(emptyList<Finding>(), trends(listOf(r(0, 20, null), Db.Regen(1000 * 60_000, null, 50_000.0, 60, null))))
    }

    @Test fun alertDiffOpensOnlyNewKeysClosesGoneOnesUpdatesChangedText() {
        val d = diffAlerts(
            mapOf("dtc" to "1 fault code", "coolant_hot" to "106 °C", "battery_low" to "12.8 V"),
            listOf(Finding("dtc", WARN, "1 fault code"), Finding("coolant_hot", WARN, "108 °C"), Finding("soot_high", WARN, "102 %"),
                Finding("soot_high", WARN, "duplicate key")),
        )
        assertEquals(listOf("soot_high"), d.open.map { it.key }) // once: only new keys notify
        assertEquals(setOf("battery_low"), d.close)
        assertEquals(listOf("108 °C"), d.update.map { it.text }) // unchanged "dtc" is not rewritten every cycle
        // Nothing active: everything open closes, nothing opens
        assertEquals(setOf("dtc"), diffAlerts(mapOf("dtc" to "x"), emptyList()).close)
        assertTrue(diffAlerts(mapOf("dtc" to "x"), emptyList()).open.isEmpty())
    }

    @Test fun rulesTabDescribesEveryRuleAndItsInputsExist() {
        val keys = Checker.RULES.map { it.key } + TREND_RULES.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        for (r in Checker.RULES) {
            assertTrue(r.key, r.title.isNotBlank() && r.about.isNotBlank() && r.uses.isNotEmpty())
            for (name in r.uses) assertTrue("${r.key} reads unknown value $name", PIDS.any { it.name == name })
        }
        assertEquals("every cycle", Poller.pollRate("0C"))
        assertEquals("every 8 cycles", Poller.pollRate("AD"))
        assertEquals("every 5 min", Poller.pollRate("E1"))
    }
}
