package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

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

    @Test fun sendReturnsAtThePromptAndTimesIt() {
        val dongle = PipedOutputStream()
        val elm = Elm(PipedInputStream(dongle), ByteArrayOutputStream())
        Thread { Thread.sleep(50); dongle.write("61 0C 0C 82 \r\r>".toByteArray()); dongle.flush() }.start()
        assertEquals("610C0C82", elm.send("210C1"))
        val t = elm.timings.getValue("210C1")
        assertEquals(1, t.ok)
        assertTrue("first byte after ~50 ms, was ${t.firstMs}", t.firstMs in 40..1000)
        assertNull(elm.send("2110", timeoutMs = 100)) // no reply: timed out, counted as a miss
        assertEquals(0, elm.timings.getValue("2110").ok)
        assertTrue(elm.timings.keys.none { it.startsWith("AT") })
    }

    @Test fun monitorStopsTheElmAndReadsToItsPrompt() {
        val dongle = PipedOutputStream()
        val sent = ByteArrayOutputStream()
        val elm = Elm(PipedInputStream(dongle), sent)
        Thread {
            Thread.sleep(30); dongle.write("611 00 12 34\r611 00 12 35\r".toByteArray()); dongle.flush()
            // The stop byte arrives after the listen window; the ELM answers it with STOPPED and its prompt
            while (!sent.toString().endsWith("AT MA\r\r")) Thread.sleep(5)
            dongle.write("STOPPED\r\r>".toByteArray()); dongle.flush()
            while (!sent.toString().endsWith("AT H0\r")) Thread.sleep(5)
            dongle.write("OK\r\r>".toByteArray()); dongle.flush()
        }.start()
        assertEquals(listOf("611 00 12 34", "611 00 12 35", "STOPPED"), elm.monitor(200))
        assertEquals("OK", elm.send("AT H0")) // the monitor's prompt was used up, not left for this
    }
}
