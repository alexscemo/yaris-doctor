package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ElmTest {
    @Test fun singleLineStripsSpacesAndCr() = assertEquals("61F10102", parse("61 F1 01 02 \r\r"))

    @Test fun errorsReturnNull() {
        assertNull(parse("NO DATA\r\r"))
        assertNull(parse("UNABLE TO CONNECT\r"))
        assertNull(parse("CAN ERROR\r"))
    }

    @Test fun multiFrameJoinsDataAndTrimsToLength() {
        // 0x0A bytes = 20 chars; frame 1 is padded past that
        assertEquals("61DD0102030405060708", parse("00A\r0:61DD010203\r1:0405060708AAAA\r\r"))
    }

    @Test fun decodersUseDpfViewerOffsets() {
        val p = "0".repeat(16) + "0190" + "0".repeat(12) + "F1" + "0000" + "2A"
        assertEquals(40, p.length)
        assertEquals(250, tempOf(p))   // 0x0190 * 0.625
        assertEquals(true, regenOf(p)) // char 33 (byte 16) low nibble = 1, as in DPFViewer
        assertEquals(84, sootOf(p))    // 0x2A * 2
        assertNull(sootOf("61DD"))
    }

    @Test fun regenBitAtEngineStopIsNotARegen() {
        // Frames from the 21 Oct drive: mid-regen at 2421 rpm, and the same bit set after the engine stopped
        assertEquals(true, regenOf("61DF" + "08D004FF00670A483E001284FA5C01"))
        assertFalse(engineStopped("610C25D6"))
        assertEquals(true, regenOf("61DF" + "08D004FF006400483E001432FFF501"))
        assertTrue(engineStopped("610C0000"))
        assertTrue(engineStopped("610C0568")) // 346 rpm, spinning down: the 15:33:54 frame of the 21 Oct afternoon drive
        assertFalse(engineStopped("610C0C08")) // 770 rpm idle
        assertFalse(engineStopped(null))
    }
}
