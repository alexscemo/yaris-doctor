package com.yarisdoctor.domain.export

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiExportTest {

    private val zone = ZoneId.of("Europe/Rome")
    private val start = Instant.parse("2026-09-01T08:00:00Z").toEpochMilli()

    private fun meta(
        samples: Int = 3, peers: Int = 1, trips: Int = 2, regens: Int = 2, alerts: Int = 1, frames: Int = 40, links: Int = 1,
        first: Long? = start, last: Long? = start + 3_600_000,
    ) = AiExport.Meta(
        generatedAt = start + 3_600_000,
        appVersion = "0.17.0",
        zone = zone,
        model2015 = false,
        schemaVersion = 6,
        firstReading = first,
        lastReading = last,
        samples = samples,
        peerSamples = peers,
        trips = trips,
        regens = regens,
        alerts = alerts,
        frames = frames,
        linkRows = links,
    )

    private class Source(
        private val trips: List<AiExport.TripRow> = emptyList(),
        private val regens: List<AiExport.RegenRow> = emptyList(),
        private val alerts: List<AiExport.AlertRow> = emptyList(),
        private val samples: List<AiExport.SampleRow> = emptyList(),
        private val peers: List<AiExport.SampleRow> = emptyList(),
        private val signals: List<AiExport.SignalRow> = emptyList(),
        private val latest: List<AiExport.LatestRow> = emptyList(),
        private val links: List<AiExport.LinkRow> = emptyList(),
        private val ash: List<AiExport.AshRow> = emptyList(),
        private val cals: List<AiExport.CalRow> = emptyList(),
        private val probes: List<AiExport.ProbeRow> = emptyList(),
        private val odoReads: List<AiExport.OdoRow> = emptyList(),
    ) : AiExport.Source {
        override fun odometerReads(emit: (AiExport.OdoRow) -> Unit) = odoReads.forEach(emit)
        override fun trips(emit: (AiExport.TripRow) -> Unit) = trips.forEach(emit)
        override fun regens(emit: (AiExport.RegenRow) -> Unit) = regens.forEach(emit)
        override fun alerts(emit: (AiExport.AlertRow) -> Unit) = alerts.forEach(emit)
        override fun samples(emit: (AiExport.SampleRow) -> Unit) = samples.forEach(emit)
        override fun peerSamples(emit: (AiExport.SampleRow) -> Unit) = peers.forEach(emit)
        override fun signals(trips: List<AiExport.TripRow>, emit: (AiExport.SignalRow) -> Unit) = signals.forEach(emit)
        override fun latest(emit: (AiExport.LatestRow) -> Unit) = latest.forEach(emit)
        override fun links(emit: (AiExport.LinkRow) -> Unit) = links.forEach(emit)
        override fun ash(emit: (AiExport.AshRow) -> Unit) = ash.forEach(emit)
        override fun calIds(emit: (AiExport.CalRow) -> Unit) = cals.forEach(emit)
        override fun odoProbes(emit: (AiExport.ProbeRow) -> Unit) = probes.forEach(emit)
    }

    private fun fullSource() = Source(
        trips = listOf(
            AiExport.TripRow(1, start, start + 600_000, 1234.0, 40, 45, 1, 0),
            AiExport.TripRow(2, start + 1_800_000, start + 2_100_000, 5000.0, null, 50, 0, 1),
        ),
        regens = listOf(
            AiExport.RegenRow(1, start + 120_000, start + 540_000, null, 88, 10, 12, 640),
            AiExport.RegenRow(2, start + 2_400_000, null, 12_000.0, 70, null, null, 610),
        ),
        alerts = listOf(
            AiExport.AlertRow("boost_deviation", "warning", "Boost, \"high\"\nnow", start + 100_000, start + 200_000),
            AiExport.AlertRow("coolant_hot", "critical", "Coolant hot", start + 300_000, null),
        ),
        samples = listOf(
            AiExport.SampleRow(start, 40, 200, false, 0.0),
            AiExport.SampleRow(start + 5000, null, 610, true, 123_456.0),
            AiExport.SampleRow(start + 10_000, 41, 205, false, null),
        ),
        peers = listOf(AiExport.SampleRow(start + 30_000, 39, 210, false, 4000.0)),
        signals = listOf(AiExport.SignalRow(1, "0C.0", "Engine Speed", "rpm", 3, "800", "2200", "1500")),
        latest = listOf(AiExport.LatestRow(start + 10_000, "0C.0", "Engine Speed", "Engine", "1500 rpm")),
        links = listOf(AiExport.LinkRow(start, "210C", 10, 9, 40, 12, 55)),
        ash = listOf(
            AiExport.AshRow(start, "00255D0200C00724000999F5FC", 2_448_642, 0, 629_237, true),
            AiExport.AshRow(start + 60_000, "BAD", null, null, null, false),
        ),
        cals = listOf(AiExport.CalRow(start, "0130303030303030303030303030303030", "00000000000000")),
        probes = listOf(AiExport.ProbeRow(start, "ODO:611", "6110000A1B2C 6110000A1B2D")),
    )

    private fun render(meta: AiExport.Meta = meta(), source: AiExport.Source = fullSource()): String =
        StringBuilder().also { AiExport.write(it, meta, source) }.toString()

    @Test fun `file is a self-describing markdown document`() {
        val md = render()
        assertTrue(md.startsWith("---\nformat: yaris-doctor-ai-export\n"))
        assertTrue(md.contains("format_version: 2\n"))
        assertTrue(md.contains("timezone: \"Europe/Rome\"\n"))
        assertTrue(md.contains("model_year: \"2011-2014\"\n"))
        assertTrue(md.contains("  readings_this_phone: 3\n"))
        assertTrue(md.contains("# Yaris Doctor export\n"))
        assertTrue(md.contains("## How to read this file"))
        assertTrue(md.contains("## At a glance"))
        assertTrue(md.contains("| Regenerations (all phones, merged) | 2 |"))
        assertTrue(md.contains("| Warnings | 2 (1 critical, 1 warning) |"))
    }

    @Test fun `times carry the zone offset and CSV uses root decimals`() {
        val md = render()
        assertTrue(md.contains("2026-09-01T10:00:00+02:00"))
        assertTrue(md.contains("1.23")) // 1234 m as 1.23 km, not 1,23
        assertFalse(md.contains("1,23 km"))
    }

    @Test fun `CSV quotes commas, quotes and newlines`() {
        val md = render()
        assertTrue(md.contains("\"Boost, \"\"high\"\"\nnow\""))
        // A blank end (an open warning) leaves an empty field, not the word null
        assertTrue(md.contains("coolant_hot,critical,2026-09-01T10:05:00+02:00,,Coolant hot"))
    }

    @Test fun `null readings and an open regen stay blank`() {
        val md = render()
        assertTrue(md.contains("2026-09-01T10:00:05+02:00,,610,1,123.456"))
        assertTrue(md.contains("2026-09-01T10:00:10+02:00,41,205,0,"))
        assertTrue(md.contains("2,2026-09-01T10:40:00+02:00,,,12.00,70,,,610,in progress"))
    }

    @Test fun `every section is present and empty data is named`() {
        val md = render(meta(samples = 0, peers = 0, trips = 0, regens = 0, alerts = 0, frames = 0, links = 0), Source())
        assertTrue(md.contains("_No trips logged._"))
        assertTrue(md.contains("_No regenerations logged._"))
        assertTrue(md.contains("_No warnings logged._"))
        assertTrue(md.contains("_No readings logged on this phone._"))
        assertTrue(md.contains("_No readings from other phones._"))
        assertTrue(md.contains("_No engine frames stored._"))
        assertTrue(md.contains("_No engine signals for these trips._"))
        assertTrue(md.contains("_No link timings logged._"))
        assertTrue(md.contains("_No ash record reads logged._"))
        assertTrue(md.contains("_No calibration ID recorded._"))
        assertTrue(md.contains("_No odometer probe run._"))
    }

    @Test fun `rows carry the odometer and say whether it is the car's or an estimate`() {
        val car = 89_159.5 * 1609.344
        val src = Source(
            trips = listOf(AiExport.TripRow(1, start, start + 600_000, 1234.0, 40, 45, 1, 0, car - 1234.0, car)),
            alerts = listOf(AiExport.AlertRow("boost_deviation", "warning", "Boost", start + 100_000, start + 200_000, car - 1000.0, car - 500.0)),
            samples = listOf(AiExport.SampleRow(start, 40, 200, false, car - 1234.0), AiExport.SampleRow(start + 600_000, 41, 205, false, car)),
            odoReads = listOf(AiExport.OdoRow(start + 600_000, 89_159, car)),
        )
        val md = render(meta().copy(odoRealFrom = start + 600_000), src)
        assertTrue(md.contains("odometer: \"the car's, read from the instrument cluster since 2026-09-01T10:10:00+02:00; earlier rows are estimates\""))
        assertTrue(md.contains("2026-09-01T10:00:00+02:00,40,200,0,143487.072,89158.733,estimate\n")) // before the first read
        assertTrue(md.contains("2026-09-01T10:10:00+02:00,41,205,0,143488.306,89159.500,car\n")) // 89,159 mi and half a mile
        assertTrue(md.contains(",Boost,89158.9,89159.2,estimate\n"))
        assertTrue(md.contains("2026-09-01T10:10:00+02:00,89159,89159.500\n"))
        assertTrue(md.contains("| Odometer at the last reading | 89159.5 mi (143488.3 km, car) |"))
    }

    @Test fun `odometer probe answers are listed raw`() =
        assertTrue(render().contains("2026-09-01T10:00:00+02:00,ODO:611,6110000A1B2C 6110000A1B2D\n"))

    @Test fun `summary survives no readings`() {
        val md = render(meta(samples = 0, peers = 0, trips = 0, regens = 0, alerts = 0, frames = 0, links = 0, first = null, last = null), Source())
        assertTrue(md.contains("| Covered | n/a to n/a |"))
        assertTrue(md.contains("| Mean distance between regens | n/a |"))
        assertEquals(1, Regex("""\| Covered \|""").findAll(md).count())
    }
}
