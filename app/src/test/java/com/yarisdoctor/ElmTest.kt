package com.yarisdoctor

import org.junit.Assert.assertEquals
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
    }

    @Test fun decodersUseDpfViewerOffsets() {
        val p = "0".repeat(16) + "0190" + "0".repeat(13) + "1" + "0".repeat(4) + "2A"
        assertEquals(40, p.length)
        assertEquals(250, tempOf(p))   // 0x0190 * 0.625
        assertEquals(true, regenOf(p)) // char 33
        assertEquals(84, sootOf(p))    // 0x2A * 2
        assertNull(sootOf("61DD"))
    }

    @Test fun decodersReadOtherBytePositions() {
        val p = "61DD" + "2A" + "0190" + "01" // bytes 0-1 echo, 2 = soot, 3-4 = temp, 5 = regen
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
