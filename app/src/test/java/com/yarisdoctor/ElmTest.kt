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

    @Test fun decodersUseDpfViewerOffsets() {
        val p = "0".repeat(16) + "0190" + "0".repeat(13) + "1" + "0".repeat(4) + "2A"
        assertEquals(40, p.length)
        assertEquals(250, tempOf(p))   // 0x0190 * 0.625
        assertEquals(true, regenOf(p)) // char 33
        assertEquals(84, sootOf(p))    // 0x2A * 2
        assertNull(sootOf("61DD"))
    }
}
