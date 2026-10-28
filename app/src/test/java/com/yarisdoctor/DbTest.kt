package com.yarisdoctor

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yarisdoctor.domain.drive.Poller
import com.yarisdoctor.domain.health.Finding
import com.yarisdoctor.domain.model.TRIP_GAP_MS
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The destructive and history-wide Db paths, against the real schema and SQL in memory. All times are fixed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DbTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun db() = Db(ctx, null)

    /** Every phone's regens and the car distance between them, checked against a plain reference computed from the fixtures. */
    @Test fun regensMatchAReferenceComputationAcrossTwoPhones() {
        val db = db()
        db.regenEnd(db.regenStart(B + 10 * MIN, 0.0, 80), B + 20 * MIN, 5_000.0, 20)
        db.regenEnd(db.regenStart(B + 3 * HOUR, 20_000.0, 75), B + 3 * HOUR + 10 * MIN, 26_000.0, 15)
        db.regenEnd(db.regenStart(B + 10 * HOUR, 40_000.0, 90), B + 10 * HOUR + 12 * MIN, 45_000.0, 10)

        val own = listOf(
            B to 0.0,
            B + 15 * MIN to 5_000.0,
            B + 45 * MIN to 12_000.0,
            B + 2 * HOUR to 20_000.0,
            B + 3 * HOUR + 5 * MIN to 26_000.0,
            B + 4 * HOUR to 30_000.0,
            B + 9 * HOUR to 40_000.0,
            B + 10 * HOUR + 5 * MIN to 45_000.0,
            B + 20 * HOUR to 45_000.0, // parked between the last two regens: no progress at all
            B + 21 * HOUR to 50_000.0,
        )
        for ((t, odo) in own) db.sample(t, 50, 250, false, odo)

        // Another phone's log, with its own odometer scale and two regens of its own
        val peer = listOf(
            B + 5 * MIN to 0.0,
            B + HOUR to 3_000.0,
            B + 2 * HOUR + 5 * MIN to 9_000.0,
            B + 3 * HOUR + 30 * MIN to 15_000.0,
            B + 10 * HOUR to 15_000.0,
            B + 19 * HOUR to 15_000.0,
            B + 20 * HOUR + 30 * MIN to 17_000.0,
        )
        val samples = JSONArray()
        peer.forEachIndexed { i, (t, odo) -> samples.put(JSONArray().put("P2").put(i + 1).put(t).put(50).put(250).put(0).put(odo)) }
        db.putPeers(samples, JSONArray()
            .put(JSONArray().put("P2").put(1).put(B + 2 * HOUR).put(B + 2 * HOUR + 11 * MIN).put(70).put(30))
            .put(JSONArray().put("P2").put(2).put(B + 20 * HOUR).put(B + 20 * HOUR + 9 * MIN).put(75).put(40)))

        fun odoAt(rows: List<Pair<Long, Double>>, t: Long) = rows.lastOrNull { it.first <= t }?.second ?: rows.first().second
        fun gap(t0: Long, t1: Long) = listOf(own, peer).sumOf { maxOf(0.0, odoAt(it, t1) - odoAt(it, t0)) }

        val expected = listOf(
            Triple(B + 10 * MIN, B + 20 * MIN, null as Double?),
            Triple(B + 2 * HOUR, B + 2 * HOUR + 11 * MIN, gap(B + 20 * MIN, B + 2 * HOUR)),
            Triple(B + 3 * HOUR, B + 3 * HOUR + 10 * MIN, gap(B + 2 * HOUR + 11 * MIN, B + 3 * HOUR)),
            Triple(B + 10 * HOUR, B + 10 * HOUR + 12 * MIN, gap(B + 3 * HOUR + 10 * MIN, B + 10 * HOUR)),
            Triple(B + 20 * HOUR, B + 20 * HOUR + 9 * MIN, gap(B + 10 * HOUR + 12 * MIN, B + 20 * HOUR)),
        )
        val actual = db.regens()
        assertEquals(expected.map { it.first to it.second }, actual.map { it.start to it.end })
        expected.forEachIndexed { i, (_, _, g) ->
            if (g == null) assertNull(actual[i].gapM) else assertEquals(g, actual[i].gapM!!, 1e-9)
        }
        assertEquals(0.0, actual[4].gapM!!, 1e-9) // both phones were parked: an idle gap counts nothing
        assertEquals(7_000.0, db.metresSinceRegen()!!, 1e-9) // 5 km here + 2 km there since the last regen ended
    }

    @Test fun tripStartIsTheFirstFrameOfTheNewestRun() {
        val db = db()
        for (t in listOf(B, B + 10_000, B + 20_000, B + 10 * MIN, B + 10 * MIN + 5_000)) db.frame(t, "0C")
        assertEquals(B + 10 * MIN, db.tripStart())
    }

    @Test fun deleteTripsRemovesOnlyTheSelectedWindowAndKeepsOpenAlerts() {
        val db = db()
        // Two trips: B..B+2m and B+1h..B+1h+1m
        for (t in listOf(B, B + MIN, B + 2 * MIN, B + HOUR, B + HOUR + MIN)) db.sample(t, 50, 250, false, t - B.toDouble())
        for (t in listOf(B, B + 3 * MIN, B + 30 * MIN, B + HOUR)) db.frame(t, "0C")
        db.syncAlerts(B + MIN, listOf(Finding("keep", 1, "still on")))
        db.syncAlerts(B + 2 * MIN, listOf(Finding("keep", 1, "still on"), Finding("gone", 1, "ended")))
        db.syncAlerts(B + 3 * MIN, listOf(Finding("keep", 1, "still on")))

        val trips = db.trips()
        assertEquals(2, trips.size)
        val oldest = trips.single { it.start == B }
        val (maxSample, maxFrame) = db.deleteTrips(listOf(oldest))

        assertEquals(listOf(B + HOUR, B + HOUR + MIN), db.times("SELECT t FROM sample ORDER BY t"))
        assertEquals(listOf(B + 30 * MIN, B + HOUR), db.times("SELECT t FROM frame ORDER BY t"))
        assertEquals(listOf("keep"), db.alerts(0).map { it.key }) // a warning still open stays
        assertEquals(db.maxSampleRowid(), maxSample)
        assertEquals(db.maxFrameRowid(), maxFrame)
    }

    @Test fun closeOrphanRegensEndsAnOpenRegenAtItsLastRegenReading() {
        val db = db()
        val open = db.regenStart(B, 1_000.0, 80)
        db.sample(B, 75, 300, true, 1_100.0)
        db.sample(B + MIN, 60, 600, true, 1_500.0)
        db.sample(B + 2 * MIN, 50, 550, true, 1_800.0)
        db.sample(B + 5 * MIN, 45, 250, false, 1_900.0) // ignition off: not a regen reading
        val later = db.regenStart(B + 10 * MIN, 2_000.0, 70)
        db.sample(B + 11 * MIN, 60, 600, true, 2_100.0)
        db.regenEnd(later, B + 20 * MIN, 3_000.0, 20)

        db.closeOrphanRegens()

        val ended = db.readableDatabase.rawQuery(
            "SELECT end_t, odo_end, soot_end FROM regen WHERE id = ?", arrayOf(open.toString()),
        ).use { it.moveToFirst(); Triple(it.getLong(0), it.getDouble(1), it.getInt(2)) }
        assertEquals(B + 2 * MIN, ended.first)
        assertEquals(1_800.0, ended.second, 1e-9)
        assertEquals(50, ended.third)
        val closed = db.readableDatabase.rawQuery(
            "SELECT end_t, soot_end FROM regen WHERE id = ?", arrayOf(later.toString()),
        ).use { it.moveToFirst(); it.getLong(0) to it.getInt(1) }
        assertEquals(B + 20 * MIN to 20, closed) // an already closed row is left alone
    }

    @Test fun thinFramesKeepsAWeekFullThenOneAMinuteThenOneAnHour() {
        val db = db()
        Prefs.put(ctx, Prefs.THINNED_TO, 0L)
        Prefs.put(ctx, Prefs.THINNED_HOURLY_TO, 0L)

        // Last 7 days: untouched, however many rows share a minute
        val recent = B - DAY
        for (i in 0 until 10) db.frame(recent + i * 2_000, "0C")

        // 7-90 days: one per lid per minute, lowest rowid of the bucket
        val minute20 = (B - 20 * DAY) / MIN * MIN
        for (i in 0 until 5) db.frame(minute20 + i * 10_000, "0C")
        for (i in 0 until 3) db.frame(minute20 + MIN + i * 10_000, "0C")
        db.frame(minute20, Poller.ASH_RECORD, "A")
        db.frame(minute20 + 5_000, Poller.ASH_RECORD, "B")
        db.frame(minute20, Poller.CAL_ID, "C")

        // Older than 90 days: one per lid per hour, the two minutes of an hour together count as one bucket
        val hour100 = (B - 100 * DAY) / HOUR * HOUR
        for (i in 0 until 5) db.frame(hour100 + i * 10_000, "0C")
        for (i in 0 until 3) db.frame(hour100 + 30 * MIN + i * 10_000, "0C")
        for (i in 0 until 2) db.frame(hour100 + HOUR + i * 10_000, "0C")

        db.thinFrames(B)

        assertEquals(10, db.count("SELECT COUNT(*) FROM frame WHERE t >= ?", arrayOf((B - 7 * DAY).toString())))
        assertEquals(2, db.count("SELECT COUNT(*) FROM frame WHERE lid = '0C' AND t >= ? AND t < ?",
            arrayOf((B - 90 * DAY).toString(), (B - 7 * DAY).toString())))
        assertEquals(listOf(minute20, minute20 + MIN), db.times("SELECT t FROM frame WHERE lid = '0C' AND t >= ? AND t < ? ORDER BY t",
            arrayOf((B - 90 * DAY).toString(), (B - 7 * DAY).toString())))
        assertEquals(1, db.count("SELECT COUNT(*) FROM frame WHERE lid = ?", arrayOf(Poller.ASH_RECORD)))
        assertEquals("A", db.string("SELECT hex FROM frame WHERE lid = ?", arrayOf(Poller.ASH_RECORD))) // the first row of the bucket is kept
        assertEquals(1, db.count("SELECT COUNT(*) FROM frame WHERE lid = ?", arrayOf(Poller.CAL_ID)))
        assertEquals(listOf(hour100, hour100 + HOUR), db.times("SELECT t FROM frame WHERE t < ? ORDER BY t", arrayOf((B - 90 * DAY).toString())))
        assertEquals(16, db.count("SELECT COUNT(*) FROM frame"))
        assertEquals(B - 7 * DAY, Prefs.long(ctx, Prefs.THINNED_TO))
        assertEquals(B - 90 * DAY, Prefs.long(ctx, Prefs.THINNED_HOURLY_TO))

        // A second run, even with the watermarks cleared, deletes nothing: the same row of each bucket survives
        Prefs.put(ctx, Prefs.THINNED_TO, 0L)
        Prefs.put(ctx, Prefs.THINNED_HOURLY_TO, 0L)
        db.thinFrames(B)
        assertEquals(16, db.count("SELECT COUNT(*) FROM frame"))
    }

    @Test fun putPeersIgnoresFutureDatedRows() {
        val db = db()
        val future = System.currentTimeMillis() + DAY
        val samples = JSONArray()
            .put(JSONArray().put("P2").put(1).put(B).put(40).put(250).put(0).put(1_000.0))
            .put(JSONArray().put("P2").put(2).put(future).put(10).put(250).put(1).put(999_999.0))
        val regens = JSONArray()
            .put(JSONArray().put("P2").put(1).put(B + HOUR).put(B + 2 * HOUR).put(60).put(20))
            .put(JSONArray().put("P2").put(2).put(future).put(JSONObject.NULL).put(70).put(JSONObject.NULL))
            .put(JSONArray().put("P2").put(3).put(B + 3 * HOUR).put(future).put(80).put(30))

        db.putPeers(samples, regens)

        assertEquals(1, db.count("SELECT COUNT(*) FROM peer_sample"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM peer_regen"))
        assertEquals(B, db.lastSample()?.t) // a future row would win lastSample() forever
        assertEquals(listOf(B + HOUR), db.regens().map { it.start })
    }

    @Test fun putPeersDropsTripsAnotherPhoneDeleted() {
        val db = db()
        fun row(dev: String, id: Int, t: Long) = JSONArray().put(dev).put(id).put(t).put(40).put(250).put(0).put(1_000.0)
        db.putPeers(JSONArray().put(row("P2", 1, B)).put(row("P2", 2, B + HOUR)).put(row("P3", 1, B)), JSONArray())

        db.putPeers(JSONArray(), JSONArray(), JSONArray().put(JSONArray().put("P2").put(B - 1).put(B + 1)))

        assertEquals("P2 ${B + HOUR},P3 $B", db.string("SELECT GROUP_CONCAT(x) FROM (SELECT device || ' ' || t AS x FROM peer_sample ORDER BY device, t)"))
    }

    /** One stored frame, as [Db.frames] receives a cycle's replies. */
    private fun Db.frame(t: Long, lid: String, hex: String = "0000") =
        frames(t, mapOf((if (lid.length == 2) "21$lid" else lid) to "0000$hex"))

    private fun Db.count(sql: String, args: Array<String>? = null): Int =
        readableDatabase.rawQuery(sql, args).use { it.moveToFirst(); it.getInt(0) }

    private fun Db.string(sql: String, args: Array<String>? = null): String =
        readableDatabase.rawQuery(sql, args).use { it.moveToFirst(); it.getString(0) }

    private fun Db.times(sql: String, args: Array<String>? = null): List<Long> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Long>()
            while (c.moveToNext()) out += c.getLong(0)
            out
        }

    companion object {
        private const val MIN = 60_000L
        private const val HOUR = 3_600_000L
        private const val DAY = 86_400_000L
        /** A 2023 instant: well before "now" for putPeers' future check, and fixed for every assertion. */
        private const val B = 1_700_000_000_000L
    }
}

/** The Overview's 5 s refresh only re-reads what changed: the regen list, its distance and the soot trend are cached. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverviewCacheTest {

    @Test fun keepsTheCostlyReadsUntilARegenChanges() {
        Prefs.debug = false
        val app: Application = ApplicationProvider.getApplicationContext()
        val db = app.container.real
        val now = System.currentTimeMillis()
        db.sample(now - 2 * HOUR, 50, 250, false, 0.0)
        db.regenEnd(db.regenStart(now - 90 * MIN, 0.0, 80), now - 80 * MIN, 1_000.0, 20)
        val vm = OverviewViewModel(app)

        val first = runBlocking { vm.load() }
        assertEquals(1, first.regens.size)
        assertEquals(1, first.sootSeries.size)

        // A reading that adds or closes no regen: the cached list and series are reused, not rebuilt
        db.sample(now - 30 * MIN, 60, 400, false, 2_000.0)
        val second = runBlocking { vm.load() }
        assertSame(first.regens, second.regens)
        assertSame(first.sootSeries, second.sootSeries)
        assertEquals(60, second.last?.soot) // lastSample() is still read on every refresh

        // A new regen changes the signature: the very next load rebuilds
        db.regenEnd(db.regenStart(now - 10 * MIN, 3_000.0, 70), now - 5 * MIN, 4_000.0, 30)
        val third = runBlocking { vm.load() }
        assertEquals(2, third.regens.size)
        assertEquals(2, third.sootSeries.size)
    }

    private companion object {
        private const val MIN = 60_000L
        private const val HOUR = 3_600_000L
    }
}
