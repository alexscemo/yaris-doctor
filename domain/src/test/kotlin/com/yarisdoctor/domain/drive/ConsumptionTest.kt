package com.yarisdoctor.domain.drive

import com.yarisdoctor.domain.car.PIDS
import com.yarisdoctor.domain.car.PID_IDLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConsumptionTest {
    private val inj = PIDS.first { it.name == "Injection Volume" }

    @Test fun theCapturedIdleBurnsAboutHalfALitreAnHour() {
        val mm3 = inj.decode(PID_IDLE.getValue("AD"))!!
        assertEquals(6.09, mm3, 1e-9)
        val c = consumption(listOf(Triple(mm3, 800.0, 0.0)))!!
        assertEquals(0.58, c.litresPerHour, 0.01)
        assertNull(c.per100Km) // standing still: L/h, not L/100 km
    }

    @Test fun cruiseIsFuelOverDistanceAndEngineOffIsLeftOut() {
        // 20 mm³ at 2000 rpm = 4.8 L/h; at 100 km/h that's 4.8 L/100 km
        assertEquals(4.8, consumption(listOf(Triple(20.0, 2000.0, 100.0)))!!.per100Km!!, 1e-9)
        // Total over total: (4.8 + 0.58) L/h over (100 + 0) km/h, not the mean of 4.8 and infinity; the stopped engine adds nothing
        val c = consumption(listOf(Triple(20.0, 2000.0, 100.0), Triple(6.0, 800.0, 0.0), Triple(0.0, 0.0, 0.0)))!!
        assertEquals((4.8 + 0.576) / 2 / 50 * 100, c.per100Km!!, 1e-9)
        assertNull(consumption(listOf(Triple(0.0, 0.0, 0.0))))
    }

    @Test fun theSimulatedInjectionVolumeFollowsItsRevsAndSpeed() {
        fun v(f: Map<String, String>, name: String) = PIDS.first { it.name == name }.let { it.decode(f.getValue("21${it.lid}").substring(4))!! }
        val sim = Sim.live()
        repeat(50) {
            val f = sim.frames(Sim.Reading(30, 300, false))
            val want = Sim.injectionRaw(v(f, "Engine Speed").toInt(), v(f, "Vehicle Speed").toInt()) * 125 / 64 / 100.0
            assertEquals(want, v(f, "Injection Volume"), 1e-9)
        }
        assertEquals(22.8, Sim.injectionRaw(2000, 100) * 125 / 64 / 100.0, 0.05) // 6 + 100 × 0.12 + 1200 × 0.004
    }
}
