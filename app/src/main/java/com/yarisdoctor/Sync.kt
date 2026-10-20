package com.yarisdoctor

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
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

    /** Current Wi-Fi SSID, kept by [watch]; null when not on Wi-Fi or the name is hidden (no location permission). */
    @Volatile var ssid: String? = null
        private set

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
        try { c.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) } catch (e: IllegalArgumentException) {}
    }

    /** Called by the service once a minute while idle: uploads only on the home Wi-Fi, only when there is something new. */
    fun maybe(c: Context) {
        val home = Prefs.wifi(c)
        if (Prefs.server(c).isBlank() || home.isBlank() || ssid != home) return
        val db = Db.real(c)
        val pending = db.maxSampleRowid() > Prefs.long(c, Prefs.SYNCED_ROWID) || db.regenSignature() != Prefs.str(c, Prefs.SYNCED_REGENS) ||
            // A hub from before frames and alerts can't take them: don't sync every minute for them (the 10-min pull rechecks)
            (Prefs.bool(c, Prefs.HUB_FRAMES, true) &&
                (db.maxFrameRowid() > Prefs.long(c, Prefs.SYNCED_FRAME_ROWID) || alertSignature(db) != Prefs.str(c, Prefs.SYNCED_ALERTS)))
        // Other phones' drives reach the hub whenever they get home, so check for them now and then even with nothing to send
        val pullDue = System.currentTimeMillis() - Prefs.long(c, Prefs.PULLED_AT) > PULL_MS
        if (!pending && !pullDue) return
        val failedRecently = Prefs.long(c, Prefs.SYNC_TRIED_AT) > Prefs.long(c, Prefs.SYNC_OK_AT) &&
            System.currentTimeMillis() - Prefs.long(c, Prefs.SYNC_TRIED_AT) < RETRY_MS
        if (!failedRecently) run(c)
    }

    /** Uploads everything new. Returns a short status for Settings; never throws. */
    @Synchronized fun run(c: Context): String {
        val wake = c.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaris:sync")
        wake.acquire(10 * 60_000L) // first upload of months of data takes a while
        Prefs.put(c, Prefs.SYNC_TRIED_AT, System.currentTimeMillis())
        val status = try {
            val db = Db.real(c)
            val signature = db.regenSignature()
            val alertSig = alertSignature(db)
            var after = Prefs.long(c, Prefs.SYNCED_ROWID)
            var sent = 0
            var regens: JSONArray? = db.regenRows() // with the first batch only
            var hubHasFrames = false
            while (true) {
                val batch = db.samplesAfter(after, BATCH)
                if (batch.length() == 0 && regens == null) break
                val first = regens != null
                val reply = request(c, "/api/sync", JSONObject()
                    .put("device_id", Prefs.deviceId(c))
                    .put("name", Prefs.driver(c))
                    .put("samples", batch)
                    .put("regens", regens ?: JSONArray())
                    .apply { if (first) put("alerts", db.alertRows(System.currentTimeMillis() - ALERT_DAYS_MS)).put("defs", pidDefs()) })
                if (first) {
                    hubHasFrames = reply.has("frames") // a hub from before frames ignores them: keep them for an updated one
                    Prefs.put(c, Prefs.HUB_FRAMES, hubHasFrames)
                }
                regens = null
                if (batch.length() > 0) {
                    after = batch.getJSONArray(batch.length() - 1).getLong(0)
                    Prefs.put(c, Prefs.SYNCED_ROWID, after) // progress survives a dropped connection mid-upload
                    sent += batch.length()
                }
                if (batch.length() < BATCH) break
            }
            Prefs.put(c, Prefs.SYNCED_REGENS, signature)
            // Raw engine frames: the bulk of the data, so capped per run like the pull below; the rest goes next minute
            var frameAfter = Prefs.long(c, Prefs.SYNCED_FRAME_ROWID)
            var framePages = 0
            while (hubHasFrames && framePages++ < MAX_PAGES) {
                val frames = db.framesAfter(frameAfter, BATCH)
                if (frames.length() == 0) break
                request(c, "/api/sync", JSONObject().put("device_id", Prefs.deviceId(c)).put("name", Prefs.driver(c)).put("frames", frames))
                frameAfter = frames.getJSONArray(frames.length() - 1).getLong(0)
                Prefs.put(c, Prefs.SYNCED_FRAME_ROWID, frameAfter)
                if (frames.length() < BATCH) break
            }
            if (hubHasFrames) Prefs.put(c, Prefs.SYNCED_ALERTS, alertSig)
            // Then download what the other phones logged, so this phone's screen shows the whole car
            var cursor = Prefs.long(c, Prefs.PULLED_ROWID)
            var got = 0
            var pages = 0
            do {
                val prev = cursor
                val page = request(c, "/api/export?device=${Prefs.deviceId(c)}&after=$cursor", null)
                val samples = page.getJSONArray("samples")
                db.putPeers(samples, page.getJSONArray("regens"))
                cursor = page.getLong("next")
                Prefs.put(c, Prefs.PULLED_ROWID, cursor)
                got += samples.length()
                // This runs on the logger's thread: a hub whose cursor stalls or never ends must not stop logging
            } while (page.getBoolean("more") && cursor > prev && ++pages < MAX_PAGES)
            Prefs.put(c, Prefs.PULLED_AT, System.currentTimeMillis())
            Prefs.put(c, Prefs.SYNC_OK_AT, System.currentTimeMillis())
            "Sent $sent new readings, received $got from other phones"
        } catch (e: Exception) {
            Log.d(TAG, "sync failed: $e")
            "Hub not reachable (${e.javaClass.simpleName})"
        } finally {
            if (wake.isHeld) wake.release()
        }
        Prefs.put(c, Prefs.SYNC_STATUS, status)
        return status
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

/** "192.168.1.50" → "http://192.168.1.50:8765"; a scheme or port the user typed is kept. Blank stays blank (= sync off). */
fun hubUrl(raw: String): String {
    val s = raw.trim().trimEnd('/')
    if (s.isEmpty()) return ""
    val withScheme = if ("://" in s) s else "http://$s"
    return if (Regex("""^\w+://[^/]+:\d+""").containsMatchIn(withScheme)) withScheme
    else withScheme.replaceFirst(Regex("""^(\w+://[^/:]+)"""), "$1:$DEFAULT_HUB_PORT")
}
