package com.yarisdoctor

import com.yarisdoctor.domain.car.PIDS
import com.yarisdoctor.domain.car.Pid
import com.yarisdoctor.domain.export.AiExport
import com.yarisdoctor.domain.model.Trip
import java.io.File
import java.io.OutputStreamWriter
import java.time.ZoneId
import java.util.Locale

/**
 * Writes [Db]'s whole log as [AiExport]'s Markdown. The queries stream from SQLite, so an export of months of readings
 * does not have to fit in memory. Only the real database is exported; Debug data never reaches Downloads.
 */
fun Db.exportAi(file: File, appVersion: String, model2015: Boolean, zone: ZoneId = ZoneId.systemDefault()) {
    val meta = aiExportMeta(this, appVersion, model2015, zone)
    file.outputStream().use { bytes ->
        OutputStreamWriter(bytes, Charsets.UTF_8).buffered().use { out ->
            AiExport.write(out, meta, AiExportSource(this))
        }
    }
}

private fun aiExportMeta(db: Db, appVersion: String, model2015: Boolean, zone: ZoneId): AiExport.Meta {
    fun count(table: String) = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
        it.moveToFirst()
        it.getInt(0)
    }
    var first: Long? = null
    var last: Long? = null
    db.readableDatabase.rawQuery(
        "SELECT MIN(t), MAX(t) FROM (SELECT t FROM sample UNION ALL SELECT t FROM peer_sample)", null,
    ).use {
        if (it.moveToFirst() && !it.isNull(0)) {
            first = it.getLong(0)
            last = it.getLong(1)
        }
    }
    return AiExport.Meta(
        generatedAt = System.currentTimeMillis(),
        appVersion = appVersion,
        zone = zone,
        model2015 = model2015,
        schemaVersion = db.readableDatabase.version,
        firstReading = first,
        lastReading = last,
        samples = count("sample"),
        peerSamples = count("peer_sample"),
        trips = db.trips().size,
        regens = db.regens().size,
        alerts = count("alert"),
        frames = count("frame"),
        linkRows = count("link"),
        odoRealFrom = db.odoRealFrom(),
    )
}

private class AiExportSource(private val db: Db) : AiExport.Source {

    /** Numeric signals only: enums are switches, where a min/max/mean says nothing useful. */
    private val numeric = PIDS.filter { it.enum == null }.groupBy { it.lid }

    override fun trips(emit: (AiExport.TripRow) -> Unit) {
        val trips = db.trips().sortedBy { it.start }
        trips.forEachIndexed { i, trip ->
            val (sootStart, sootEnd) = soot(trip)
            emit(AiExport.TripRow(
                index = i + 1, start = trip.start, end = trip.end, metres = trip.metres,
                sootStart = sootStart, sootEnd = sootEnd, regens = trip.regens, warnings = warnings(trip),
                odoStart = db.odoAt(trip.start), odoEnd = db.odoAt(trip.end),
            ))
        }
    }

    override fun regens(emit: (AiExport.RegenRow) -> Unit) {
        db.withSigns(db.regens()).forEachIndexed { i, r ->
            emit(AiExport.RegenRow(i + 1, r.start, r.end, r.gapM, r.sootStart, r.sootEnd, r.floor, r.peakTemp, db.odoAt(r.start), r.end?.let(db::odoAt)))
        }
    }

    override fun alerts(emit: (AiExport.AlertRow) -> Unit) = db.readableDatabase.rawQuery(
        "SELECT key, level, text, start_t, end_t, odo_start, odo_end FROM alert ORDER BY start_t, key", null,
    ).use { c ->
        while (c.moveToNext()) emit(AiExport.AlertRow(
            c.getString(0), if (c.getInt(1) >= 2) "critical" else "warning", c.getString(2), c.getLong(3),
            if (c.isNull(4)) null else c.getLong(4), if (c.isNull(5)) null else c.getDouble(5), if (c.isNull(6)) null else c.getDouble(6),
        ))
    }

    override fun odometerReads(emit: (AiExport.OdoRow) -> Unit) = db.readableDatabase.rawQuery("SELECT t, miles, odo_m FROM odometer ORDER BY t", null).use { c ->
        while (c.moveToNext()) emit(AiExport.OdoRow(c.getLong(0), c.getLong(1), c.getDouble(2)))
    }

    override fun samples(emit: (AiExport.SampleRow) -> Unit) = samples("sample", emit)

    override fun peerSamples(emit: (AiExport.SampleRow) -> Unit) = samples("peer_sample", emit)

    private fun samples(table: String, emit: (AiExport.SampleRow) -> Unit) = db.readableDatabase.rawQuery(
        "SELECT t, soot, temp, regen, odo_m FROM $table ORDER BY t", null,
    ).use { c ->
        while (c.moveToNext()) emit(AiExport.SampleRow(
            c.getLong(0), c.intOrNull(1), c.intOrNull(2), c.getInt(3) == 1, if (c.isNull(4)) null else c.getDouble(4),
        ))
    }

    override fun signals(trips: List<AiExport.TripRow>, emit: (AiExport.SignalRow) -> Unit) {
        for (trip in trips) {
            for ((lid, pids) in numeric) {
                val stats = HashMap<String, Stat>(pids.size)
                db.readableDatabase.rawQuery(
                    "SELECT hex FROM frame WHERE lid = ? AND t BETWEEN ? AND ?", arrayOf(lid, trip.start.toString(), trip.end.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val hex = c.getString(0)
                        for (p in pids) p.decode(hex)?.let { stats.getOrPut(p.id) { Stat() }.add(it) }
                    }
                }
                for (p in pids) stats[p.id]?.let { s ->
                    emit(AiExport.SignalRow(
                        trip.index, p.id, p.name, p.unit, s.n,
                        number(s.min, p), number(s.max, p), number(s.mean, p),
                    ))
                }
            }
        }
    }

    override fun latest(emit: (AiExport.LatestRow) -> Unit) {
        val latest = db.latestFrames()
        for (p in PIDS) {
            val (t, hex) = latest[p.lid] ?: continue
            val v = p.decode(hex) ?: continue
            emit(AiExport.LatestRow(t, p.id, p.name, p.category, p.format(v)))
        }
    }

    override fun links(emit: (AiExport.LinkRow) -> Unit) = db.readableDatabase.rawQuery(
        "SELECT t, cmd, n, ok, first_ms, tail_ms, total_ms FROM link WHERE t = (SELECT MAX(t) FROM link) ORDER BY cmd", null,
    ).use { c ->
        while (c.moveToNext()) emit(AiExport.LinkRow(
            c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6),
        ))
    }

    override fun ash(emit: (AiExport.AshRow) -> Unit) {
        // ashHistory is newest first; the file reads oldest first
        for ((t, hex) in db.ashHistory(20).reversed()) emit(ashRow(t, hex))
    }

    override fun calIds(emit: (AiExport.CalRow) -> Unit) = db.readableDatabase.rawQuery(
        "SELECT t, hex FROM frame WHERE lid = '0904' ORDER BY t DESC LIMIT 1", null,
    ).use { c ->
        if (c.moveToFirst()) emit(AiExport.CalRow(c.getLong(0), c.getString(1), calText(c.getString(1))))
    }

    override fun odoProbes(emit: (AiExport.ProbeRow) -> Unit) {
        for ((t, what, raw) in db.odoProbes()) emit(AiExport.ProbeRow(t, what, raw))
    }

    /** This phone's first and last soot within [trip]'s own sample window. */
    private fun soot(trip: Trip): Pair<Int?, Int?> = db.readableDatabase.rawQuery(
        "SELECT (SELECT soot FROM sample WHERE t BETWEEN ? AND ? AND soot IS NOT NULL ORDER BY t LIMIT 1), " +
            "(SELECT soot FROM sample WHERE t BETWEEN ? AND ? AND soot IS NOT NULL ORDER BY t DESC LIMIT 1)",
        arrayOf(trip.start.toString(), trip.end.toString(), trip.start.toString(), trip.end.toString()),
    ).use { if (it.moveToFirst()) it.intOrNull(0) to it.intOrNull(1) else null to null }

    /** Warnings that started in [trip]'s sample window. */
    private fun warnings(trip: Trip): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM alert WHERE start_t BETWEEN ? AND ?", arrayOf(trip.start.toString(), trip.end.toString()),
    ).use { it.moveToFirst(); it.getInt(0) }

    /** `Pid.decimals` without the unit, so a CSV column can be parsed as a number. */
    private fun number(v: Double, p: Pid) = String.format(Locale.ROOT, "%.${p.decimals}f", v)

    private fun ashRow(t: Long, hex: String): AiExport.AshRow {
        val b = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (b.size != 13) return AiExport.AshRow(t, hex, null, null, null, false)
        fun counter(at: Int) = b.subList(at, at + 4).fold(0L) { acc, x -> acc shl 8 or x.toLong() }
        return AiExport.AshRow(t, hex, counter(0), counter(4), counter(8), b.take(12).fold(0) { x, y -> x xor y } == b[12])
    }

    /** `49 04 <count>` is dropped: the payload is a count byte then 16 ASCII bytes per ID. */
    private fun calText(hex: String): String = hex.chunked(2).drop(1).chunked(16).mapNotNull { bytes ->
        val s = bytes.mapNotNull { it.toIntOrNull(16)?.toChar() }.joinToString("")
        s.takeIf { it.all { ch -> ch.code in 32..126 } }
    }.joinToString(" | ")

    private class Stat {
        var n = 0
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        var sum = 0.0
        val mean get() = if (n == 0) 0.0 else sum / n
        fun add(v: Double) {
            n++
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
    }
}

private fun android.database.Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
