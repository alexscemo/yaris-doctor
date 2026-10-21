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

    @Test fun toyotaManualLimits() {
        assertTrue(firesAfter("idle_fuel_high", 60_000, replies(warm, Triple("AD", 20, 0x01), Triple("AD", 21, 0xCD)))) // 9.0 mm³/st
        assertTrue(firesAfter("pump_scv", 30_000, replies(Triple("DD", 4, 0x32), Triple("DD", 5, 0x00))))                 // 1600 mA
        assertTrue(firesAfter("learned_values", 60_000, replies(Triple("DD", 15, 0x80), Triple("DD", 16, 0x00))))          // vanes 2.5 V
        val shorted = replies(Triple("F1", 6, 0x06), Triple("F1", 7, 0x00)) // 960 °C = short
        assertTrue(firesAfter("exhaust_sensor", 60_000, shorted))
        assertTrue(!firesAfter("exhaust_hot", 5_000, shorted)) // one fault, one warning
        // Throttle at its reset value after a battery disconnect isn't out of range
        assertTrue(!firesAfter("learned_values", 60_000, replies(Triple("DF", 0, 0x0A), Triple("DF", 1, 0xE1)))) // 21.25 deg
    }

    @Test fun normalValuesNameRealPids() {
        for (name in TOYOTA_NORMAL.keys + TOYOTA_NORMAL_2015.keys) assertTrue(name, PIDS.any { it.name == name })
        assertTrue(toyotaNormal("PM Accumulation Ratio", true)!!.startsWith("0-173"))
        assertTrue(toyotaNormal("MAF", true) == TOYOTA_NORMAL["MAF"])
    }

    @Test fun sootRulesFollowTheModelYear() {
        fun soot(pct: Int) = replies(Triple("DD", 17, pct / 2))
        fun found(model2015: Boolean, pct: Int) = Checker(model2015).let { c -> c.check(0, soot(pct), false); keys(c.check(30_000, soot(pct), false)) - "dtc" }
        assertEquals(setOf("soot_high"), found(false, 110))
        assertEquals(emptySet<String>(), found(true, 110))  // 2015+: a regen is needed at 118 %
        assertEquals(setOf("soot_critical"), found(false, 160))
        assertEquals(setOf("soot_high"), found(true, 160))  // 2015+: normal up to 173 %
        assertEquals(setOf("soot_critical"), found(true, 180))
    }

    @Test fun regenFlagMustAgreeWithAfterInjection() {
        val inject = replies(Triple("AD", 6, 0x01), Triple("AD", 7, 0xF4)) // 500 µs
        val none = replies()
        /** Feeds a cycle every 18 s (the AD read interval) for [mins]; [aip] picks the reply per cycle. Returns the last findings. */
        fun run(regen: Boolean, mins: Int, aip: (Int) -> Map<String, String>): Set<String> {
            val c = Checker()
            var f = emptySet<String>()
            for (i in 0..mins * 60 / 18) f = keys(c.check(i * 18_000L, aip(i), regen))
            return f
        }
        // Agreeing: a regen with the odd 0 read (overrun), or no regen and no after injection
        assertTrue("regen_flag_check" !in run(true, 10) { if (it % 4 == 0) none else inject })
        assertTrue("regen_flag_check" !in run(false, 10) { none })
        // A wrong regen byte: the ECU after-injects for a whole regen while the flag stays off, dips included
        assertTrue("regen_flag_check" in run(false, 6) { if (it % 4 == 0) none else inject })
        // ... but a short purge doesn't warn
        assertTrue("regen_flag_check" !in run(false, 10) { if (it in 5..8) inject else none })
        // Flag stuck on with no after injection
        assertTrue("regen_flag_check" in run(true, 6) { none })
        assertTrue("regen_flag_check" !in run(true, 4) { none })
    }

    @Test fun oilChangeDueAt15000Km() {
        assertEquals(null, oilDue(null)) // never recorded: no warning
        assertEquals(null, oilDue(14_999_000.0))
        assertEquals("oil_change_due", oilDue(15_200_000.0)?.key)
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

    @Test fun regenFloorAndPeakTemperature() {
        fun r(startMin: Long, floor: Int?, peak: Int?) = Db.Regen(startMin * 60_000, (startMin + 20) * 60_000, 300_000.0, 88, 10, floor, peak)
        // The owner's car before the ash record was restored: 40 % five minutes after a regen, peak 627 °C
        assertEquals(setOf("soot_floor"), keys(trends(listOf(r(0, 40, 627)))))
        assertEquals(emptyList<Finding>(), trends(listOf(r(0, 40, 627), r(1000, 12, 640))))
        // The newest regen with a reading counts: a drive that ended before the floor was read doesn't clear it
        assertEquals(setOf("soot_floor"), keys(trends(listOf(r(0, 40, 627), r(1000, null, null)))))
        // 720 °C is past the 2011-2014 range (700) but inside the 2015+ one (750)
        assertEquals(setOf("regen_hot"), keys(trends(listOf(r(0, 12, 720)))))
        assertEquals(emptyList<Finding>(), trends(listOf(r(0, 12, 720)), model2015 = true))
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
            assertTrue("${r.key}: the hub rejects the whole sync over 600 characters", r.about.length <= 600)
            for (name in r.uses) assertTrue("${r.key} reads unknown value $name", PIDS.any { it.name == name })
        }
        for ((key, _, about) in TREND_RULES) assertTrue(key, about.length <= 600)
        assertEquals("every cycle", Poller.pollRate("0C"))
        assertEquals("every 8 cycles", Poller.pollRate("AD"))
        assertEquals("every 5 min", Poller.pollRate("E1"))
    }
}
