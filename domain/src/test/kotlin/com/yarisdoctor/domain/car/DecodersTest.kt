package com.yarisdoctor.domain.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodersTest {
    @Test fun decodersUseDpfViewerOffsets() {
        val p = "0".repeat(16) + "0190" + "0".repeat(12) + "F1" + "0000" + "2A"
        assertEquals(40, p.length)
        assertEquals(250, tempOf(p))   // 0x0190 * 0.625
        assertEquals(84, sootOf(p))    // 0x2A * 2
        assertNull(sootOf("61DD"))
    }

    @Test fun regenIsAfterInjectionAboveZero() {
        assertEquals(false, regenOf("61AD" + PID_IDLE.getValue("AD"))) // idle: 0 µs
        assertEquals(true, regenOf("61AD" + "000000000000" + "0130"))  // 304 µs, the 29 Oct burn's average
        assertNull(regenOf("61AD0000"))
    }

    @Test fun engineStoppedGoesByRpm() {
        assertFalse(engineStopped("610C25D6"))
        assertTrue(engineStopped("610C0000"))
        assertTrue(engineStopped("610C0568")) // 346 rpm, spinning down: the 15:33:54 frame of the 21 Oct afternoon drive
        assertFalse(engineStopped("610C0C08")) // 770 rpm idle
        assertFalse(engineStopped(null))
    }
}
