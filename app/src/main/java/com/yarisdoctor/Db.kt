package com.yarisdoctor

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "yaris.db", null, 2) {

    data class Sample(val t: Long, val soot: Int?, val temp: Int?, val regen: Boolean)

    /** [miles]: driven between the previous regen's end and this one's start; null for the first logged regen. */
    data class Regen(val start: Long, val end: Long?, val miles: Double?, val sootStart: Int?, val sootEnd: Int?)

    override fun onCreate(db: SQLiteDatabase) {
        // odo_m: GPS distance accumulated by this app (metres), our stand-in for the odometer
        db.execSQL("CREATE TABLE sample(t INTEGER, soot INTEGER, temp INTEGER, regen INTEGER, odo_m REAL)")
        db.execSQL("CREATE TABLE regen(id INTEGER PRIMARY KEY, start_t INTEGER, end_t INTEGER, odo_start REAL, odo_end REAL, soot_start INTEGER, soot_end INTEGER)")
        db.execSQL(SAMPLE_T_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v2: time index for the charts' range queries
        if (old < 2) db.execSQL(SAMPLE_T_INDEX)
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

    fun odometer(): Double =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(odo_m), 0) FROM sample", null).use { it.moveToFirst(); it.getDouble(0) }

    fun lastSample(): Sample? =
        readableDatabase.rawQuery("SELECT t, soot, temp, regen FROM sample ORDER BY t DESC LIMIT 1", null).use {
            if (it.moveToFirst()) Sample(it.getLong(0), it.intOrNull(1), it.intOrNull(2), it.getInt(3) == 1) else null
        }

    /** Oldest first. */
    fun regens(): List<Regen> {
        val rows = mutableListOf<Regen>()
        var prevEnd: Double? = null
        readableDatabase.rawQuery("SELECT start_t, end_t, odo_start, odo_end, soot_start, soot_end FROM regen ORDER BY start_t", null).use {
            while (it.moveToNext()) {
                val miles = prevEnd?.let { p -> (it.getDouble(2) - p) / METRES_PER_MILE }
                rows += Regen(it.getLong(0), if (it.isNull(1)) null else it.getLong(1), miles, it.intOrNull(4), it.intOrNull(5))
                if (!it.isNull(3)) prevEnd = it.getDouble(3)
            }
        }
        return rows
    }

    /** Soot averaged per 10-minute bucket since [since] (epoch ms), oldest first. */
    fun sootSeries(since: Long): List<Pair<Long, Float>> =
        readableDatabase.rawQuery(
            "SELECT (t / 600000) * 600000 AS b, AVG(soot) FROM sample WHERE t >= ? AND soot IS NOT NULL GROUP BY b ORDER BY b",
            arrayOf(since.toString()),
        ).use {
            val out = ArrayList<Pair<Long, Float>>(it.count)
            while (it.moveToNext()) out += it.getLong(0) to it.getFloat(1)
            out
        }

    fun milesSinceRegen(): Double? =
        readableDatabase.rawQuery("SELECT odo_end FROM regen WHERE odo_end IS NOT NULL ORDER BY start_t DESC LIMIT 1", null).use {
            if (it.moveToFirst()) (odometer() - it.getDouble(0)) / METRES_PER_MILE else null
        }

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

    companion object {
        const val METRES_PER_MILE = 1609.344
        private const val SAMPLE_T_INDEX = "CREATE INDEX IF NOT EXISTS sample_t ON sample(t)"
        private var instance: Db? = null
        @Synchronized fun get(ctx: Context) = instance ?: Db(ctx.applicationContext).also { instance = it }
    }
}
