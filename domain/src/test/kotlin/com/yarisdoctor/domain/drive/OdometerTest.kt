package com.yarisdoctor.domain.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class OdometerTest {
    private val m89159 = 89_159 * MILE_M

    @Test fun advanceAddsTheMeanSpeed() {
        assertEquals(15.0, Odometer.advance(0.0, 50.0, 58.0, 1000), 1e-3) // 54 km/h for 1 s
        assertEquals(100.0, Odometer.advance(100.0, 50.0, 50.0, 0), 1e-3)
        assertEquals(100.0, Odometer.advance(100.0, 50.0, 50.0, Odometer.MAX_STEP_MS + 1), 1e-3) // a stall adds nothing
    }

    @Test fun snapKeepsAFigureInsideTheMile() {
        assertEquals(m89159 + 300, Odometer.snap(m89159 + 300, 89_159, firstOfDrive = true), 1e-3)
        assertEquals(m89159 + 300, Odometer.snap(m89159 + 300, 89_159, firstOfDrive = false), 1e-3)
    }

    @Test fun snapMovesSpeedDriftToTheNearestEndOfTheMile() {
        assertEquals(m89159, Odometer.snap(m89159 - 200, 89_159, firstOfDrive = false), 1e-3) // speed read low
        assertEquals(m89159 + MILE_M - 1, Odometer.snap(m89159 + MILE_M + 200, 89_159, firstOfDrive = false), 1e-3) // read high
    }

    @Test fun snapStartsAFarFigureInTheMiddleOfTheMile() {
        // An old GPS figure, or driven without the phone: nothing tells where in the mile the car is
        assertEquals(m89159 + MILE_M / 2, Odometer.snap(558_597.0, 89_159, firstOfDrive = true), 1e-3)
    }
}
