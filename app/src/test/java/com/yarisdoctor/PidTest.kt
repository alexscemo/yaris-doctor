package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Idle payloads from the Techstream capture (NLP130_engine_PIDs.xlsx, LIDs sheet) and the values Techstream showed. */
class PidTest {
    private fun pid(name: String, lid: String) = PIDS.single { it.name == name && it.lid == lid }
    private fun Pid.shown(payload: String) = decode(payload)?.let(::format) // what All data shows

    @Test fun tableIsWholeAndConsistent() {
        assertEquals(102, PIDS.size)
        assertEquals(35, PID_LENGTHS.size)
        assertEquals(PIDS.size, PIDS.map { it.id }.toSet().size)
        for (p in PIDS) assertTrue(p.name, p.bitEnd < p.len * 8 && p.bitStart <= p.bitEnd)
    }

    @Test fun decodesCapturedPayloads() {
        val dd = "440029642420291432 09C4C7551200 4F8D17".replace(" ", "")
        assertEquals(46.0, pid("PM Accumulation Ratio", "DD").decode(dd)!!, 0.0)
        assertEquals(28.0, pid("Fuel Temperature", "DD").decode(dd)!!, 0.0)
        assertEquals(25000.0, pid("Target Common Rail Pressure", "DD").decode(dd)!!, 0.0)
        assertEquals(100.97, pid("Target Booster Pressure", "DD").decode(dd)!!, 1e-9) // 5170*125/64 = 10097 (truncated)
        val f1 = "00660060005F005636"
        assertEquals(59.3, pid("Exhaust Temperature B1S1", "F1").decode(f1)!!, 1e-9)
        assertEquals(53.7, pid("Exhaust Temperature B1S2", "F1").decode(f1)!!, 1e-9)
        assertEquals(14.0, pid("Intake Air Temp (Turbo)", "F1").decode(f1)!!, 0.0)
        assertEquals(800.0, pid("Engine Speed", "0C").decode("0C82")!!, 0.0)
        assertEquals(14.0, pid("Battery Voltage", "42").decode("36EC")!!, 0.0)
        assertEquals(0.597, pid("DPF Differential Pressure", "EF").decode("059987A6")!!, 1e-9) // (1433*125/32 - 5000) / 1000
    }

    @Test fun flagsEnumsAndSigns() {
        val e6 = "18800C4104"
        assertEquals("ON", pid("Neutral Position SW Signal", "E6").shown(e6))
        assertEquals("OK", pid("EGR Close Lrn. Status", "E6").shown(e6))
        assertEquals("OFF", pid("Engine Oil Volume (Overfill)", "E6").shown(e6))
        assertEquals("Standby", pid("DPNR/DPF Status Reju(PM)", "DF").shown("08D004FF008CE9483E00143200 0F00".replace(" ", "")))
        assertEquals("Operate", pid("DPNR/DPF Status Reju(PM)", "DF").shown("08D004FF008CE9483E02143200 0F00".replace(" ", "")))
        // 0xFFBA as a signed 16-bit field = -70 → -7.0 deg
        assertEquals(-7.0, pid("Pilot 1 Injection Timing", "AD").decode("0".repeat(16) + "FFBA" + "0".repeat(32))!!, 1e-9)
        assertNull(pid("Engine Speed", "0C").decode("0C"))
    }
}
