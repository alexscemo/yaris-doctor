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

    @Test fun pidCodeNormalisesAndRejectsJunk() {
        assertEquals("21DD", pidCode(" 21 dd ", "21DF"))
        assertEquals("21DF", pidCode("  ", "21DF"))
        assertEquals("221234", pidCode("22 12 34", "21DF"))
        assertNull(pidCode("ATZ", "21DF"))
        assertNull(pidCode("21D", "21DF"))
        assertNull(pidCode("21", "21DF"))
        assertEquals("010C", pidCode("01 0c", "21DF"))
        // Services that change the ECU never pass, even well-formed: reset, clear DTCs, actuator / routine, write
        for (bad in listOf("1101", "0400", "3001", "3101", "3B0102", "2E1234")) assertNull(bad, pidCode(bad, "21DF"))
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
        assertFalse(engineStopped(null))
    }

    @Test fun formulasParseAndEvaluate() {
        val p = "61DD" + "2A" + "0190" + "13" // bytes 2 = 0x2A, 3-4 = 0x0190, 5 = 0x13
        fun f(s: String) = Formula.parse(s)!!
        assertEquals(42.0 * 3 + 1, f("A*3+1").eval(p, 2)!!, 0.0)       // * before +
        assertEquals(42.0 * 4, f("a * (3 + 1)").eval(p, 2)!!, 0.0)    // brackets, spaces, lower case
        assertEquals(250.0, f("(A*256+B)*0.625").eval(p, 3)!!, 0.0)
        assertEquals(3.0, f("A & 15").eval(p, 5)!!, 0.0)                // & binds loosest
        assertEquals(-42.0, f("-A").eval(p, 2)!!, 0.0)
        assertNull(f("A/0").eval(p, 2))                                // not finite
        assertNull(f("A+B").eval(p, 5))                                // B is past the end
        for (bad in listOf("", "A*", "(A", "A)", "E", "2..5", "A B", "ATZ")) assertNull(bad, Formula.parse(bad))
        assertEquals("A*2", formulaText(" ", "A*2"))
        assertNull(formulaText("A*", "A*2"))
        assertEquals(41, sootOf(p, 2, f("A-1")))
        assertEquals(false, regenOf(p, 5)) // default A&15 = 3, not 1
    }

    @Test fun decodersReadOtherBytePositions() {
        val p = "61DD" + "2A" + "0190" + "21" // bytes 0-1 echo, 2 = soot, 3-4 = temp, 5 = regen (low nibble 1)
        assertEquals(84, sootOf(p, 2))
        assertEquals(250, tempOf(p, 3))
        assertEquals(true, regenOf(p, 5))
        assertNull(sootOf(p, 6)) // past the end
        assertEquals(19, bytePos(" ", 19))
        assertEquals(7, bytePos(" 7 ", 19))
        assertNull(bytePos("64", 19))
        assertNull(bytePos("x", 19))
    }
}
