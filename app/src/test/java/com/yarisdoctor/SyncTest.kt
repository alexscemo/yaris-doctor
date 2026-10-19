package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTest {
    @Test fun hubUrlFillsInSchemeAndDefaultPort() {
        assertEquals("http://192.168.1.50:8765", hubUrl("192.168.1.50"))
        assertEquals("http://192.168.1.50:9000", hubUrl(" 192.168.1.50:9000/ "))
        assertEquals("https://hub.lan:8765", hubUrl("https://hub.lan"))
        assertEquals("http://hub.lan:80/x", hubUrl("http://hub.lan:80/x"))
        assertEquals("", hubUrl("  "))
    }

    @Test fun mergeCountsAHandOverBurnOnceButNeverMergesOpenRegens() {
        val m = 60_000L
        val merged = mergeRegens(listOf(
            Db.Regen(10 * m, 20 * m, null, 80, 30),   // my phone
            Db.Regen(19 * m, 22 * m, null, 35, 10),   // partner's phone caught the end of the same burn
            Db.Regen(100 * m, null, null, 70, null),  // a phone killed mid-burn: never closed
            Db.Regen(101 * m, 110 * m, null, 75, 12), // a later burn must not be swallowed by it
        ))
        assertEquals(listOf(10 * m, 100 * m, 101 * m), merged.map { it.start })
        assertEquals(22 * m, merged[0].end)
        assertEquals(10, merged[0].sootEnd)
    }
}
