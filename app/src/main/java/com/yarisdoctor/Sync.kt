package com.yarisdoctor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.yarisdoctor.domain.model.Trip
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads the real log (never Debug data) to a Yaris Doctor Hub (github.com/alexscemo/yaris-doctor-hub) while on the
 * home Wi-Fi set in Settings. The hub ignores rows it already has, so a failed or repeated upload is harmless; we only
 * remember how far the hub has confirmed.
 */
object Sync {
    private const val BATCH = 2000
    private const val RETRY_MS = 15 * 60_000L // after a failure (away from home, hub down), don't retry every minute
    private const val PULL_MS = 10 * 60_000L
    private const val MAX_PAGES = 50 // per run; the rest comes on the next pull, from the saved cursor
    private const val TAG = "Sync"
    private const val ALERT_DAYS_MS = 30 * 86_400_000L
    private const val CH_SYNC = "sync"
    private const val ID_SYNC = 4 // after DpfService's 1 (service), 2 (regen) and 3 (drive finished)

    /** Current Wi-Fi SSID, kept by [watch]; null when not on Wi-Fi or the name is hidden (no location permission). */
    @Volatile var ssid: String? = null
        internal set // written by [wifiCallback]; internal only so a unit test can stand in for the callback

    /** A run is in progress right now; the service must not stop itself for a pause at home mid-upload. */
    @Volatile private var busy = false

    fun isRunning() = busy

    // SSIDs are location data: only a callback with FLAG_INCLUDE_LOCATION_INFO sees them (needs fine + background location)
    private val wifiCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            ssid = (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")?.takeIf { it != WifiManager.UNKNOWN_SSID }
        }

        override fun onLost(network: Network) {
            ssid = null
        }
    }

    fun watch(c: Context) {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        // Already registered (a service restart racing its predecessor) must not take the logger down
        try { c.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, wifiCallback) } catch (e: IllegalArgumentException) {}
    }

    fun unwatch(c: Context) {
        // Nothing reports a change from now on: a stale "home" would pause the next service instance wherever it is
        ssid = null
        try { c.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) } catch (e: IllegalArgumentException) {}
    }

    /** On the Wi-Fi named in Settings, as [watch] last saw it. */
    fun atHome(c: Context) = Prefs.wifi(c).let { it.isNotBlank() && ssid == it }

    /** This phone has readings, regens, frames or warnings the hub hasn't confirmed; false when no hub is set. */
    fun pending(c: Context): Boolean {
        if (Prefs.server(c).isBlank()) return false
        val db = c.container.real
        return db.maxSampleRowid() > Prefs.long(c, Prefs.SYNCED_ROWID) || db.regenSignature() != Prefs.str(c, Prefs.SYNCED_REGENS) ||
            // A hub from before frames and alerts can't take them: don't sync every minute for them (the 10-min pull rechecks)
            (Prefs.bool(c, Prefs.HUB_FRAMES, true) &&
                (db.maxFrameRowid() > Prefs.long(c, Prefs.SYNCED_FRAME_ROWID) || alertSignature(db) != Prefs.str(c, Prefs.SYNCED_ALERTS))) ||
            // Likewise deleted trips wait for a hub that can remove them
            (Prefs.bool(c, Prefs.HUB_DELETES, true) && Prefs.str(c, Prefs.PENDING_DELETES) != null)
    }

    /** Called by the service once a minute while idle: uploads only on the home Wi-Fi, only when there is something new. */
    fun maybe(c: Context) {
        if (Prefs.server(c).isBlank() || !atHome(c)) return
        val pending = pending(c)
        // Other phones' drives reach the hub whenever they get home, so check for them now and then even with nothing to send
        val pullDue = System.currentTimeMillis() - Prefs.long(c, Prefs.PULLED_AT) > PULL_MS
        if (!pending && !pullDue) return
        val failedRecently = Prefs.long(c, Prefs.SYNC_TRIED_AT) > Prefs.long(c, Prefs.SYNC_OK_AT) &&
            System.currentTimeMillis() - Prefs.long(c, Prefs.SYNC_TRIED_AT) < RETRY_MS
        if (!failedRecently) run(c, notify = true)
    }

    /**
     * Uploads everything new, then downloads the other phones' rows. Returns a short status for Settings; never throws.
     * [notify]: the automatic sync on the home Wi-Fi tells the user when it uploaded something ("Sync now" shows a toast instead).
     * A run is time-boxed by [SYNC_BUDGET_MS]: it stops cleanly between pages when the budget is gone and says so, and
     * [Prefs.SYNC_OK_AT] only moves once the pull completed, so a cut-short run is retried instead of being called done.
     */
    @Synchronized fun run(c: Context, notify: Boolean = false): String {
        busy = true
        try {
            val wake = c.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaris:sync")
            wake.acquire(SYNC_BUDGET_MS + 60_000L) // the budget bounds the work; the lock only has to outlive it
            Prefs.put(c, Prefs.SYNC_TRIED_AT, System.currentTimeMillis())
            val startedAt = System.currentTimeMillis()
            var cutShort = false
            val status = try {
                val db = c.container.real
                val signature = db.regenSignature()
                val alertSig = alertSignature(db)
                // A hub that never got this phone's warnings (first sync, new address) gets all of them; after that the
                // last 30 days are enough, since older ones are closed and never change
                val alerts = db.alertRows(if (Prefs.str(c, Prefs.SYNCED_ALERTS) == null) 0 else System.currentTimeMillis() - ALERT_DAYS_MS)
                val alertsChanged = alertSig != Prefs.str(c, Prefs.SYNCED_ALERTS)
                var framesSent = 0
                var after = Prefs.long(c, Prefs.SYNCED_ROWID)
                var sent = 0
                var regens: JSONArray? = db.regenRows() // with the first batch only
                var regensSent = false
                var hubHasFrames = false
                val deletes = parseRanges(Prefs.str(c, Prefs.PENDING_DELETES))
                while (true) {
                    // Every page persists its own cursor, so stopping here loses nothing: the next run starts where this one did
                    if (overBudget(startedAt, System.currentTimeMillis())) {
                        cutShort = true
                        break
                    }
                    val batch = db.samplesAfter(after, BATCH)
                    if (batch.length() == 0 && regens == null) break
                    val first = regens != null
                    val reply = request(c, "/api/sync", JSONObject()
                        .put("device_id", Prefs.deviceId(c))
                        .put("name", Prefs.driver(c))
                        .put("samples", batch)
                        .put("regens", regens ?: JSONArray())
                        // regens_all: this is every regen the phone has, so the hub drops ones the phone deleted (engine-stop blips)
                        .apply {
                            if (!first) return@apply
                            put("regens_all", true).put("alerts", alerts).put("defs", pidDefs()).put("rules", ruleDefs()).put("dpf", db.dpfRows())
                            // Trips deleted on the phone: the hub removes this phone's rows in these windows
                            if (deletes.isNotEmpty()) put("deleted", JSONArray(deletes.map { JSONArray().put(it.first).put(it.last) }))
                        })
                    if (first) {
                        hubHasFrames = reply.has("frames") // a hub from before frames ignores them: keep them for an updated one
                        Prefs.put(c, Prefs.HUB_FRAMES, hubHasFrames)
                        // Same for deletes: an older hub ignores them, so they stay queued for an updated one ([queueDeletes] can't run meanwhile)
                        val hubDeletes = reply.has("deleted")
                        Prefs.put(c, Prefs.HUB_DELETES, hubDeletes)
                        if (hubDeletes && deletes.isNotEmpty()) Prefs.put(c, Prefs.PENDING_DELETES, null)
                    }
                    regens = null
                    regensSent = true // the hub has this phone's whole regen list now
                    if (batch.length() > 0) {
                        after = batch.getJSONArray(batch.length() - 1).getLong(0)
                        Prefs.put(c, Prefs.SYNCED_ROWID, after) // progress survives a dropped connection mid-upload
                        sent += batch.length()
                    }
                    if (batch.length() < BATCH) break
                }
                // Only when the regen list actually reached the hub: a run cut short before its first batch must resend it
                if (regensSent) Prefs.put(c, Prefs.SYNCED_REGENS, signature)
                // Raw engine frames: the bulk of the data, so capped per run like the pull below; the rest goes next time
                var frameAfter = Prefs.long(c, Prefs.SYNCED_FRAME_ROWID)
                var framePages = 0
                while (hubHasFrames && framePages++ < MAX_PAGES) {
                    if (overBudget(startedAt, System.currentTimeMillis())) {
                        cutShort = true
                        break
                    }
                    val frames = db.framesAfter(frameAfter, BATCH)
                    if (frames.length() == 0) break
                    request(c, "/api/sync", JSONObject().put("device_id", Prefs.deviceId(c)).put("name", Prefs.driver(c)).put("frames", frames))
                    frameAfter = frames.getJSONArray(frames.length() - 1).getLong(0)
                    Prefs.put(c, Prefs.SYNCED_FRAME_ROWID, frameAfter)
                    framesSent += frames.length()
                    if (frames.length() < BATCH) break
                }
                if (hubHasFrames) Prefs.put(c, Prefs.SYNCED_ALERTS, alertSig)
                // Then download what the other phones logged, so this phone's screen shows the whole car
                var cursor = Prefs.long(c, Prefs.PULLED_ROWID)
                var got = 0
                var pages = 0
                do {
                    if (overBudget(startedAt, System.currentTimeMillis())) {
                        cutShort = true
                        break
                    }
                    val prev = cursor
                    val page = request(c, "/api/export?device=${Prefs.deviceId(c)}&after=$cursor", null)
                    val samples = page.getJSONArray("samples")
                    db.putPeers(samples, page.getJSONArray("regens"))
                    cursor = page.getLong("next")
                    Prefs.put(c, Prefs.PULLED_ROWID, cursor)
                    got += samples.length()
                    // This runs off the logger's thread: a hub whose cursor stalls or never ends must not stop logging
                } while (page.getBoolean("more") && cursor > prev && ++pages < MAX_PAGES)
                val warnings = if (hubHasFrames && alertsChanged) alerts.length() else 0
                if (!cutShort) {
                    Prefs.put(c, Prefs.PULLED_AT, System.currentTimeMillis())
                    Prefs.put(c, Prefs.SYNC_OK_AT, System.currentTimeMillis()) // the pull completed: not a partial run
                    if (notify && (sent > 0 || framesSent > 0 || warnings > 0)) synced(c, sent, framesSent, warnings)
                    "Sent $sent new readings and $framesSent engine frames, received $got from other phones"
                } else {
                    // No "All synced" notification: what is left is still pending, and the cursors will pick it up next time
                    "Sync paused after ${SYNC_BUDGET_MS / 1000} s (sent $sent readings and $framesSent engine frames); " +
                        "the rest continues next time"
                }
            } catch (e: Exception) {
                Log.d(TAG, "sync failed: $e")
                "Hub not reachable (${e.javaClass.simpleName})"
            } finally {
                if (wake.isHeld) wake.release()
            }
            Prefs.put(c, Prefs.SYNC_STATUS, status)
            return status
        } finally {
            busy = false
        }
    }

    /**
     * Queues deleted trips' windows for the hub to remove at the next sync; nothing to do without a hub (a new address resends
     * everything). [tops] = Db.deleteTrips' highest sample and frame rowids left: new rows will reuse the numbers above them, so
     * the upload cursors go back there. The hub drops the old copies first, in the request that carries the deletes.
     */
    @Synchronized fun queueDeletes(c: Context, trips: List<Trip>, tops: Pair<Long, Long>) {
        if (Prefs.server(c).isBlank() || trips.isEmpty()) return
        Prefs.put(c, Prefs.PENDING_DELETES, formatRanges(parseRanges(Prefs.str(c, Prefs.PENDING_DELETES)) + trips.map { it.start..it.until }))
        Prefs.put(c, Prefs.SYNCED_ROWID, minOf(Prefs.long(c, Prefs.SYNCED_ROWID), tops.first))
        Prefs.put(c, Prefs.SYNCED_FRAME_ROWID, minOf(Prefs.long(c, Prefs.SYNCED_FRAME_ROWID), tops.second))
    }

    /** One quiet notification after an automatic upload, replacing the previous one, so a drive ends with "all synced". */
    private fun synced(c: Context, readings: Int, frames: Int, warnings: Int) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SYNC, "Hub sync", NotificationManager.IMPORTANCE_LOW))
        fun count(n: Int, what: String) = "$n $what${if (n == 1) "" else "s"}".takeIf { n > 0 }
        val parts = listOfNotNull(count(readings, "reading"), count(frames, "engine frame"), count(warnings, "warning"))
        nm.notify(ID_SYNC, Notification.Builder(c, CH_SYNC)
            .setSmallIcon(R.drawable.ic_stat_gauge)
            .setColor(c.getColor(R.color.primary))
            .setContentTitle("All synced with the hub")
            .setContentText("Sent ${parts.joinToString(", ")}")
            .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())
    }

    /** Changes when an alert opens, closes or changes its text: the hub keeps the latest of each row. */
    private fun alertSignature(db: Db) = db.alertRows(System.currentTimeMillis() - ALERT_DAYS_MS).toString().hashCode().toString()

    /** POSTs [body] as JSON, or GETs when it is null; returns the hub's JSON reply. */
    private fun request(c: Context, path: String, body: JSONObject?): JSONObject {
        val hub = Prefs.server(c)
        if (hub.isBlank()) throw IOException("no hub address set")
        val conn = URL("$hub$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3_000
            conn.readTimeout = 30_000
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (conn.responseCode != 200) {
                throw IOException("hub said ${conn.responseCode}: " + (conn.errorStream?.bufferedReader()?.readText() ?: ""))
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}

const val DEFAULT_HUB_PORT = 8765

/**
 * Wall-clock budget for one sync run. A slow hub must neither hold the logger's wake lock nor keep it from probing
 * the dongle for long; a run that hits it resumes from the persisted cursors on the next one.
 */
const val SYNC_BUDGET_MS = 120_000L

/**
 * Whether a run that started at [startedAt] (wall clock, ms) has used its budget by [now]. Checked between pages, so a
 * cut-short run always keeps the cursors it already saved. A clock that jumped backwards does not stop a run for good.
 */
fun overBudget(startedAt: Long, now: Long, budgetMs: Long = SYNC_BUDGET_MS) = now - startedAt >= budgetMs

/** Time windows (ms, inclusive) as "a-b,c-d", how deleted trips wait in Prefs; null when there are none. */
fun formatRanges(ranges: List<LongRange>): String? = ranges.takeIf { it.isNotEmpty() }?.joinToString(",") { "${it.first}-${it.last}" }

/** Reads [formatRanges]' text back; a malformed entry is skipped rather than blocking the rest. */
fun parseRanges(s: String?): List<LongRange> = s.orEmpty().split(",").mapNotNull { part ->
    val (a, b) = part.split("-").takeIf { it.size == 2 } ?: return@mapNotNull null
    val from = a.toLongOrNull() ?: return@mapNotNull null
    val to = b.toLongOrNull() ?: return@mapNotNull null
    from..to
}

/** "192.168.1.50" → "http://192.168.1.50:8765"; a scheme or port the user typed is kept. Blank stays blank (= sync off). */
fun hubUrl(raw: String): String {
    val s = raw.trim().trimEnd('/')
    if (s.isEmpty()) return ""
    val withScheme = if ("://" in s) s else "http://$s"
    return if (Regex("""^\w+://[^/]+:\d+""").containsMatchIn(withScheme)) withScheme
    else withScheme.replaceFirst(Regex("""^(\w+://[^/:]+)"""), "$1:$DEFAULT_HUB_PORT")
}
