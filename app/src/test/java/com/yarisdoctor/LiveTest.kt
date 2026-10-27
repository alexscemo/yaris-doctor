package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-memory reading the screens follow: one per cycle, carrying a missed code only while it is still current. */
class LiveTest {

    @Test fun aReadingIsTheNewestOnePushed() {
        Live.push(1_000, soot = 40, temp = 220, regen = false, debug = false)
        assertEquals(40, Live.reading.value?.sample?.soot)
        assertEquals(220, Live.reading.value?.sample?.temp)
        assertEquals(1_000L, Live.reading.value?.sample?.t)
        assertFalse(Live.reading.value!!.debug)
    }

    @Test fun aCycleThatMissedACodeKeepsTheValueJustBeforeIt() {
        Live.push(10_000, soot = 52, temp = 300, regen = false, debug = false)
        // 21DD and 21F1 unanswered this cycle: blanking the card for a second would read as "no data"
        Live.push(12_000, soot = null, temp = null, regen = false, debug = false)
        assertEquals(52, Live.reading.value?.sample?.soot)
        assertEquals(300, Live.reading.value?.sample?.temp)
        assertEquals(12_000L, Live.reading.value?.sample?.t) // but it is this cycle's reading
    }

    @Test fun aCodeMissedAfterALongGapIsNotCarriedOver() {
        Live.push(1_000, soot = 52, temp = 300, regen = false, debug = false)
        // A new session (or hours later): the old soot is not this car's any more, so it must not be shown as live
        Live.push(3_700_000, soot = null, temp = null, regen = false, debug = false)
        assertNull(Live.reading.value?.sample?.soot)
        assertNull(Live.reading.value?.sample?.temp)
    }

    @Test fun theReadingCarriesTheModeItCameFrom() {
        // Debug mode swaps the database: a simulated reading must be recognisable, so the real log never shows it
        Live.push(1_000, soot = 10, temp = 100, regen = false, debug = true)
        assertTrue(Live.reading.value!!.debug)
    }
}
