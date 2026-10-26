package com.yarisdoctor

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.yarisdoctor.domain.health.Finding
import java.io.File
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DatabaseBackup] against real SQLite: `VACUUM INTO` from the live database, the schema check, and the all-or-nothing
 * restore. No device needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseBackupTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "backup-test").apply { mkdirs() }
    private val live = Db(context, null)
    private val snapshot = File(dir, "snapshot.db")

    @After fun closeAndClean() {
        live.close()
        dir.deleteRecursively()
    }

    /** A phone's log: three readings, one closed regen, two engine frames, one warning, and another phone's reading. */
    private fun seed(db: Db) {
        db.sample(1_000, 10, 200, false, 100.0)
        db.sample(2_000, 30, 620, true, 250.0)
        db.sample(3_000, 12, 240, false, 400.0)
        val id = db.regenStart(2_000, 250.0, 30)
        db.regenEnd(id, 2_500, 300.0, 12)
        db.frames(2_000, mapOf("210C" to "610C0BB8", "21DD" to "61DD00C8"))
        db.syncAlerts(2_000, listOf(Finding("dpf_temp", 2, "DPF too hot")))
        db.putPeers(
            JSONArray().put(JSONArray().put("other").put(7).put(1_500).put(11).put(210).put(0).put(50.0)),
            JSONArray().put(JSONArray().put("other").put(7).put(1_500).put(1_900).put(11).put(9)),
        )
    }

    @Test fun roundTripCopiesEveryTable() {
        seed(live)
        DatabaseBackup.export(live, snapshot)

        val fresh = Db(context, null)
        try {
            assertEquals(
                DatabaseBackup.RestoreResult(readings = 3, regens = 1, frames = 2, warnings = 1),
                DatabaseBackup.restore(fresh, snapshot),
            )
            assertEquals("restore keeps the schema version", 6, fresh.readableDatabase.version)

            assertEquals(listOf(1000L, 2000L, 3000L), fresh.rows("SELECT t FROM sample ORDER BY t") { it.getLong(0) })
            assertEquals(listOf(10, 30, 12), fresh.rows("SELECT soot FROM sample ORDER BY t") { it.getInt(0) })
            assertEquals(400.0, fresh.rows("SELECT odo_m FROM sample ORDER BY t DESC LIMIT 1") { it.getDouble(0) }.first(), 0.001)

            assertEquals(250.0, fresh.rows("SELECT odo_start FROM regen") { it.getDouble(0) }.first(), 0.001)
            assertEquals(300.0, fresh.rows("SELECT odo_end FROM regen") { it.getDouble(0) }.first(), 0.001)
            assertEquals(listOf(12), fresh.rows("SELECT soot_end FROM regen") { it.getInt(0) })

            assertEquals(listOf("0C" to "0BB8", "DD" to "00C8"), fresh.rows("SELECT lid, hex FROM frame ORDER BY lid") { it.getString(0) to it.getString(1) })
            assertEquals(listOf("dpf_temp" to "DPF too hot"), fresh.rows("SELECT key, text FROM alert") { it.getString(0) to it.getString(1) })

            // The peer tables came too, and Db's cached phone list was dropped so they are actually read again
            assertEquals(listOf("other"), fresh.rows("SELECT device FROM peer_sample") { it.getString(0) })
            assertEquals(listOf(1_500L), fresh.rows("SELECT start_t FROM peer_regen") { it.getLong(0) })
        } finally {
            fresh.close()
        }
    }

    @Test fun aWrongVersionIsRefusedAndLeavesTheTargetAlone() {
        seed(live)
        DatabaseBackup.export(live, snapshot)
        val older = File(dir, "v5.db")
        snapshot.copyTo(older)
        SQLiteDatabase.openDatabase(older.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("PRAGMA user_version = 5")
        }

        val target = Db(context, null)
        try {
            target.sample(9_000, 99, 100, false, 1.0)
            val e = assertThrows(IllegalArgumentException::class.java) { DatabaseBackup.restore(target, older) }
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("another version"))
            assertEquals(listOf(9000L), target.rows("SELECT t FROM sample") { it.getLong(0) })
        } finally {
            target.close()
        }
    }

    @Test fun anEmptyDatabaseExportsAndRestores() {
        DatabaseBackup.export(live, snapshot)
        assertTrue(snapshot.length() > 0)

        val fresh = Db(context, null)
        try {
            assertEquals(DatabaseBackup.RestoreResult(0, 0, 0, 0), DatabaseBackup.restore(fresh, snapshot))
            assertEquals(6, fresh.readableDatabase.version)
        } finally {
            fresh.close()
        }
    }

    @Test fun theSnapshotIsOneFileWithNoJournalOrWal() {
        seed(live)
        DatabaseBackup.export(live, snapshot)
        assertFalse(File(dir, "snapshot.db-journal").exists())
        assertFalse(File(dir, "snapshot.db-wal").exists())
        assertEquals("export leaves the live log alone", 3, live.rows("SELECT t FROM sample") { it.getLong(0) }.size)

        // Restoring replaces, never adds to, what the phone has now
        val fresh = Db(context, null)
        try {
            fresh.sample(9_000, 99, 100, false, 1.0)
            assertEquals(3, DatabaseBackup.restore(fresh, snapshot).readings)
            assertEquals(listOf(1000L, 2000L, 3000L), fresh.rows("SELECT t FROM sample ORDER BY t") { it.getLong(0) })
        } finally {
            fresh.close()
        }
    }

    @Test fun aFileWithoutTheSqliteHeaderIsRefusedBeforeSqliteOpensIt() {
        val junk = File(dir, "notes.db").apply { writeText("not a database") }
        val e = assertThrows(IllegalArgumentException::class.java) { DatabaseBackup.validate(live, junk) }
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("not a SQLite database"))
    }

    @Test fun aHeaderWithNoSchemaIsRefusedAsNotThisAppsDatabase() {
        // A valid SQLite header but nothing behind it: SQLite opens it, then the schema check must refuse it.
        val fake = File(dir, "fake.db").apply {
            outputStream().use { out ->
                out.write("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
                out.write(ByteArray(4096))
            }
        }
        val e = assertThrows(IllegalArgumentException::class.java) { DatabaseBackup.validate(live, fake) }
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("not a Yaris Doctor database"))
    }

    private fun <T> Db.rows(sql: String, read: (Cursor) -> T): List<T> = readableDatabase.rawQuery(sql, null).use { c ->
        val out = ArrayList<T>()
        while (c.moveToNext()) out += read(c)
        out
    }
}
