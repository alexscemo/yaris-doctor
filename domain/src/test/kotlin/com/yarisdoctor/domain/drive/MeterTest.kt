package com.yarisdoctor.domain.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeterTest {
    /** An ELM that tracks its header: the engine answers on 7E0, the meter on 7C0. */
    private class FakeElm(val meter: Map<String, String>, val engine: Map<String, String> = emptyMap()) : ObdLink {
        var header = "7E0"
        var headers = false
        var inits = 0
        val sent = mutableListOf<String>()
        override fun init() { inits++; header = "7E0"; headers = false }
        override fun send(cmd: String, timeoutMs: Long): String? {
            sent += cmd
            if (cmd.startsWith("AT SH ")) header = cmd.substring(6)
            if (cmd == "AT H1") headers = true
            if (cmd.startsWith("AT")) return "OK"
            return (if (header == "7C0") meter else engine)[cmd]
        }
        override fun monitor(ms: Long) = listOf("6110000A1B2C", "6110000A1B2C", "6110000A1B2D", "BUFFERFULL")
    }

    @Test fun fuelReadsTheMeterAndGoesBackToTheEngine() {
        val elm = FakeElm(mapOf("2129" to "61295A"))
        assertEquals("61295A", Meter(elm).fuel())
        assertEquals("7E0", elm.header)
        assertEquals(listOf("AT SH 7C0", "AT CRA 7C8", "2129", "AT SH 7E0", "AT CRA 7E8"), elm.sent)
        assertEquals(45.0, Meter.litres("5A")!!, 1e-9)
    }

    @Test fun fuelRejectsAWrongReplyAndStillRestores() {
        val elm = FakeElm(mapOf("2129" to "7F2112"))
        assertNull(Meter(elm).fuel())
        assertEquals("7E0", elm.header)
    }

    @Test fun probeAsksListensAndResetsTheLink() {
        val elm = FakeElm(meter = mapOf("2102" to "6102AABB"), engine = mapOf("2128" to "6128000102"))
        val r = Meter(elm).probe()
        assertEquals("6128000102", r["ODO:7E0:2128"])
        assertEquals("", r["ODO:7E0:21ED"]) // no answer is recorded too
        assertEquals("6102AABB", r["ODO:7C0:2102"])
        assertEquals("6110000A1B2C 6110000A1B2D BUFFERFULL", r["ODO:611"])
        assertEquals("611×3:6110000A1B2D BUF×1:BUFFERFULL", r["ODO:ALL"])
        assertEquals(1, elm.inits)
        assertEquals("7E0", elm.header)
    }
}
