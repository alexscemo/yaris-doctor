package com.yarisdoctor.domain.drive

import com.yarisdoctor.domain.model.Regen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegensTest {
    @Test fun burnPercentIsShareOfStartingSootBurnedOff() {
        assertEquals(0, burnPercent(80, 80))
        assertEquals(50, burnPercent(80, 40))
        assertEquals(100, burnPercent(80, 0))
        assertEquals(0, burnPercent(40, 45)) // soot reading ticked up: never negative
        assertNull(burnPercent(null, 40))
        assertNull(burnPercent(0, 0))
    }

    @Test fun mergeCountsAHandOverBurnOnceButNeverMergesOpenRegens() {
        val m = 60_000L
        val merged = mergeRegens(listOf(
            Regen(10 * m, 20 * m, null, 80, 30),   // my phone
            Regen(19 * m, 22 * m, null, 35, 10),   // partner's phone caught the end of the same burn
            Regen(100 * m, null, null, 70, null),  // a phone killed mid-burn: never closed
            Regen(101 * m, 110 * m, null, 75, 12), // a later burn must not be swallowed by it
        ))
        assertEquals(listOf(10 * m, 100 * m, 101 * m), merged.map { it.start })
        assertEquals(22 * m, merged[0].end)
        assertEquals(10, merged[0].sootEnd)
    }
}
