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
    private const val TAG = "Sync"

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
        val pending = db.maxSampleRowid() > Prefs.long(c, Prefs.SYNCED_ROWID) || db.regenSignature() != Prefs.str(c, Prefs.SYNCED_REGENS)
        if (!pending) return
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
            var after = Prefs.long(c, Prefs.SYNCED_ROWID)
            var sent = 0
            var regens: JSONArray? = db.regenRows() // with the first batch only
            while (true) {
                val batch = db.samplesAfter(after, BATCH)
                if (batch.length() == 0 && regens == null) break
                post(c, JSONObject()
                    .put("device_id", Prefs.deviceId(c))
                    .put("name", Prefs.driver(c))
                    .put("samples", batch)
                    .put("regens", regens ?: JSONArray()))
                regens = null
                if (batch.length() > 0) {
                    after = batch.getJSONArray(batch.length() - 1).getLong(0)
                    Prefs.put(c, Prefs.SYNCED_ROWID, after) // progress survives a dropped connection mid-upload
                    sent += batch.length()
                }
                if (batch.length() < BATCH) break
            }
            Prefs.put(c, Prefs.SYNCED_REGENS, signature)
            Prefs.put(c, Prefs.SYNC_OK_AT, System.currentTimeMillis())
            "Synced $sent new readings"
        } catch (e: Exception) {
            Log.d(TAG, "sync failed: $e")
            "Hub not reachable (${e.javaClass.simpleName})"
        } finally {
            if (wake.isHeld) wake.release()
        }
        Prefs.put(c, Prefs.SYNC_STATUS, status)
        return status
    }

    private fun post(c: Context, body: JSONObject) {
        val hub = Prefs.server(c)
        if (hub.isBlank()) throw IOException("no hub address set")
        val conn = URL("$hub/api/sync").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                throw IOException("hub said ${conn.responseCode}: " + (conn.errorStream?.bufferedReader()?.readText() ?: ""))
            }
            conn.inputStream.close()
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
