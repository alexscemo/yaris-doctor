package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecorderTest {
    @Test fun burnPercentIsShareOfStartingSootBurnedOff() {
        assertEquals(0, burnPercent(80, 80))
        assertEquals(50, burnPercent(80, 40))
        assertEquals(100, burnPercent(80, 0))
        assertEquals(0, burnPercent(40, 45)) // soot reading ticked up: never negative
        assertNull(burnPercent(null, 40))
        assertNull(burnPercent(0, 0))
    }
}
