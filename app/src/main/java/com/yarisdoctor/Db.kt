package com.yarisdoctor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "yaris.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // odo_m: GPS distance accumulated by this app (metres), our stand-in for the odometer
        db.execSQL("CREATE TABLE sample(t INTEGER, soot INTEGER, temp INTEGER, regen INTEGER, odo_m REAL)")
        db.execSQL("CREATE TABLE regen(id INTEGER PRIMARY KEY, start_t INTEGER, end_t INTEGER, odo_start REAL, odo_end REAL, soot_start INTEGER, soot_end INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

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

    fun lastSample(): String? =
        readableDatabase.rawQuery("SELECT t, soot, temp, regen FROM sample ORDER BY t DESC LIMIT 1", null).use {
            if (it.moveToFirst()) "${fmt(it.getLong(0))}  soot ${it.getString(1) ?: "?"}%  temp ${it.getString(2) ?: "?"}°C" +
                (if (it.getInt(3) == 1) "  REGEN" else "") else null
        }

    /** One line per regen, newest first: when, how long, miles driven since the previous regen ended. */
    fun regens(): List<String> {
        val rows = mutableListOf<String>()
        var prevEnd: Double? = null
        readableDatabase.rawQuery("SELECT start_t, end_t, odo_start, odo_end, soot_start, soot_end FROM regen ORDER BY start_t", null).use {
            while (it.moveToNext()) {
                val mins = if (it.isNull(1)) "ongoing" else "${(it.getLong(1) - it.getLong(0)) / 60_000} min"
                val miles = prevEnd?.let { p -> "%.0f mi since last".format((it.getDouble(2) - p) / METRES_PER_MILE) } ?: "first logged"
                rows += "${fmt(it.getLong(0))}  $mins  $miles  soot ${it.getString(4) ?: "?"}→${it.getString(5) ?: "?"}%"
                if (!it.isNull(3)) prevEnd = it.getDouble(3)
            }
        }
        return rows.reversed()
    }

    fun milesSinceRegen(): Double? =
        readableDatabase.rawQuery("SELECT odo_end FROM regen WHERE odo_end IS NOT NULL ORDER BY start_t DESC LIMIT 1", null).use {
            if (it.moveToFirst()) (odometer() - it.getDouble(0)) / METRES_PER_MILE else null
        }

    companion object {
        const val METRES_PER_MILE = 1609.344
        private var instance: Db? = null
        @Synchronized fun get(ctx: Context) = instance ?: Db(ctx.applicationContext).also { instance = it }
        private fun fmt(t: Long) = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()).format(t)
    }
}
