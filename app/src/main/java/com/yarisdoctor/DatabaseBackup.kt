package com.yarisdoctor

import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The whole log as one restorable file. [export] lets SQLite write a consistent snapshot of the live database with
 * `VACUUM INTO`; [restore] copies a snapshot's rows back in one transaction. Plain SQL and no ContentResolver, so it
 * runs and is tested on the JVM (Robolectric).
 *
 * The caller passes [AppContainer.real]: Debug mode's in-memory database is never backed up, and never restored over.
 */
object DatabaseBackup {

    /** The tables a snapshot must hold, in the order [restore] copies them. */
    private val TABLES = listOf("sample", "regen", "frame", "alert", "link", "peer_sample", "peer_regen", "maintenance", "fuel")

    /** Tables newer than [OLDEST], by the schema version that added them: an older snapshot lacks them. */
    private val ADDED = mapOf("maintenance" to 7, "fuel" to 8)

    /** The oldest snapshot [restore] takes: its other tables have the same columns as now. */
    private const val OLDEST = 6

    /** What a restore put back: this phone's readings, its regens, the engine frames and the warnings. */
    data class RestoreResult(val readings: Int, val regens: Int, val frames: Int, val warnings: Int)

    /**
     * Writes a snapshot of [db] to [to], replacing any file there. `VACUUM INTO` reads the live database as one
     * transaction, so a reading logged while it runs is either all in the snapshot or all out of it, and the result is
     * a normal database file without `-wal` or `-journal` companions.
     */
    fun export(db: Db, to: File) {
        to.delete() // VACUUM INTO refuses an existing file
        to.parentFile?.mkdirs()
        try {
            db.writableDatabase.execSQL("VACUUM INTO ${literal(to.absolutePath)}")
        } catch (e: SQLException) {
            to.delete() // never leave a half-written snapshot behind
            throw IllegalStateException("Could not write the backup file: ${e.message ?: "SQLite refused VACUUM INTO"}", e)
        }
    }

    /**
     * Checks that [from] is a snapshot this build can restore: a readable SQLite file from schema [OLDEST] up to the
     * live version, with every table its version had. Throws [IllegalArgumentException] with a message fit to show the user. Reads nothing else.
     */
    fun validate(db: Db, from: File) {
        val expected = db.readableDatabase.version
        val src = openReadOnly(from)
        try {
            // The header is only read by the first query, so a corrupt file fails here rather than in openDatabase
            val version = try {
                src.version
            } catch (e: SQLException) {
                throw notADatabase(from, e)
            }
            if (version !in OLDEST..expected) throw IllegalArgumentException(
                "The backup was made by another version of the app (database $version, this phone $expected)",
            )
            val missing = TABLES.firstOrNull { (ADDED[it] ?: 0) <= version && !hasTable(src, it) }
            if (missing != null) throw IllegalArgumentException("The backup is missing the $missing table: not a Yaris Doctor database")
        } finally {
            src.close()
        }
    }

    /**
     * Replaces every row in [db]'s tables with [from]'s, in one transaction: if anything fails the phone's log is left
     * exactly as it was. Validate first with [validate] (this does too) so a wrong file never reaches the copies.
     */
    fun restore(db: Db, from: File): RestoreResult {
        validate(db, from)
        val live = db.writableDatabase
        // ATTACH takes a plain filename; the snapshot was closed by validate(), so the path is free
        try {
            live.execSQL("ATTACH DATABASE ${literal(from.absolutePath)} AS src")
        } catch (e: SQLException) {
            throw IllegalStateException("Could not open the backup as a database: ${e.message ?: from.name}", e)
        }
        try {
            live.beginTransaction()
            try {
                for (table in TABLES) {
                    // A snapshot from before the table existed: the phone keeps its own rows (the logbook, fill-ups), rather than none
                    if (!hasTable(live, table, "src")) continue
                    live.execSQL("DELETE FROM main.$table")
                    live.execSQL("INSERT INTO main.$table SELECT * FROM src.$table")
                }
                live.setTransactionSuccessful()
            } finally {
                live.endTransaction()
            }
        } finally {
            try {
                live.execSQL("DETACH DATABASE src")
            } catch (e: SQLException) {
                // The transaction is what matters; a connection with a spent attachment is harmless either way
            }
        }
        // The peer tables were rewritten behind Db's back, so its cached phone list is stale
        db.invalidatePeers()
        return RestoreResult(count(live, "sample"), count(live, "regen"), count(live, "frame"), count(live, "alert"))
    }

    private fun openReadOnly(from: File): SQLiteDatabase {
        if (!from.isFile) throw IllegalArgumentException("There is no backup file at ${from.absolutePath}")
        // Check the header before SQLite sees it: openDatabase's corruption handler would delete the file and log a
        // "DB wipe" for something as ordinary as a picked photo, and its message is less useful than this one.
        if (!looksLikeSqlite(from)) throw IllegalArgumentException("This file is not a SQLite database: ${from.name}")
        return try {
            SQLiteDatabase.openDatabase(from.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLException) {
            throw notADatabase(from, e)
        }
    }

    /** The 16-byte header every SQLite 3 file starts with; a file shorter than one page cannot be a database. */
    private fun looksLikeSqlite(from: File): Boolean {
        if (from.length() < 512) return false
        val header = ByteArray(16)
        val read = from.inputStream().use { it.read(header) }
        return read == 16 && header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
    }

    private fun notADatabase(from: File, e: SQLException) =
        IllegalArgumentException("This file is not a Yaris Doctor database: ${e.message ?: from.name}", e)

    private fun hasTable(db: SQLiteDatabase, table: String, schema: String = "main"): Boolean =
        db.rawQuery("SELECT 1 FROM $schema.sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    /** A SQL string literal: the paths come from cacheDir and the file picker's cache copy, and quotes are legal in both. */
    private fun literal(s: String) = "'" + s.replace("'", "''") + "'"
}
