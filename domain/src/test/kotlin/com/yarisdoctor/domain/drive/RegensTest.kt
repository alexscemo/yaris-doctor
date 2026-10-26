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

    @Test fun mergeSameBurnJoinsABurnSplitByAnIgnitionCycle() {
        val m = 60_000L
        val merged = mergeSameBurn(listOf(
            Regen(10 * m, 20 * m, null, 80, 30, floor = 14, peakTemp = 600),
            Regen(25 * m, 35 * m, null, 32, 10, floor = 12, peakTemp = 620),
        ))
        assertEquals(1, merged.size)
        assertEquals(10 * m, merged[0].start)   // first start
        assertEquals(35 * m, merged[0].end)     // second end
        assertEquals(80, merged[0].sootStart)   // first soot start
        assertEquals(10, merged[0].sootEnd)     // second soot end
        assertEquals(12, merged[0].floor)       // the lower floor and higher peak carry over
        assertEquals(620, merged[0].peakTemp)
    }

    @Test fun mergeSameBurnAcceptsASmallSootDifference() {
        val m = 60_000L
        val merged = mergeSameBurn(listOf(
            Regen(0, 10 * m, null, 80, 30),
            Regen(11 * m, 20 * m, null, 25, 10), // 5 points lower: still the same burn
        ))
        assertEquals(1, merged.size)
    }

    @Test fun mergeSameBurnKeepsASecondBurnWhenTheSootDropped() {
        val m = 60_000L
        val merged = mergeSameBurn(listOf(
            Regen(0, 10 * m, null, 80, 30),
            Regen(11 * m, 20 * m, null, 20, 8), // 10 points lower: a real second burn
        ))
        assertEquals(listOf(0L, 11 * m), merged.map { it.start })
    }

    @Test fun mergeSameBurnKeepsBurnsOverHalfAnHourApart() {
        val m = 60_000L
        val merged = mergeSameBurn(listOf(
            Regen(0, 10 * m, null, 80, 30),
            Regen(130 * m, 140 * m, null, 32, 10), // 2 h later: continuous soot but not the same burn
        ))
        assertEquals(listOf(0L, 130 * m), merged.map { it.start })
    }

    @Test fun mergeSameBurnNeverMergesAnOpenRegen() {
        val m = 60_000L
        val merged = mergeSameBurn(listOf(
            Regen(0, null, null, 80, null),
            Regen(5 * m, 15 * m, null, 30, 10),
        ))
        assertEquals(2, merged.size)
        assertNull(merged[0].end)
    }

    @Test fun mergeSameBurnCollapsesThreePartialBurns() {
        val m = 60_000L
        val merged = mergeSameBurn(listOf(
            Regen(0, 5 * m, null, 90, 70),
            Regen(10 * m, 15 * m, null, 72, 45),
            Regen(20 * m, 30 * m, null, 47, 10),
        ))
        assertEquals(1, merged.size)
        assertEquals(0L, merged[0].start)
        assertEquals(30 * m, merged[0].end)
        assertEquals(90, merged[0].sootStart)
        assertEquals(10, merged[0].sootEnd)
    }
}
