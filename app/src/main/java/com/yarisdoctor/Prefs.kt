package com.yarisdoctor

import android.content.Context
import android.os.Build
import java.util.UUID

object Prefs {
    const val DONGLE = "dongle"
    const val DONGLE_NAME = "dongle_name"
    const val LOGGING = "logging"
    const val ALERTS = "alerts"
    const val EDGE = "edge"
    const val KM = "km"
    const val SERVER = "server"
    const val WIFI = "wifi"
    const val DRIVER = "driver"
    const val SYNCED_ROWID = "synced_rowid"
    const val SYNCED_REGENS = "synced_regens"
    const val SYNC_STATUS = "sync_status"
    const val SYNC_OK_AT = "sync_ok_at"
    const val SYNC_TRIED_AT = "sync_tried_at"
    const val PULLED_ROWID = "pulled_rowid"
    const val PULLED_AT = "pulled_at"
    const val THINNED_TO = "thinned_to"
    const val SYNCED_FRAME_ROWID = "synced_frame_rowid"
    const val SYNCED_ALERTS = "synced_alerts"
    const val HUB_FRAMES = "hub_frames"
    const val MODEL_2015 = "model_2015"
    const val OIL_AT = "oil_at"
    const val OIL_ODO = "oil_odo"
    private const val DEVICE_ID = "device_id"

    // DPFViewer's dongle, kept as the default so existing installs keep connecting
    private const val DEFAULT_DONGLE = "66:1E:32:7A:2E:72"

    /** Process-lifetime only, never persisted: a reboot or process kill always returns to real logging. */
    @Volatile var debug = false

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun dongle(c: Context) = sp(c).getString(DONGLE, DEFAULT_DONGLE)!!
    fun dongleName(c: Context) = sp(c).getString(DONGLE_NAME, null)
    fun logging(c: Context) = sp(c).getBoolean(LOGGING, true)
    fun alerts(c: Context) = sp(c).getBoolean(ALERTS, true)
    fun edge(c: Context) = sp(c).getBoolean(EDGE, false) // off by default: it needs "Display over other apps"
    fun km(c: Context) = sp(c).getBoolean(KM, false)
    /** Metres per shown distance unit, and its label, per the KM setting. */
    fun unit(c: Context) = if (km(c)) 1000.0 to "km" else Db.METRES_PER_MILE to "mi"
    /** 2015-or-later Yaris: Toyota's 2015+ soot scale and regen temperature range. Off = 2011-2014, the owner's car. */
    fun model2015(c: Context) = sp(c).getBoolean(MODEL_2015, false)
    /** Metres on the real log's GPS odometer since the oil change recorded in Settings (OIL_AT, OIL_ODO), null if none. */
    fun oilMetres(c: Context): Double? = long(c, OIL_AT).takeIf { it > 0 }?.let { Db.real(c).odometer() - long(c, OIL_ODO) }
    // Both empty by default: nothing is uploaded until the user points the app at their own hub and Wi-Fi
    fun server(c: Context) = sp(c).getString(SERVER, "")!!
    fun wifi(c: Context) = sp(c).getString(WIFI, "")!!
    fun driver(c: Context) = sp(c).getString(DRIVER, null) ?: Build.MODEL
    fun long(c: Context, key: String) = sp(c).getLong(key, 0L)
    fun bool(c: Context, key: String, default: Boolean) = sp(c).getBoolean(key, default)
    fun str(c: Context, key: String) = sp(c).getString(key, null)

    /** Random per-install id: how the home hub tells phones apart without accounts. */
    @Synchronized fun deviceId(c: Context): String =
        sp(c).getString(DEVICE_ID, null) ?: UUID.randomUUID().toString().also { put(c, DEVICE_ID, it) }

    fun put(c: Context, key: String, value: Boolean) = sp(c).edit().putBoolean(key, value).apply()
    fun put(c: Context, key: String, value: String?) = sp(c).edit().putString(key, value).apply()
    fun put(c: Context, key: String, value: Long) = sp(c).edit().putLong(key, value).apply()
}
