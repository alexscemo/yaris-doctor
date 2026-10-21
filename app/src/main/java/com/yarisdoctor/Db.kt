package com.yarisdoctor

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** [name] null = in-memory database (Debug mode), gone when closed. */
class Db private constructor(ctx: Context, name: String?) : SQLiteOpenHelper(ctx, name, null, 6) {

    /** A [Finding] while it lasted; [end] null = still active. */
    data class Alert(val key: String, val level: Int, val text: String, val start: Long, val end: Long?)

    data class Sample(val t: Long, val soot: Int?, val temp: Int?, val regen: Boolean)

    /**
     * [gapM]: metres driven between the previous regen's end and this one's start; null for the first logged regen.
     * [floor] and [peakTemp] are only filled by [withSigns].
     */
    data class Regen(val start: Long, val end: Long?, val gapM: Double?, val sootStart: Int?, val sootEnd: Int?, val floor: Int? = null, val peakTemp: Int? = null)

    override fun onCreate(db: SQLiteDatabase) {
        // odo_m: GPS distance accumulated by this app (metres), our stand-in for the odometer
        db.execSQL("CREATE TABLE sample(t INTEGER, soot INTEGER, temp INTEGER, regen INTEGER, odo_m REAL)")
        db.execSQL("CREATE TABLE regen(id INTEGER PRIMARY KEY, start_t INTEGER, end_t INTEGER, odo_start REAL, odo_end REAL, soot_start INTEGER, soot_end INTEGER)")
        db.execSQL(SAMPLE_T_INDEX)
        createPeers(db)
        createFrames(db)
        createAlerts(db)
        createLink(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v2: time index for the charts' range queries
        if (old < 2) db.execSQL(SAMPLE_T_INDEX)
        // v3: other phones' logs, downloaded from the hub
        if (old < 3) createPeers(db)
        // v4: raw ECU replies for the full live-data table
        if (old < 4) createFrames(db)
        // v5: health check alerts
        if (old < 5) createAlerts(db)
        // v6: dongle request timings per session
        if (old < 6) createLink(db)
    }

    /** One row per command per session ([t] = session start): count, answered, and average ms (see [Elm.Timing]). */
    private fun createLink(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE link(t INTEGER, cmd TEXT, n INTEGER, ok INTEGER, first_ms INTEGER, tail_ms INTEGER, total_ms INTEGER)")

    fun linkStats(t: Long, timings: Map<String, Elm.Timing>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((cmd, x) in timings) if (x.n > 0) db.insert("link", null, ContentValues().apply {
                put("t", t); put("cmd", cmd); put("n", x.n); put("ok", x.ok)
                put("first_ms", x.firstMs / x.n); put("tail_ms", x.tailMs / x.n); put("total_ms", x.totalMs / x.n)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Average ms per request over the newest logged session, or null before the first one. */
    fun linkAverage(): Long? = readableDatabase.rawQuery(
        "SELECT SUM(total_ms * n) / SUM(n) FROM link WHERE t = (SELECT MAX(t) FROM link)", null,
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    private fun createAlerts(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE alert(id INTEGER PRIMARY KEY, key TEXT, level INTEGER, text TEXT, start_t INTEGER, end_t INTEGER)")

    /**
     * Makes the open alert rows match [active]: new keys open a row, keys no longer active get [t] as their end, open
     * ones take the latest text (the numbers in it change). Returns the newly opened findings, to notify.
     */
    fun syncAlerts(t: Long, active: List<Finding>): List<Finding> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = HashMap<String, Long>()
            val texts = HashMap<String, String>()
            db.rawQuery("SELECT id, key, text FROM alert WHERE end_t IS NULL", null).use {
                while (it.moveToNext()) { ids[it.getString(1)] = it.getLong(0); texts[it.getString(1)] = it.getString(2) }
            }
            val d = diffAlerts(texts, active)
            for (key in d.close) db.execSQL("UPDATE alert SET end_t = ? WHERE id = ?", arrayOf(t, ids.getValue(key)))
            for (f in d.update) db.execSQL("UPDATE alert SET text = ?, level = ? WHERE id = ?", arrayOf(f.text, f.level, ids.getValue(f.key)))
            for (f in d.open) db.insert("alert", null, ContentValues().apply {
                put("key", f.key); put("level", f.level); put("text", f.text); put("start_t", t)
            })
            db.setTransactionSuccessful()
            return d.open
        } finally {
            db.endTransaction()
        }
    }

    /** Open alerts, then those that ended after [since], newest first, one row per key. */
    fun alerts(since: Long): List<Alert> =
        readableDatabase.rawQuery(
            "SELECT key, level, text, start_t, end_t FROM alert WHERE end_t IS NULL OR end_t >= ? ORDER BY end_t IS NOT NULL, level DESC, start_t DESC",
            arrayOf(since.toString()),
        ).use {
            val out = LinkedHashMap<String, Alert>()
            while (it.moveToNext()) out.putIfAbsent(it.getString(0), Alert(it.getString(0), it.getInt(1), it.getString(2), it.getLong(3), if (it.isNull(4)) null else it.getLong(4)))
            out.values.toList()
        }

    /** One row per answered request: [lid] ("DD") and the payload after `61 <lid>`, decoded on read with [PIDS]. */
    private fun createFrames(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE frame(t INTEGER, lid TEXT, hex TEXT)")
        db.execSQL("CREATE INDEX frame_lid_t ON frame(lid, t)")
        db.execSQL("CREATE INDEX frame_t ON frame(t)")
    }

    /**
     * [replies]: request code → cleaned reply, as Poller.cycle returns them; codes outside the table aren't stored, except the
     * ash record, kept under its whole code ("A918") so it can't clash with a `21` lid.
     */
    fun frames(t: Long, replies: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((code, reply) in replies) {
                val lid = when (code) {
                    in PID_LENGTHS -> code.substring(2)
                    Poller.ASH_RECORD -> code
                    else -> continue
                }
                db.insert("frame", null, ContentValues().apply { put("t", t); put("lid", lid); put("hex", reply.substring(4)) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Newest payload per lid, as lid → (t, hex). One indexed lookup per lid: a GROUP BY would scan every frame. */
    fun latestFrames(): Map<String, Pair<Long, String>> = PID_IDLE.keys.mapNotNull { lid ->
        readableDatabase.rawQuery("SELECT t, hex FROM frame WHERE lid = ? ORDER BY t DESC LIMIT 1", arrayOf(lid)).use {
            if (it.moveToFirst()) lid to (it.getLong(0) to it.getString(1)) else null
        }
    }.toMap()

    /** Newest ash record read (13 bytes after `E9 18`) and when, or null if this ECU never answered it. */
    fun ashRecord(): Pair<Long, String>? =
        readableDatabase.rawQuery("SELECT t, hex FROM frame WHERE lid = ? ORDER BY t DESC LIMIT 1", arrayOf(Poller.ASH_RECORD)).use {
            if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
        }

    /** The ash record's readings where its value changed (the first one included), newest first. Read every 5 min, so few rows. */
    fun ashHistory(limit: Int = 10): List<Pair<Long, String>> = readableDatabase.rawQuery(
        "SELECT t, hex FROM (SELECT t, hex, LAG(hex) OVER (ORDER BY t) AS prev FROM frame WHERE lid = ?) WHERE prev IS NULL OR prev != hex ORDER BY t DESC LIMIT ?",
        arrayOf(Poller.ASH_RECORD, limit.toString()),
    ).use {
        val out = ArrayList<Pair<Long, String>>()
        while (it.moveToNext()) out += it.getLong(0) to it.getString(1)
        out
    }

    /**
     * [regens] with each one's peak DPF temperature (B1S2, the samples' temp) and, once finished, its soot floor: the first
     * reading 5-10 min after it ended, the ECU's real post-regen figure rather than the ~10 % at the end of the burn. Null
     * where no phone was connected then. A few indexed queries per regen, so pass only the regens you show or check.
     */
    fun withSigns(regens: List<Regen>): List<Regen> = regens.map { r ->
        val end = r.end ?: Long.MAX_VALUE
        val peak = listOf("sample", "peer_sample").mapNotNull { tbl ->
            readableDatabase.rawQuery("SELECT MAX(temp) FROM $tbl WHERE t BETWEEN ? AND ?", arrayOf(r.start.toString(), end.toString())).use { it.moveToFirst(); it.intOrNull(0) }
        }.maxOrNull()
        val floor = r.end?.let { e ->
            listOf("sample", "peer_sample").mapNotNull { tbl ->
                readableDatabase.rawQuery(
                    "SELECT t, soot FROM $tbl WHERE t BETWEEN ? AND ? AND soot IS NOT NULL ORDER BY t LIMIT 1",
                    arrayOf((e + FLOOR_AFTER_MS).toString(), (e + 2 * FLOOR_AFTER_MS).toString()),
                ).use { if (it.moveToFirst()) it.getLong(0) to it.getInt(1) else null }
            }.minByOrNull { it.first }?.second
        }
        r.copy(floor = floor, peakTemp = peak)
    }

    /**
     * Start of the last trip: the newest engine-speed frame (read every cycle) with none in the [TRIP_GAP_MS] before it.
     * Walks the (lid, t) index back from the newest frame, so it costs one probe per frame of the trip.
     */
    fun tripStart(): Long? = readableDatabase.rawQuery(
        "SELECT t FROM frame f WHERE lid = '0C' AND NOT EXISTS (SELECT 1 FROM frame g WHERE g.lid = '0C' AND g.t < f.t AND g.t >= f.t - ?) ORDER BY t DESC LIMIT 1",
        arrayOf(TRIP_GAP_MS.toString()),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    /** [lid]'s frames from [from] on, oldest first. */
    fun frameSeries(lid: String, from: Long): List<Pair<Long, String>> =
        readableDatabase.rawQuery("SELECT t, hex FROM frame WHERE lid = ? AND t >= ? ORDER BY t", arrayOf(lid, from.toString())).use {
            val out = ArrayList<Pair<Long, String>>(it.count)
            while (it.moveToNext()) out += it.getLong(0) to it.getString(1)
            out
        }

    /** Average time between [lid]'s last 10 frames since [from] (the trip start), i.e. how often it is read; null with fewer than 2. */
    fun readEvery(lid: String, from: Long): Long? = readableDatabase.rawQuery(
        "SELECT (MAX(t) - MIN(t)) / (COUNT(*) - 1) FROM (SELECT t FROM frame WHERE lid = ? AND t >= ? ORDER BY t DESC LIMIT 10) HAVING COUNT(*) > 1",
        arrayOf(lid, from.toString()),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun pid(name: String) = PIDS.first { it.name == name }

    /** Newest DPF pressure readings (kPa) taken with the engine stopped (rpm 0) and the ignition on, newest first. */
    fun engineOffDp(limit: Int = 5): List<Double> = readableDatabase.rawQuery(
        "SELECT e.hex FROM frame e JOIN frame c ON c.t = e.t AND c.lid = '0C' AND c.hex = '0000' WHERE e.lid = 'EF' ORDER BY e.t DESC LIMIT ?",
        arrayOf(limit.toString()),
    ).use {
        val dp = pid("DPF Differential Pressure")
        val out = ArrayList<Double>()
        while (it.moveToNext()) dp.decode(it.getString(0))?.let(out::add)
        out
    }

    /** [restriction] for each cycle in [[from], [to]] that read pressure, MAF, exhaust temperature and rpm together. */
    fun restrictions(from: Long, to: Long): List<Double> = readableDatabase.rawQuery(
        "SELECT e.hex, m.hex, f.hex, c.hex FROM frame e " +
            "JOIN frame m ON m.t = e.t AND m.lid = '10' JOIN frame f ON f.t = e.t AND f.lid = 'F1' JOIN frame c ON c.t = e.t AND c.lid = '0C' " +
            "WHERE e.lid = 'EF' AND e.t BETWEEN ? AND ? ORDER BY e.t",
        arrayOf(from.toString(), to.toString()),
    ).use {
        val dp = pid("DPF Differential Pressure"); val maf = pid("MAF"); val ex = pid("Exhaust Temperature B1S2"); val rpm = pid("Engine Speed")
        val out = ArrayList<Double>()
        while (it.moveToNext()) {
            val v = restriction(dp.decode(it.getString(0)) ?: continue, maf.decode(it.getString(1)) ?: continue,
                ex.decode(it.getString(2)) ?: continue, rpm.decode(it.getString(3)) ?: continue)
            if (v != null) out += v
        }
        out
    }

    /**
     * [DpfCondition] for this phone's finished regens, oldest first: restriction in the 5 min after each (a fast-loading filter has already risen a lot by 15), and its rise
     * per km over the last 10 min of driving before the next regen (or before the newest frame, for the latest one).
     */
    fun dpfConditions(): List<DpfCondition> {
        val rows = readableDatabase.rawQuery("SELECT start_t, end_t, odo_end, odo_start FROM regen WHERE end_t IS NOT NULL ORDER BY start_t", null).use {
            val out = ArrayList<DoubleArray>()
            // A missing odometer reading is NaN, which fails dpfCondition's km check instead of faking a distance
            fun odo(i: Int) = if (it.isNull(i)) Double.NaN else it.getDouble(i)
            while (it.moveToNext()) out += doubleArrayOf(it.getLong(0).toDouble(), it.getLong(1).toDouble(), odo(2), odo(3))
            out
        }
        val newest = readableDatabase.rawQuery("SELECT COALESCE(MAX(t), 0) FROM frame", null).use { it.moveToFirst(); it.getLong(0) }
        return rows.mapIndexed { i, r ->
            val end = r[1].toLong()
            val next = rows.getOrNull(i + 1)
            val until = next?.get(0)?.toLong() ?: newest
            val km = ((next?.get(3) ?: odometer()) - r[2]) / 1000
            dpfCondition(r[0].toLong(), restrictions(end, end + 5 * 60_000L), restrictions(until - 10 * 60_000L, until), km)
        }
    }

    /** [DpfCondition] rows for the hub: [regen start, base, per km]; nulls where there wasn't enough driving to tell. */
    fun dpfRows(): JSONArray = JSONArray().apply {
        for (d in dpfConditions()) put(JSONArray().put(d.regenStart).put(d.base ?: JSONObject.NULL).put(d.perKm ?: JSONObject.NULL))
    }

    /** What the drive since [from] did, for the end-of-drive notification: metres, first and last soot, regens and warnings started. */
    fun tripStats(from: Long): TripStats {
        val (m, s0, s1) = readableDatabase.rawQuery(
            "SELECT MAX(odo_m) - MIN(odo_m), (SELECT soot FROM sample WHERE t >= ?1 AND soot IS NOT NULL ORDER BY t LIMIT 1), " +
                "(SELECT soot FROM sample WHERE t >= ?1 AND soot IS NOT NULL ORDER BY t DESC LIMIT 1) FROM sample WHERE t >= ?1",
            arrayOf(from.toString()),
        ).use { it.moveToFirst(); Triple(if (it.isNull(0)) 0.0 else it.getDouble(0), it.intOrNull(1), it.intOrNull(2)) }
        fun count(sql: String) = readableDatabase.rawQuery(sql, arrayOf(from.toString())).use { it.moveToFirst(); it.getInt(0) }
        return TripStats(m, s0, s1, count("SELECT COUNT(*) FROM regen WHERE start_t >= ?"), count("SELECT COUNT(DISTINCT key) FROM alert WHERE start_t >= ?"))
    }

    class TripStats(val metres: Double, val sootStart: Int?, val sootEnd: Int?, val regens: Int, val warnings: Int)

    /** Deletes closed regens shorter than a minute that covered no distance: the engine-stop blip of the 21DF bit, not a burn. */
    fun dropStopBlips() = writableDatabase.execSQL(
        "DELETE FROM regen WHERE end_t IS NOT NULL AND end_t - start_t < 60000 AND COALESCE(odo_end, odo_start, 0) - COALESCE(odo_start, 0) < 50",
    )

    /** A consistent copy of the whole database in [file] (replaced), taken while the service may still be writing. */
    fun exportTo(file: java.io.File) {
        file.delete() // VACUUM INTO refuses an existing file
        writableDatabase.execSQL("VACUUM INTO ?", arrayOf(file.path))
    }

    /** Keeps the first frame per request per minute in [[from], [to]): old drives stay chartable at a fraction of the size. */
    fun thinFrames(from: Long, to: Long) = writableDatabase.execSQL(
        "DELETE FROM frame WHERE t >= ? AND t < ? AND rowid NOT IN (SELECT MIN(rowid) FROM frame WHERE t >= ? AND t < ? GROUP BY lid, t / 60000)",
        arrayOf(from, to, from, to),
    )

    /** Copies of the other phones' rows (from the hub); this phone's own log stays in sample/regen. */
    private fun createPeers(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE peer_sample(device TEXT, phone_rowid INTEGER, t INTEGER, soot INTEGER, temp INTEGER, regen INTEGER, odo_m REAL, PRIMARY KEY(device, phone_rowid))")
        db.execSQL("CREATE INDEX peer_sample_t ON peer_sample(t)")
        db.execSQL("CREATE INDEX peer_sample_device_t ON peer_sample(device, t)")
        db.execSQL("CREATE TABLE peer_regen(device TEXT, id INTEGER, start_t INTEGER, end_t INTEGER, soot_start INTEGER, soot_end INTEGER)")
    }

    fun sample(t: Long, soot: Int?, temp: Int?, regen: Boolean, odoM: Double) {
        writableDatabase.insert("sample", null, ContentValues().apply {
            put("t", t); put("soot", soot); put("temp", temp); put("regen", regen); put("odo_m", odoM)
        })
    }

    fun regenStart(t: Long, odoM: Double, soot: Int?): Long =
        writableDatabase.insert("regen", null, ContentValues().apply {
            put("start_t", t); put("odo_start", odoM); put("soot_start", soot)
        })

    fun regenEnd(id: Long, t: Long, odoM: Double, soot: Int?) {
        writableDatabase.update("regen", ContentValues().apply {
            put("end_t", t); put("odo_end", odoM); put("soot_end", soot)
        }, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Regens left open because the process was killed mid-burn (no finally ran, so Recorder never closed them): ends each
     * at its last regen reading before the next regen, like the hub's close_if_stale. Call before a new session starts.
     */
    fun closeOrphanRegens() = writableDatabase.execSQL(
        """
        UPDATE regen SET (end_t, odo_end, soot_end) = (
            SELECT COALESCE(MAX(s.t), regen.start_t), s.odo_m, s.soot FROM sample s
            WHERE s.regen = 1 AND s.t >= regen.start_t
              AND s.t < COALESCE((SELECT MIN(r.start_t) FROM regen r WHERE r.start_t > regen.start_t), 9223372036854775807))
        WHERE end_t IS NULL
        """,
    )

    fun odometer(): Double =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(odo_m), 0) FROM sample", null).use { it.moveToFirst(); it.getDouble(0) }

    /** The car's latest reading, from this phone or another one. */
    fun lastSample(): Sample? = listOf("sample", "peer_sample").mapNotNull { table ->
        readableDatabase.rawQuery("SELECT t, soot, temp, regen FROM $table ORDER BY t DESC LIMIT 1", null).use {
            if (it.moveToFirst()) Sample(it.getLong(0), it.intOrNull(1), it.intOrNull(2), it.getInt(3) == 1) else null
        }
    }.maxByOrNull { it.t }

    /** Every phone's regens, oldest first, the same burn seen by two phones counted once; gaps are car distance. */
    fun regens(): List<Regen> {
        val rows = mutableListOf<Regen>()
        readableDatabase.rawQuery(
            "SELECT start_t, end_t, soot_start, soot_end FROM regen UNION ALL SELECT start_t, end_t, soot_start, soot_end FROM peer_regen ORDER BY start_t", null,
        ).use {
            while (it.moveToNext()) rows += Regen(it.getLong(0), if (it.isNull(1)) null else it.getLong(1), null, it.intOrNull(2), it.intOrNull(3))
        }
        val phones = phones()
        var prevEnd: Long? = null
        return mergeRegens(rows).map { r ->
            r.copy(gapM = prevEnd?.let { driven(phones, it, r.start) }).also { if (r.end != null) prevEnd = r.end }
        }
    }

    /** Soot averaged per 10-minute bucket since [since] (epoch ms), oldest first, from every phone. */
    fun sootSeries(since: Long): List<Pair<Long, Float>> =
        readableDatabase.rawQuery(
            "SELECT (t / 600000) * 600000 AS b, AVG(soot) FROM (SELECT t, soot FROM sample WHERE t >= ?1 UNION ALL SELECT t, soot FROM peer_sample WHERE t >= ?1) " +
                "WHERE soot IS NOT NULL GROUP BY b ORDER BY b",
            arrayOf(since.toString()),
        ).use {
            val out = ArrayList<Pair<Long, Float>>(it.count)
            while (it.moveToNext()) out += it.getLong(0) to it.getFloat(1)
            out
        }

    /** Car distance since the last finished regen, by any phone. */
    fun metresSinceRegen(): Double? =
        readableDatabase.rawQuery("SELECT MAX(t) FROM (SELECT MAX(end_t) AS t FROM regen UNION ALL SELECT MAX(end_t) FROM peer_regen)", null).use {
            if (it.moveToFirst() && !it.isNull(0)) driven(phones(), it.getLong(0), Long.MAX_VALUE) else null
        }

    /** null = this phone, then the other phones' ids. Cached: the DISTINCT scans every peer row, and only putPeers changes it. */
    @Volatile private var phones: List<String?>? = null

    private fun phones(): List<String?> = phones ?: (listOf<String?>(null) +
        readableDatabase.rawQuery("SELECT DISTINCT device FROM peer_sample", null).use { c -> List(c.count) { c.moveToNext(); c.getString(0) } })
        .also { phones = it }

    /** Metres the car drove between two instants: each phone's own GPS odometer only grows, so add up their progress. */
    private fun driven(phones: List<String?>, t0: Long, t1: Long): Double =
        phones.sumOf { maxOf(0.0, (odoAt(it, t1) ?: 0.0) - (odoAt(it, t0) ?: 0.0)) }

    /** A phone's odometer at [t]: its last reading by then, else its first reading (it hadn't driven yet). */
    private fun odoAt(phone: String?, t: Long): Double? {
        val (from, args) = if (phone == null) "sample WHERE" to emptyArray<String>() else "peer_sample WHERE device = ? AND" to arrayOf(phone)
        fun one(sql: String, vararg a: String) = readableDatabase.rawQuery(sql, a).use { if (it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else null }
        return one("SELECT odo_m FROM $from t <= ? ORDER BY t DESC LIMIT 1", *args, t.toString())
            ?: one("SELECT odo_m FROM $from 1 ORDER BY t LIMIT 1", *args)
    }

    /**
     * Stores one page of the hub's export: other phones' samples (re-sent rows are ignored) and their full regen list,
     * which replaces the old one because regens gain an end time later.
     */
    fun putPeers(samples: JSONArray, regens: JSONArray) {
        // A future-dated row would win lastSample() forever (a "live" regen that never ends): bad peer clock or forged hub data
        val latest = System.currentTimeMillis() + PEER_CLOCK_SLACK_MS
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until samples.length()) {
                val r = samples.getJSONArray(i) // device, phone_rowid, t, soot, temp, regen, odo_m
                if (r.getLong(2) > latest) continue
                db.insertWithOnConflict("peer_sample", null, ContentValues().apply {
                    put("device", r.getString(0)); put("phone_rowid", r.getLong(1)); put("t", r.getLong(2))
                    put("soot", r.intOrNull(3)); put("temp", r.intOrNull(4)); put("regen", r.getInt(5) == 1); put("odo_m", r.optDouble(6, 0.0))
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.delete("peer_regen", null, null)
            for (i in 0 until regens.length()) {
                val r = regens.getJSONArray(i) // device, id, start_t, end_t, soot_start, soot_end
                if (r.getLong(2) > latest || (!r.isNull(3) && r.getLong(3) > latest)) continue
                db.insert("peer_regen", null, ContentValues().apply {
                    put("device", r.getString(0)); put("id", r.getLong(1)); put("start_t", r.getLong(2))
                    put("end_t", if (r.isNull(3)) null else r.getLong(3)); put("soot_start", r.intOrNull(4)); put("soot_end", r.intOrNull(5))
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            phones = null
        }
    }

    private fun JSONArray.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

    /** Sample rows after [rowid] for the hub, as [rowid, t, soot, temp, regen, odo_m]. */
    fun samplesAfter(rowid: Long, limit: Int): JSONArray =
        readableDatabase.rawQuery(
            "SELECT rowid, t, soot, temp, regen, odo_m FROM sample WHERE rowid > ? ORDER BY rowid LIMIT ?",
            arrayOf(rowid.toString(), limit.toString()),
        ).use {
            val out = JSONArray()
            while (it.moveToNext()) out.put(JSONArray().put(it.getLong(0)).put(it.getLong(1)).put(it.json(2)).put(it.json(3))
                .put(it.getInt(4) == 1).put(it.getDouble(5)))
            out
        }

    /** Frame rows after [rowid] for the hub, as [rowid, t, lid, hex]. The hub takes 2-character lids only, so not the ash record. */
    fun framesAfter(rowid: Long, limit: Int): JSONArray =
        readableDatabase.rawQuery("SELECT rowid, t, lid, hex FROM frame WHERE rowid > ? AND length(lid) = 2 ORDER BY rowid LIMIT ?", arrayOf(rowid.toString(), limit.toString())).use {
            val out = JSONArray()
            while (it.moveToNext()) out.put(JSONArray().put(it.getLong(0)).put(it.getLong(1)).put(it.getString(2)).put(it.getString(3)))
            out
        }

    /** Same filter as [framesAfter], or a newest ash record row would look pending forever and sync every minute. */
    fun maxFrameRowid(): Long =
        // ORDER BY rowid DESC LIMIT 1 walks back from the newest row; MAX() with a WHERE would scan the whole table
        readableDatabase.rawQuery("SELECT rowid FROM frame WHERE length(lid) = 2 ORDER BY rowid DESC LIMIT 1", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Alerts open or ended after [since], as [id, key, level, text, start, end]; resent whole when they change (text and end do). */
    fun alertRows(since: Long): JSONArray =
        readableDatabase.rawQuery("SELECT id, key, level, text, start_t, end_t FROM alert WHERE end_t IS NULL OR end_t >= ?", arrayOf(since.toString())).use {
            val out = JSONArray()
            while (it.moveToNext()) out.put(JSONArray().put(it.getLong(0)).put(it.getString(1)).put(it.getInt(2)).put(it.getString(3)).put(it.getLong(4)).put(it.json(5)))
            out
        }

    /** Every regen row, as [id, start, end, odo_start, odo_end, soot_start, soot_end]. Small, so always sent whole. */
    fun regenRows(): JSONArray =
        readableDatabase.rawQuery("SELECT id, start_t, end_t, odo_start, odo_end, soot_start, soot_end FROM regen", null).use {
            val out = JSONArray()
            while (it.moveToNext()) out.put(JSONArray().apply { for (i in 0 until 7) put(if (i == 0) it.getLong(0) else it.json(i)) })
            out
        }

    /** Changes whenever a regen is added or closed, so the hub can be told without resending every time. */
    fun regenSignature(): String =
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(end_t), 0) FROM regen", null).use { it.moveToFirst(); "${it.getLong(0)}:${it.getLong(1)}" }

    fun maxSampleRowid(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(rowid), 0) FROM sample", null).use { it.moveToFirst(); it.getLong(0) }

    private fun Cursor.json(i: Int): Any = when {
        isNull(i) -> JSONObject.NULL
        getType(i) == Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        else -> getLong(i)
    }

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

    companion object {
        /** Regens from two phones (dongle hand-over mid-burn) closer than this are the same burn; matches the hub. */
        const val MERGE_SLACK_MS = 2 * 60_000L
        /** ECU silent longer than this ends a trip; the service's REGEN_GAP_MS ends a drive at the same point. */
        const val TRIP_GAP_MS = 2 * 60_000L
        private const val PEER_CLOCK_SLACK_MS = 5 * 60_000L
        const val METRES_PER_MILE = 1609.344
        /** A regen's soot floor is read this long after it ends (see [withSigns]). */
        const val FLOOR_AFTER_MS = 5 * 60_000L
        private const val SAMPLE_T_INDEX = "CREATE INDEX IF NOT EXISTS sample_t ON sample(t)"
        private var real: Db? = null
        private var debug: Db? = null

        /** In Debug mode: a fresh in-memory database seeded with fake history; yaris.db is never touched. */
        @Synchronized fun get(ctx: Context): Db {
            val c = ctx.applicationContext
            if (!Prefs.debug) return real(c)
            return debug ?: Db(c, null).also { debug = it; Sim.seed(it, System.currentTimeMillis()) }
        }

        /** The real log, even while Debug mode is on: only this is ever uploaded. */
        @Synchronized fun real(ctx: Context): Db = real ?: Db(ctx.applicationContext, "yaris.db").also { real = it }

        @Synchronized fun dropDebug() {
            debug?.close()
            debug = null
        }
    }
}

/**
 * [rows] sorted by start. A finished regen that starts within [Db.MERGE_SLACK_MS] of the previous finished one's end
 * is the same burn seen by another phone. Open regens are never merged, so a phone killed mid-burn can't swallow later ones.
 */
fun mergeRegens(rows: List<Db.Regen>): List<Db.Regen> {
    val out = mutableListOf<Db.Regen>()
    for (r in rows) {
        val last = out.lastOrNull()
        if (last?.end != null && r.end != null && r.start <= last.end + Db.MERGE_SLACK_MS) {
            if (r.end > last.end) out[out.lastIndex] = last.copy(end = r.end, sootEnd = r.sootEnd)
        } else {
            out += r
        }
    }
    return out
}
