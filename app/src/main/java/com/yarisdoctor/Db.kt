package com.yarisdoctor

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.yarisdoctor.domain.car.PIDS
import com.yarisdoctor.domain.car.PID_IDLE
import com.yarisdoctor.domain.car.PID_LENGTHS
import com.yarisdoctor.domain.drive.Poller
import com.yarisdoctor.domain.drive.mergeRegens
import com.yarisdoctor.domain.drive.mergeSameBurn
import com.yarisdoctor.domain.health.DpfCondition
import com.yarisdoctor.domain.health.Finding
import com.yarisdoctor.domain.health.FinishedRegen
import com.yarisdoctor.domain.health.diffAlerts
import com.yarisdoctor.domain.health.dpfConditions
import com.yarisdoctor.domain.health.restriction
import com.yarisdoctor.domain.model.Alert
import com.yarisdoctor.domain.model.FLOOR_AFTER_MS
import com.yarisdoctor.domain.model.Regen
import com.yarisdoctor.domain.model.Sample
import com.yarisdoctor.domain.model.TRIP_GAP_MS
import com.yarisdoctor.domain.model.Trip
import com.yarisdoctor.domain.model.TripStats
import com.yarisdoctor.domain.repo.DriveStore
import org.json.JSONArray
import org.json.JSONObject

/** [name] null = in-memory database (Debug mode), gone when closed. Created by [AppContainer]: one per database per process. */
class Db internal constructor(private val ctx: Context, name: String?) : SQLiteOpenHelper(ctx, name, null, 6), DriveStore {

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

    /** Runs [block] in one transaction, committed only if it returns normally. */
    private inline fun <T> tx(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    fun linkStats(t: Long, timings: Map<String, Elm.Timing>) = tx { db ->
        for ((cmd, x) in timings) if (x.n > 0) db.insert("link", null, ContentValues().apply {
            put("t", t); put("cmd", cmd); put("n", x.n); put("ok", x.ok)
            put("first_ms", x.firstMs / x.n); put("tail_ms", x.tailMs / x.n); put("total_ms", x.totalMs / x.n)
        })
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
    override fun syncAlerts(t: Long, active: List<Finding>): List<Finding> = tx { db ->
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
        d.open
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
     * ash record and the calibration ID, kept under their whole codes ("A918", "0904") so they can't clash with a `21` lid.
     */
    override fun frames(t: Long, replies: Map<String, String>) = tx { db ->
        for ((code, reply) in replies) {
            val lid = when (code) {
                in PID_LENGTHS -> code.substring(2)
                Poller.ASH_RECORD, Poller.CAL_ID -> code
                else -> continue
            }
            db.insert("frame", null, ContentValues().apply { put("t", t); put("lid", lid); put("hex", reply.substring(4)) })
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

    /** The ash record's readings where its value changed (the first one included), newest first. Read every minute, and its counters move while the engine runs, so this is about the last 10 minutes. */
    fun ashHistory(limit: Int = 10): List<Pair<Long, String>> = readableDatabase.rawQuery(
        "SELECT t, hex FROM (SELECT t, hex, LAG(hex) OVER (ORDER BY t) AS prev FROM frame WHERE lid = ?) WHERE prev IS NULL OR prev != hex ORDER BY t DESC LIMIT ?",
        arrayOf(Poller.ASH_RECORD, limit.toString()),
    ).use {
        val out = ArrayList<Pair<Long, String>>()
        while (it.moveToNext()) out += it.getLong(0) to it.getString(1)
        out
    }

    override fun recentRegens(n: Int) = withSigns(regens().takeLast(n))

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
    override fun engineOffDp(limit: Int): List<Double> = readableDatabase.rawQuery(
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

    /** [DpfCondition] for this phone's finished regens, oldest first (see [dpfConditions] in the domain). */
    fun dpfConditions(): List<DpfCondition> {
        val rows = readableDatabase.rawQuery("SELECT start_t, end_t, odo_start, odo_end FROM regen WHERE end_t IS NOT NULL ORDER BY start_t", null).use {
            fun odo(i: Int) = if (it.isNull(i)) Double.NaN else it.getDouble(i)
            val out = ArrayList<FinishedRegen>()
            while (it.moveToNext()) out += FinishedRegen(it.getLong(0), it.getLong(1), odo(2), odo(3))
            out
        }
        val newest = readableDatabase.rawQuery("SELECT COALESCE(MAX(t), 0) FROM frame", null).use { it.moveToFirst(); it.getLong(0) }
        return dpfConditions(rows, newest, odometer(), ::restrictions)
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

    /** Deletes closed regens shorter than a minute that covered no distance: the engine-stop blip of the 21DF bit, not a burn. */
    override fun dropStopBlips() = writableDatabase.execSQL(
        "DELETE FROM regen WHERE end_t IS NOT NULL AND end_t - start_t < 60000 AND COALESCE(odo_end, odo_start, 0) - COALESCE(odo_start, 0) < 50",
    )

    /** This phone's drives, newest first: runs of samples with no gap over [TRIP_GAP_MS] (see [Trip]). */
    override fun trips(): List<Trip> = readableDatabase.rawQuery(
        "SELECT s, e, m, (SELECT COUNT(*) FROM regen WHERE start_t BETWEEN s AND e + $TRIP_GAP_MS) FROM " +
            "(SELECT MIN(t) AS s, MAX(t) AS e, COALESCE(MAX(odo_m) - MIN(odo_m), 0) AS m FROM " +
            "(SELECT t, odo_m, SUM(gap) OVER (ORDER BY t) AS trip FROM " +
            "(SELECT t, odo_m, COALESCE(t - LAG(t) OVER (ORDER BY t) > $TRIP_GAP_MS, 1) AS gap FROM sample)) GROUP BY trip) ORDER BY s DESC",
        null,
    ).use {
        val out = ArrayList<Trip>(it.count)
        while (it.moveToNext()) out += Trip(it.getLong(0), it.getLong(1), it.getDouble(2), it.getInt(3))
        out
    }

    /** Rows per table that [deleteTrips] would remove: readings, engine frames, regens and warnings. */
    fun tripCounts(trips: List<Trip>): Map<String, Int> = tx { db ->
        pick(db, trips)
        TRIP_ROWS.filter { it.first != "link" }.associate { (table, cond) ->
            table to db.rawQuery("SELECT COUNT(*) FROM $table WHERE ${inTrips(table, cond)}", null).use { it.moveToFirst(); it.getInt(0) }
        }
    }

    /**
     * Deletes this phone's rows in [trips]' windows (open warnings aside); other phones' copies (peer tables) are theirs and stay.
     * Returns the highest sample and frame rowids left: SQLite numbers the next rows from there, so deleting the newest trip
     * makes it reuse rowids the upload cursors have already passed (Sync.queueDeletes winds them back).
     */
    fun deleteTrips(trips: List<Trip>): Pair<Long, Long> = tx { db ->
        pick(db, trips)
        for ((table, cond) in TRIP_ROWS) db.execSQL("DELETE FROM $table WHERE ${inTrips(table, cond)}")
        db.rawQuery("SELECT (SELECT COALESCE(MAX(rowid), 0) FROM sample), (SELECT COALESCE(MAX(rowid), 0) FROM frame)", null)
            .use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }
    }

    /** Rows of [table] that [deleteTrips] removes. A warning still open stays: the service holds it and would raise it again as new. */
    private fun inTrips(table: String, cond: String) =
        "EXISTS (SELECT 1 FROM pick WHERE $cond)" + if (table == "alert") " AND end_t IS NOT NULL" else ""

    /** [trips]' windows in temp table pick(a, b) on [db]'s connection, so any number of them fits in one statement. */
    private fun pick(db: SQLiteDatabase, trips: List<Trip>) {
        db.execSQL("CREATE TEMP TABLE IF NOT EXISTS pick(a INTEGER, b INTEGER)")
        db.execSQL("DELETE FROM pick")
        for (trip in trips) db.insert("pick", null, ContentValues().apply { put("a", trip.start); put("b", trip.until) })
    }

    /**
     * Keeps the first frame per request per minute in [[from], [to]): old drives stay chartable at a fraction of the size.
     * The ash record (A918) and calibration ID (0904) are lids too, so they thin like any other request.
     */
    override fun thinFrames(from: Long, to: Long) = thin(from, to, BUCKET_MINUTE_MS)

    /**
     * Tiered frame retention for the real log: the last [RECENT_MS] is untouched, 7-90 days keep one frame per [lid] per
     * minute, and anything older one per hour. Each pass starts where the previous one stopped (Prefs.THINNED_TO and
     * Prefs.THINNED_HOURLY_TO), so only new rows are looked at and a second run deletes nothing.
     */
    fun thinFrames(now: Long) {
        // 7-90 days: one per lid per minute. From at most 90 days back, so older rows go straight to the hourly pass
        val minuteTo = now - RECENT_MS
        val minuteFrom = maxOf(Prefs.long(ctx, Prefs.THINNED_TO), now - HOURLY_AFTER_MS)
        if (minuteTo - minuteFrom >= TRIM_STEP_MS) {
            thin(minuteFrom, minuteTo, BUCKET_MINUTE_MS)
            Prefs.put(ctx, Prefs.THINNED_TO, minuteTo)
        }
        // Older than 90 days: one per lid per hour
        val hourTo = now - HOURLY_AFTER_MS
        val hourFrom = Prefs.long(ctx, Prefs.THINNED_HOURLY_TO)
        if (hourTo - hourFrom >= TRIM_STEP_MS) {
            thin(hourFrom, hourTo, BUCKET_HOUR_MS)
            Prefs.put(ctx, Prefs.THINNED_HOURLY_TO, hourTo)
        }
    }

    /** Deletes every frame in [[from], [to]) but the lowest rowid of each [lid] per [bucketMs]. */
    private fun thin(from: Long, to: Long, bucketMs: Long) = writableDatabase.execSQL(
        "DELETE FROM frame WHERE t >= ? AND t < ? AND rowid NOT IN (SELECT MIN(rowid) FROM frame WHERE t >= ? AND t < ? GROUP BY lid, t / $bucketMs)",
        arrayOf(from, to, from, to),
    )

    /** Copies of the other phones' rows (from the hub); this phone's own log stays in sample/regen. */
    private fun createPeers(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE peer_sample(device TEXT, phone_rowid INTEGER, t INTEGER, soot INTEGER, temp INTEGER, regen INTEGER, odo_m REAL, PRIMARY KEY(device, phone_rowid))")
        db.execSQL("CREATE INDEX peer_sample_t ON peer_sample(t)")
        db.execSQL("CREATE INDEX peer_sample_device_t ON peer_sample(device, t)")
        db.execSQL("CREATE TABLE peer_regen(device TEXT, id INTEGER, start_t INTEGER, end_t INTEGER, soot_start INTEGER, soot_end INTEGER)")
    }

    override fun sample(t: Long, soot: Int?, temp: Int?, regen: Boolean, odoM: Double) {
        writableDatabase.insert("sample", null, ContentValues().apply {
            put("t", t); put("soot", soot); put("temp", temp); put("regen", regen); put("odo_m", odoM)
        })
    }

    override fun regenStart(t: Long, odoM: Double, soot: Int?): Long =
        writableDatabase.insert("regen", null, ContentValues().apply {
            put("start_t", t); put("odo_start", odoM); put("soot_start", soot)
        })

    override fun regenEnd(id: Long, t: Long, odoM: Double, soot: Int?) {
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
    override fun lastSample(): Sample? = listOf("sample", "peer_sample").mapNotNull { table ->
        readableDatabase.rawQuery("SELECT t, soot, temp, regen FROM $table ORDER BY t DESC LIMIT 1", null).use {
            if (it.moveToFirst()) Sample(it.getLong(0), it.intOrNull(1), it.intOrNull(2), it.getInt(3) == 1) else null
        }
    }.maxByOrNull { it.t }

    /** Every phone's regens, oldest first, the same burn seen by two phones counted once (and one split by an ignition cycle joined); gaps are car distance. */
    fun regens(): List<Regen> {
        val rows = mutableListOf<Regen>()
        readableDatabase.rawQuery(
            "SELECT start_t, end_t, soot_start, soot_end FROM regen UNION ALL SELECT start_t, end_t, soot_start, soot_end FROM peer_regen ORDER BY start_t", null,
        ).use {
            while (it.moveToNext()) rows += Regen(it.getLong(0), if (it.isNull(1)) null else it.getLong(1), null, it.intOrNull(2), it.intOrNull(3))
        }
        val merged = mergeSameBurn(mergeRegens(rows))
        // Every instant a gap needs, read in one go: each phone's odometer is otherwise two queries per regen
        val bounds = ArrayList<Long>(merged.size * 2)
        var prevEnd: Long? = null
        for (r in merged) {
            prevEnd?.let { bounds += it }
            bounds += r.start
            if (r.end != null) prevEnd = r.end
        }
        val odo = Odometers(bounds)
        prevEnd = null
        return merged.map { r ->
            r.copy(gapM = prevEnd?.let { odo.between(it, r.start) }).also { if (r.end != null) prevEnd = r.end }
        }
    }

    /** Soot averaged per 10-minute bucket since [since] (epoch ms), oldest first, from every phone. */
    fun sootSeries(since: Long): List<Pair<Long, Float>> =
        readableDatabase.rawQuery(
            "SELECT (t / $SOOT_BUCKET_MS) * $SOOT_BUCKET_MS AS b, AVG(soot) FROM (SELECT t, soot FROM sample WHERE t >= ?1 UNION ALL SELECT t, soot FROM peer_sample WHERE t >= ?1) " +
                "WHERE soot IS NOT NULL GROUP BY b ORDER BY b",
            arrayOf(since.toString()),
        ).use {
            val out = ArrayList<Pair<Long, Float>>(it.count)
            while (it.moveToNext()) out += it.getLong(0) to it.getFloat(1)
            out
        }

    /** Car distance since the last finished regen, by any phone. */
    override fun metresSinceRegen(): Double? =
        readableDatabase.rawQuery("SELECT MAX(t) FROM (SELECT MAX(end_t) AS t FROM regen UNION ALL SELECT MAX(end_t) FROM peer_regen)", null).use {
            if (it.moveToFirst() && !it.isNull(0)) {
                val end = it.getLong(0)
                Odometers(listOf(end, Long.MAX_VALUE)).between(end, Long.MAX_VALUE)
            } else null
        }

    /** null = this phone, then the other phones' ids. Cached: the DISTINCT scans every peer row, and only putPeers changes it. */
    @Volatile private var phones: List<String?>? = null

    /** Forgets the cached phone list: call after the peer tables are rewritten outside [putPeers] (a restored backup). */
    fun invalidatePeers() { phones = null }

    private fun phones(): List<String?> = phones ?: (listOf<String?>(null) +
        readableDatabase.rawQuery("SELECT DISTINCT device FROM peer_sample", null).use { c -> List(c.count) { c.moveToNext(); c.getString(0) } })
        .also { phones = it }

    /**
     * The phones' odometers at a set of instants, read once: one indexed statement per phone over a temp table of the
     * instants. [between] is the car's distance across any two of them. Replaced two queries per phone per regen, which
     * made the Overview's 5 s refresh run hundreds of them.
     */
    private inner class Odometers(instants: List<Long>) {
        private val at = instants.distinct().sorted()
        private val index = at.withIndex().associate { (i, t) -> t to i }
        private val devices = phones()
        private val values: Map<String?, List<Double?>> = if (at.isEmpty()) devices.associateWith { emptyList<Double?>() } else read()

        /** Metres the car drove between two instants: each phone's own GPS odometer only grows, so add up their progress. */
        fun between(t0: Long, t1: Long): Double = devices.sumOf { phone ->
            val odo = values.getValue(phone)
            maxOf(0.0, (odo[index.getValue(t1)] ?: 0.0) - (odo[index.getValue(t0)] ?: 0.0))
        }

        /** A phone's odometer at each instant: its last reading by then, else its first reading (it hadn't driven yet). */
        private fun read(): Map<String?, List<Double?>> {
            val db = writableDatabase
            // One transaction: another thread's regens() must not clear the temp table between the inserts and the reads
            db.beginTransaction()
            try {
                db.execSQL("CREATE TEMP TABLE IF NOT EXISTS odo_at(i INTEGER PRIMARY KEY, t INTEGER)")
                db.execSQL("DELETE FROM odo_at")
                at.forEachIndexed { i, t -> db.insert("odo_at", null, ContentValues().apply { put("i", i); put("t", t) }) }
                val out = devices.associateWith { phone ->
                    val (from, arg) = if (phone == null) "sample WHERE" to null else "peer_sample WHERE device = ? AND" to phone
                    db.rawQuery(
                        "SELECT (SELECT odo_m FROM $from t <= odo_at.t ORDER BY t DESC LIMIT 1), (SELECT odo_m FROM $from 1 ORDER BY t LIMIT 1) FROM odo_at ORDER BY i",
                        if (arg == null) null else arrayOf(arg, arg),
                    ).use { c ->
                        val list = ArrayList<Double?>(at.size)
                        while (c.moveToNext()) list += when {
                            !c.isNull(0) -> c.getDouble(0)
                            !c.isNull(1) -> c.getDouble(1)
                            else -> null
                        }
                        list
                    }
                }
                db.setTransactionSuccessful()
                return out
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Stores one page of the hub's export: other phones' samples (re-sent rows are ignored) and their full regen list,
     * which replaces the old one because regens gain an end time later.
     */
    fun putPeers(samples: JSONArray, regens: JSONArray, deleted: JSONArray? = null) {
        // A future-dated row would win lastSample() forever (a "live" regen that never ends): bad peer clock or forged hub data
        val latest = System.currentTimeMillis() + PEER_CLOCK_SLACK_MS
        try {
            tx { db ->
                for (i in 0 until samples.length()) {
                    val r = samples.getJSONArray(i) // device, phone_rowid, t, soot, temp, regen, odo_m
                    if (r.getLong(2) > latest) continue
                    db.insertWithOnConflict("peer_sample", null, ContentValues().apply {
                        put("device", r.getString(0)); put("phone_rowid", r.getLong(1)); put("t", r.getLong(2))
                        put("soot", r.intOrNull(3)); put("temp", r.intOrNull(4)); put("regen", r.getInt(5) == 1); put("odo_m", r.optDouble(6, 0.0))
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                // Trips another phone deleted (a hub that predates this sends none): its rows in them go here too
                for (i in 0 until (deleted?.length() ?: 0)) {
                    val r = deleted!!.getJSONArray(i) // device, from, to
                    db.delete("peer_sample", "device = ? AND t BETWEEN ? AND ?", arrayOf(r.getString(0), r.getLong(1).toString(), r.getLong(2).toString()))
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
            }
        } finally {
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
        private const val PEER_CLOCK_SLACK_MS = 5 * 60_000L
        /** [sootSeries]' bucket. */
        const val SOOT_BUCKET_MS = 10 * 60_000L
        /** [thinFrames] tiers: full resolution for a week, a frame per minute to 90 days, then one per hour. */
        private const val RECENT_MS = 7 * 24 * 3_600_000L
        private const val HOURLY_AFTER_MS = 90 * 24 * 3_600_000L
        private const val TRIM_STEP_MS = 3_600_000L
        private const val BUCKET_MINUTE_MS = 60_000L
        private const val BUCKET_HOUR_MS = 3_600_000L
        /** Each table with a trip's rows and its test against a pick(a, b) window. A link row is stamped at session start, a little before the first sample. */
        private val TRIP_ROWS = listOf(
            "sample" to "sample.t BETWEEN a AND b",
            "frame" to "frame.t BETWEEN a AND b",
            "regen" to "regen.start_t BETWEEN a AND b",
            "alert" to "alert.start_t BETWEEN a AND b",
            "link" to "link.t BETWEEN a - $TRIP_GAP_MS AND b - $TRIP_GAP_MS",
        )
        private const val SAMPLE_T_INDEX = "CREATE INDEX IF NOT EXISTS sample_t ON sample(t)"
    }
}
