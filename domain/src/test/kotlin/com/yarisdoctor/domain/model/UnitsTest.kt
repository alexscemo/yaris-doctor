package com.yarisdoctor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    @Test fun imperialConvertsTheValuesTheCarReports() {
        val u = Units.IMPERIAL
        assertEquals(1.0, u.distance(METRES_PER_MILE), 0.0)
        assertEquals(62.137119, u.speed(100.0), 1e-6)
        assertEquals(212.0, u.temperature(100.0), 1e-9)
        assertEquals(9.0, u.temperatureDelta(5.0), 1e-9)
        assertEquals(14.5037738, u.pressure(100.0), 1e-6)
        assertEquals(14.5037738, u.pressureFromBar(1.0), 1e-6)
        assertEquals(145.037738, u.pressureFromMpa(1.0), 1e-5)
        assertEquals(3.3069, u.flow(25.0), 1e-4)
        assertEquals(211.888, u.flowFromLitresPerSecond(100.0), 1e-3)
        assertEquals(2.414016, u.sootPer100Distance(1.5), 1e-9)
    }

    @Test fun imperialFormatsWithEnoughPrecision() {
        val u = Units.IMPERIAL
        assertEquals("3.6 psi", u.pressureText(24.5))
        assertEquals("0.04 psi", u.pressureText(0.3))
        assertEquals("3.3 lb/min", u.flowText(25.0))
        assertEquals("0.8 psi per 100 cfm", u.restrictionText(12.34))
        assertEquals("9,321 mi", u.distanceFromKmText(15_000.0, grouped = true))
        assertEquals("599°F", u.temperatureNoSpace(315.0))
    }

    @Test fun metricLeavesValuesAlone() {
        val u = Units.METRIC
        assertEquals(100.0, u.pressure(100.0), 0.0)
        assertEquals(100.0, u.temperature(100.0), 0.0)
        assertEquals("100 kPa", u.pressureText(100.0))
        assertEquals("100 km", u.distanceText(100_000.0))
    }

    @Test fun textRewritesMetricUnitsInRuleDescriptions() {
        val u = Units.IMPERIAL
        assertEquals("Coolant at 221–232 °F.", u.text("Coolant at 105-111 °C."))
        assertEquals("Drive above 19 mph.", u.text("Drive above 30 km/h."))
        assertEquals("Oil pressure under 3.6 psi.", u.text("Oil pressure under 24.5 kPa."))
        assertEquals("It rose 9 °F in 60 s, now 212 °F.", u.text("It rose 5 °C in 60 s, now 100 °C."))
        assertEquals("13.8–18.1 psi", u.text("0.95-1.25 bar"))
        assertEquals("3.3 lb/min at 20.1 cfm", u.text("25 g/s at 9.5 l/s"))
        assertEquals("93–249 mi apart", u.text("150-400 km apart"))
    }

    @Test fun textKeepsMetricAndNeutralUnits() {
        val s = "Coolant at 105 °C, 3000 rpm, 13-21 deg, 1.0-2.0 V"
        assertEquals(s, Units.METRIC.text(s))
        assertEquals("Coolant at 221 °F, 3000 rpm, 13-21 deg, 1.0-2.0 V", Units.IMPERIAL.text(s))
    }
}
