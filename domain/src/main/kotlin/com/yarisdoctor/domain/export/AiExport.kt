package com.yarisdoctor.domain.export

import com.yarisdoctor.domain.drive.MILE_M
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the AI-readable Markdown export: one self-describing file with a legend, a summary and every table as CSV.
 *
 * The database side only supplies rows through [Source], so the format (and its escaping, times and summary maths) is
 * plain Kotlin and unit-tested. Large tables are streamed instead of being held in memory.
 */
object AiExport {
    const val FORMAT = "yaris-doctor-ai-export"
    const val VERSION = 2

    /** Everything [write] needs before it starts; the counts decide whether a table is written at all. */
    data class Meta(
        val generatedAt: Long,
        val appVersion: String,
        val zone: ZoneId,
        val model2015: Boolean,
        val schemaVersion: Int,
        val firstReading: Long?,
        val lastReading: Long?,
        val samples: Int,
        val peerSamples: Int,
        val trips: Int,
        val regens: Int,
        val alerts: Int,
        val frames: Int,
        val linkRows: Int,
        /** The first cluster odometer read: rows before it carry an estimated odometer. Null before the first. */
        val odoRealFrom: Long? = null,
    )

    /** [odoStart] / [odoEnd]: the car's odometer (metres) at the trip's first and last reading. */
    data class TripRow(
        val index: Int, val start: Long, val end: Long, val metres: Double,
        val sootStart: Int?, val sootEnd: Int?, val regens: Int, val warnings: Int,
        val odoStart: Double? = null, val odoEnd: Double? = null,
    )

    data class RegenRow(
        val index: Int, val start: Long, val end: Long?, val gapM: Double?,
        val sootStart: Int?, val sootEnd: Int?, val floor: Int?, val peakTemp: Int?,
        val odoStart: Double? = null, val odoEnd: Double? = null,
    )

    data class AlertRow(val key: String, val level: String, val text: String, val start: Long, val end: Long?, val odoStart: Double? = null, val odoEnd: Double? = null)

    /** One cluster odometer read: [miles] as the dash showed it, [odoM] the logged odometer after it (metres). */
    data class OdoRow(val t: Long, val miles: Long, val odoM: Double)

    data class SampleRow(val t: Long, val soot: Int?, val temp: Int?, val regen: Boolean, val odoM: Double?)

    /** [min]/[max]/[mean] are already formatted numbers without the unit, so the column stays sortable. */
    data class SignalRow(
        val trip: Int, val id: String, val name: String, val unit: String,
        val readings: Int, val min: String, val max: String, val mean: String,
    )

    data class LatestRow(val t: Long, val id: String, val name: String, val category: String, val value: String)

    data class LinkRow(val session: Long, val cmd: String, val n: Int, val ok: Int, val firstMs: Int, val tailMs: Int, val totalMs: Int)

    /** [a]/[b]/[c]: the 12 record bytes as three big-endian 32-bit counters, or null when the payload is malformed. */
    data class AshRow(val t: Long, val hex: String, val a: Long?, val b: Long?, val c: Long?, val checksumOk: Boolean)

    data class CalRow(val t: Long, val hex: String, val text: String)

    /** One odometer probe answer: [what] was asked (`ODO:<header>:<request>`, `ODO:611`, `ODO:ALL`, `ODO:KM`), [raw] the reply. */
    data class ProbeRow(val t: Long, val what: String, val raw: String)

    /** How [write] gets its rows; [Db] implements it with cursors, tests with lists. */
    interface Source {
        fun trips(emit: (TripRow) -> Unit)
        fun regens(emit: (RegenRow) -> Unit)
        fun alerts(emit: (AlertRow) -> Unit)
        fun samples(emit: (SampleRow) -> Unit)
        fun peerSamples(emit: (SampleRow) -> Unit)
        fun signals(trips: List<TripRow>, emit: (SignalRow) -> Unit)
        fun latest(emit: (LatestRow) -> Unit)
        fun links(emit: (LinkRow) -> Unit)
        fun ash(emit: (AshRow) -> Unit)
        fun calIds(emit: (CalRow) -> Unit)
        fun odoProbes(emit: (ProbeRow) -> Unit)
        fun odometerReads(emit: (OdoRow) -> Unit) {}
    }

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    private val DAY = 86_400_000.0

    fun write(out: Appendable, meta: Meta, source: Source) {
        val trips = ArrayList<TripRow>().apply { source.trips { add(it) } }
        val regens = ArrayList<RegenRow>().apply { source.regens { add(it) } }
        val alerts = ArrayList<AlertRow>().apply { source.alerts { add(it) } }
        val signals = ArrayList<SignalRow>().apply { source.signals(trips) { add(it) } }
        val latest = ArrayList<LatestRow>().apply { source.latest { add(it) } }
        val links = ArrayList<LinkRow>().apply { source.links { add(it) } }
        val ash = ArrayList<AshRow>().apply { source.ash { add(it) } }
        val cals = ArrayList<CalRow>().apply { source.calIds { add(it) } }
        val probes = ArrayList<ProbeRow>().apply { source.odoProbes { add(it) } }

        fun time(t: Long?) = t?.let { Instant.ofEpochMilli(it).atZone(meta.zone).format(TIME) } ?: ""
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun km(metres: Double?, decimals: Int = 2) = num(metres?.div(1000.0), decimals)
        fun mi(metres: Double?, decimals: Int = 2) = num(metres?.div(MILE_M), decimals)
        /** Where a row's odometer comes from: the car's cluster, or an estimate from before its first read. */
        fun source(t: Long?, odoM: Double?) = when {
            odoM == null || t == null -> ""
            meta.odoRealFrom != null && t >= meta.odoRealFrom -> "car"
            else -> "estimate"
        }
        fun minutes(from: Long, to: Long?) = if (to == null) "" else num((to - from) / 60_000.0, 1)

        out.line("---")
        out.line("format: $FORMAT")
        out.line("format_version: $VERSION")
        out.line("generated: ${q(time(meta.generatedAt))}")
        out.line("app_version: ${q(meta.appVersion)}")
        out.line("car: ${q("Toyota Yaris 1.4 D-4D (1ND-TV)")}")
        out.line("model_year: ${q(if (meta.model2015) "2015 or later" else "2011-2014")}")
        out.line("soot_scale: ${q(if (meta.model2015) "2015+ (a regen is needed at 118%, the fault level is 174%)" else "2011-2014 (a regen is needed at 100%, the fault level is 150%)")}")
        out.line("database_schema: ${meta.schemaVersion}")
        out.line("timezone: ${q(meta.zone.id)}")
        out.line("distance_unit: km")
        out.line("odometer: ${q(if (meta.odoRealFrom != null) "the car's, read from the instrument cluster since ${time(meta.odoRealFrom)}; earlier rows are estimates" else "not read from the car yet: estimates")}")
        out.line("rows:")
        out.line("  readings_this_phone: ${meta.samples}")
        out.line("  readings_other_phones: ${meta.peerSamples}")
        out.line("  trips: ${meta.trips}")
        out.line("  regenerations: ${meta.regens}")
        out.line("  warnings: ${meta.alerts}")
        out.line("  engine_frames: ${meta.frames}")
        out.line("  link_rows: ${meta.linkRows}")
        out.line("---")
        out.line()
        out.line("# Yaris Doctor export")
        out.line()
        out.line("One file with the whole Yaris Doctor log, written for an AI to read: a legend, a summary, and every table as CSV.")
        out.line()
        out.line("## How to read this file")
        out.line()
        out.line("- **Times** are ISO 8601 local time with the UTC offset (`2026-10-25T14:03:02+01:00`). Rows are oldest first unless a section says otherwise. The zone is in the front matter.")
        out.line("- **Soot** (`soot_pct`) is the ECU's PM Accumulation Ratio in percent. The regen-needed and fault levels depend on the model year (front matter).")
        out.line("- **Exhaust temperature** (`exhaust_c`) is the gas temperature after the DPF (bank 1, sensor 2), in °C.")
        out.line("- **`regen`** is 1 while the ECU's DPF regeneration flag is on, otherwise 0.")
        out.line("- **`odometer_km` / `odometer_mi`** are the car's dash odometer at that moment. The app reads it from the instrument cluster (whole miles, 0x611) at the start of each drive and every 5 minutes, and adds the ECU's vehicle speed in between, so a row is within a mile of the dash and usually much closer. `odometer_source` says which: `car` from the first cluster read on, `estimate` before it (the phone's old GPS distance, counted back from that first read, so it misses driving done without the phone).")
        out.line("- **Blank** means not recorded or not read; the summary uses `n/a`.")
        out.line("- **Trips** are runs of readings with no gap over 2 minutes. **Regenerations** are the periods the flag was on, merged across phones when they overlap.")
        out.line("- **Engine signals** are the ECU values decoded from the raw CAN frames and summarised per trip. The full-resolution frames stay in the app's database (and on the hub); this export keeps the summary.")
        out.line()

        val distanceKm = trips.sumOf { it.metres } / 1000.0
        val odoNow = trips.lastOrNull()?.odoEnd
        val gaps = regens.mapNotNull { it.gapM }.filter { it > 0 }
        val meanGap = gaps.takeIf { it.isNotEmpty() }?.average()?.div(1000.0)
        val days = if (meta.firstReading != null && meta.lastReading != null) (meta.lastReading - meta.firstReading) / DAY else null
        val warnings = alerts.count { it.level == "warning" }
        val critical = alerts.count { it.level == "critical" }

        out.line("## At a glance")
        out.line()
        out.line("| Metric | Value |")
        out.line("| --- | --- |")
        out.line("| Readings | ${meta.samples} this phone, ${meta.peerSamples} from other phones |")
        out.line("| Covered | ${time(meta.firstReading).ifEmpty { "n/a" }} to ${time(meta.lastReading).ifEmpty { "n/a" }}${if (days != null) " (${num(days, 1)} days)" else ""} |")
        out.line("| Trips (this phone) | ${meta.trips} |")
        out.line("| Distance, sum of trips | ${num(distanceKm, 1)} km |")
        out.line("| Odometer at the last reading | ${odoNow?.let { "${num(it / MILE_M, 1)} mi (${num(it / 1000, 1)} km, ${source(trips.last().end, it)})" } ?: "n/a"} |")
        out.line("| Regenerations (all phones, merged) | ${regens.size} |")
        out.line("| Mean distance between regens | ${meanGap?.let { "${num(it, 1)} km" } ?: "n/a"} |")
        out.line("| Warnings | ${alerts.size} (${critical} critical, ${warnings} warning) |")
        out.line("| Engine frames stored | ${meta.frames} |")
        out.line("| Engine signals tracked | ${latest.size} |")
        out.line()

        out.line("## Trips")
        out.line()
        out.line("One row per drive of this phone, oldest first. `regens` and `warnings` count those that started inside the trip.")
        out.line()
        if (trips.isEmpty()) {
            out.line("_No trips logged._")
        } else {
            out.line("```csv")
            out.line("trip,start,end,duration_min,distance_km,soot_start_pct,soot_end_pct,regens,warnings,odometer_start_mi,odometer_end_mi,odometer_source")
            for (t in trips) out.line(csv(t.index, time(t.start), time(t.end), minutes(t.start, t.end), km(t.metres), t.sootStart, t.sootEnd, t.regens, t.warnings,
                mi(t.odoStart, 1), mi(t.odoEnd, 1), source(t.start, t.odoStart)))
            out.line("```")
        }
        out.line()

        out.line("## Regenerations")
        out.line()
        out.line("Oldest first. `distance_since_previous_km` is the car distance from the previous regen's end to this one's start. `soot_floor_pct` is the reading 5-10 min after the end, the ECU's settled figure; `peak_exhaust_c` is the hottest reading during the burn. `in progress` means the regen was still open when this file was written.")
        out.line()
        if (regens.isEmpty()) {
            out.line("_No regenerations logged._")
        } else {
            out.line("```csv")
            out.line("regen,start,end,duration_min,distance_since_previous_km,soot_start_pct,soot_end_pct,soot_floor_pct,peak_exhaust_c,status,odometer_start_mi,odometer_end_mi,odometer_source")
            for (r in regens) out.line(csv(
                r.index, time(r.start), time(r.end), minutes(r.start, r.end), km(r.gapM), r.sootStart, r.sootEnd, r.floor, r.peakTemp,
                if (r.end == null) "in progress" else "finished", mi(r.odoStart, 1), mi(r.odoEnd, 1), source(r.start, r.odoStart),
            ))
            out.line("```")
        }
        out.line()

        out.line("## Warnings")
        out.line()
        out.line("Health checks that fired, oldest first. `level` is `warning` or `critical`. A blank `end` means it was still active at export time.")
        out.line()
        if (alerts.isEmpty()) {
            out.line("_No warnings logged._")
        } else {
            out.line("```csv")
            out.line("key,level,start,end,text,odometer_start_mi,odometer_end_mi,odometer_source")
            for (a in alerts) out.line(csv(a.key, a.level, time(a.start), time(a.end), a.text, mi(a.odoStart, 1), mi(a.odoEnd, 1), source(a.start, a.odoStart)))
            out.line("```")
        }
        out.line()

        out.line("## Readings (this phone)")
        out.line()
        out.line("One row per sample: every 5 s normally, 1 s while a regen runs. Old frames may be thinned by the app, but readings are kept as logged.")
        out.line()
        if (meta.samples == 0) {
            out.line("_No readings logged on this phone._")
        } else {
            out.line("```csv")
            out.line("time,soot_pct,exhaust_c,regen,odometer_km,odometer_mi,odometer_source")
            source.samples { s -> out.line(csv(time(s.t), s.soot, s.temp, if (s.regen) 1 else 0, km(s.odoM, 3), mi(s.odoM, 3), source(s.t, s.odoM))) }
            out.line("```")
        }
        out.line()

        out.line("## Readings from other phones")
        out.line()
        out.line("The same format as above, downloaded from the home hub. These are drives another phone logged on the same car; they can overlap this phone's readings.")
        out.line()
        if (meta.peerSamples == 0) {
            out.line("_No readings from other phones._")
        } else {
            out.line("```csv")
            out.line("time,soot_pct,exhaust_c,regen,odometer_km,odometer_mi")
            source.peerSamples { s -> out.line(csv(time(s.t), s.soot, s.temp, if (s.regen) 1 else 0, km(s.odoM, 3), mi(s.odoM, 3))) }
            out.line("```")
        }
        out.line()

        out.line("## Engine signals: latest reading")
        out.line()
        out.line("The newest decoded value of every signal this ECU reports. The `id` is the request local ID and bit offset from the PID table.")
        out.line()
        if (latest.isEmpty()) {
            out.line("_No engine frames stored._")
        } else {
            out.line("```csv")
            out.line("signal,id,category,value,last_read")
            for (l in latest) out.line(csv(l.name, l.id, l.category, l.value, time(l.t)))
            out.line("```")
        }
        out.line()

        out.line("## Engine signals: per trip")
        out.line()
        out.line("Min, max and mean of each signal within a trip, decoded from the raw frames. `readings` is how many samples went into the row. Enum/switch signals are left out of this table (their latest state is above).")
        out.line()
        if (signals.isEmpty()) {
            out.line("_No engine signals for these trips._")
        } else {
            out.line("```csv")
            out.line("trip,signal,id,unit,readings,min,max,mean")
            for (s in signals) out.line(csv(s.trip, s.name, s.id, s.unit, s.readings, s.min, s.max, s.mean))
            out.line("```")
        }
        out.line()

        out.line("## Dongle link timings")
        out.line()
        out.line("The last logged session only. Times are milliseconds from sending a request to the first byte, the tail to the ELM's `>` prompt, and the total.")
        out.line()
        if (links.isEmpty()) {
            out.line("_No link timings logged._")
        } else {
            out.line("```csv")
            out.line("session_start,command,requests,answered,first_byte_ms,tail_ms,total_ms")
            for (l in links) out.line(csv(time(l.session), l.cmd, l.n, l.ok, l.firstMs, l.tailMs, l.totalMs))
            out.line("```")
        }
        out.line()

        out.line("## DPF ash record")
        out.line()
        out.line("The ECU's DPF deterioration record (`A9 18`), stored raw because its format is undocumented. `a`, `b` and `c` are the 12 payload bytes read as three big-endian 32-bit counters; the meanings below are inferred from the owner's car. `checksum_ok` is the XOR check over those 12 bytes. Only reads whose value changed are listed, newest last.")
        out.line()
        if (ash.isEmpty()) {
            out.line("_No ash record reads logged._")
        } else {
            out.line("```csv")
            out.line("time,raw_hex,a,b,c,checksum_ok")
            for (r in ash) out.line(csv(time(r.t), r.hex, r.a, r.b, r.c, if (r.checksumOk) 1 else 0))
            out.line("```")
            out.line()
            out.line("Counters: `a` ~ time with the ECU awake (about 0.26 s ticks); `c` ~ engine running seconds; `b` ~ 20 × `c` (50 ms ticks). None is an ash mass.")
        }
        out.line()

        out.line("## ECU calibration ID")
        out.line()
        out.line("Read once from mode `09 04`; used to look up which tools can read this ECU's flash.")
        out.line()
        if (cals.isEmpty()) {
            out.line("_No calibration ID recorded._")
        } else {
            out.line("```csv")
            out.line("time,raw_hex,text")
            for (c in cals) out.line(csv(time(c.t), c.hex, c.text))
            out.line("```")
        }
        out.line()

        out.line("## Odometer reads")
        out.line()
        out.line("Every time the app read the dash odometer from the instrument cluster: `miles` as the dash showed it (whole miles), `odometer_mi` the logged odometer just after, which stays inside that mile. Readings between two reads add the ECU's vehicle speed.")
        out.line()
        val odoReads = ArrayList<OdoRow>().apply { source.odometerReads { add(it) } }
        if (odoReads.isEmpty()) {
            out.line("_The cluster odometer has not been read yet._")
        } else {
            out.line("```csv")
            out.line("time,miles,odometer_mi")
            for (o in odoReads) out.line(csv(time(o.t), o.miles, mi(o.odoM, 3)))
            out.line("```")
        }
        out.line()

        out.line("## Odometer probe")
        out.line()
        out.line("Run from Settings to find where this car exposes its odometer: `ODO:7E0:<request>` engine ECU and `ODO:7C0:<request>` meter replies (blank = no answer), `ODO:611` the distinct CAN lines heard on id 0x611 (headers on: the first 3 characters are the id), `ODO:ALL` per CAN id seen on an unfiltered listen as `id×lines:last line`, and `ODO:KM` the logbook mileage at that moment, to match the bytes against.")
        out.line()
        if (probes.isEmpty()) {
            out.line("_No odometer probe run._")
        } else {
            out.line("```csv")
            out.line("time,what,raw")
            for (p in probes) out.line(csv(time(p.t), p.what, p.raw))
            out.line("```")
        }
        out.line()

        out.line("## Notes and gaps")
        out.line()
        out.line("- **Raw frames:** this export summarises the raw CAN frames per trip. The full-resolution frames stay in the app's database (and on the hub).")
        out.line("- **Other phones:** only their readings are included. Warnings and engine frames are this phone's, because the app does not download those from the hub.")
        out.line("- **Distance:** the car's own odometer from the first cluster read on; before it, the phone's GPS distance moved onto that odometer (an estimate that misses driving without the phone). Other phones' readings use their own estimate until each has read the cluster.")
        out.line("- **Soot scale:** the thresholds in the front matter come from the model-year setting in the app, not from this file.")
        out.line("- This file is read-only: exporting never changes the database.")
    }

    private fun Appendable.line(text: String = "") {
        append(text)
        append('\n')
    }

    private fun num(value: Double?, decimals: Int): String =
        if (value == null || value.isNaN() || value.isInfinite()) "" else String.format(Locale.ROOT, "%.${decimals}f", value)

    /** One CSV field: quoted when it holds a comma, quote or newline, with quotes doubled. */
    private fun csv(vararg fields: Any?): String = fields.joinToString(",") { field ->
        val s = field?.toString().orEmpty()
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
