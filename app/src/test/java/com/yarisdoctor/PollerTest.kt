package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollerTest {
    /** A fake ECU that answers every table code with a zero payload of the right length. */
    private fun ecu(sent: MutableList<String>, reply: (String) -> String? = { c -> PID_LENGTHS[c.take(4)]?.let { "6" + c.substring(1, 4) + "00".repeat(it) } }) =
        { c: String -> sent += c; reply(c) }

    @Test fun everyTableCodeIsPolled() {
        val all = Poller.FAST + Poller.MEDIUM + Poller.SLOW
        assertEquals(all.size, all.toSet().size)
        assertEquals(PID_LENGTHS.keys, all.toSet())
    }

    @Test fun fastEveryCycleMediumInTurnSlowWhenDue() {
        val sent = mutableListOf<String>()
        val p = Poller(ecu(sent))
        var t = 0L
        val seen = HashSet<String>()
        repeat(Poller.MEDIUM.size) { seen += p.cycle(t, emptyList()).keys; t += 2000 }
        assertEquals(PID_LENGTHS.keys, seen) // a whole table in one round of medium codes
        sent.clear()
        p.cycle(t, emptyList())
        assertTrue(sent.containsAll(Poller.FAST.map { if (PID_LENGTHS.getValue(it) + 2 <= 7) it + "1" else it }))
        assertFalse(sent.any { it.take(4) in Poller.SLOW }) // all read less than SLOW_MS ago
    }

    @Test fun wrongLengthOrHeaderIsRetriedThenDropped() {
        val sent = mutableListOf<String>()
        var n = 0
        val p = Poller(ecu(sent) { c -> if (c.startsWith("210C")) (if (n++ == 0) "610C0C" else "610C0C82") else null })
        assertEquals("610C0C82", p.cycle(0, listOf("210C"))["210C"])
        assertEquals(listOf("210C1", "210C"), sent.take(2))
        assertEquals(null, Poller(ecu(mutableListOf()) { "620C0C82" }).cycle(0, listOf("210C"))["210C"])
    }

    @Test fun deadEcuStopsTheCycleEarly() {
        val sent = mutableListOf<String>()
        assertTrue(Poller(ecu(sent) { null }).cycle(0, emptyList()).isEmpty())
        assertEquals(6, sent.size) // 3 codes, each tried twice
    }

    @Test fun countSuffixDroppedWhenDongleRejectsIt() {
        val sent = mutableListOf<String>()
        val p = Poller(ecu(sent) { c -> if (c.length == 5) "?" else PID_LENGTHS[c]?.let { "6" + c.substring(1) + "00".repeat(it) } })
        repeat(3) { p.cycle(it * 2000L, emptyList()) }
        sent.clear()
        p.cycle(10_000, emptyList())
        assertFalse(sent.any { it.length == 5 })
    }

    @Test fun unsupportedCodeIsParked() {
        val sent = mutableListOf<String>()
        val p = Poller(ecu(sent) { c -> if (c.startsWith("21EF")) "NO DATA".let { null } else PID_LENGTHS[c.take(4)]?.let { "6" + c.substring(1, 4) + "00".repeat(it) } })
        repeat(Poller.PARK_AFTER) { p.cycle(it * 2000L, emptyList()) }
        sent.clear()
        p.cycle(99_000, emptyList())
        assertFalse(sent.any { it.startsWith("21EF") })
    }

    @Test fun userCodesOutsideTheTablePassThrough() {
        val p = Poller(ecu(mutableListOf()) { c -> if (c == "221234") "621234AB" else null })
        assertEquals("621234AB", p.cycle(0, listOf("221234"))["221234"])
    }
}
