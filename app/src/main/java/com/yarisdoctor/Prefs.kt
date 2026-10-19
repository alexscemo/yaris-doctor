package com.yarisdoctor

import android.content.Context

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
    fun km(c: Context) =sp(c).getBoolean(KM, false)
    // Both empty by default: nothing is uploaded until the user points the app at their own hub and Wi-Fi
    fun server(c: Context) = sp(c).getString(SERVER, "")!!
    fun wifi(c: Context) = sp(c).getString(WIFI, "")!!
    fun driver(c: Context) = sp(c).getString(DRIVER, null) ?: android.os.Build.MODEL
    fun long(c: Context, key: String) = sp(c).getLong(key, 0L)
    fun str(c: Context, key: String) = sp(c).getString(key, null)

    /** Random per-install id: how the home hub tells phones apart without accounts. */
    @Synchronized fun deviceId(c: Context): String =
        sp(c).getString(DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString().also { put(c, DEVICE_ID, it) }

    fun put(c: Context, key: String, value: Boolean) = sp(c).edit().putBoolean(key, value).apply()
    fun put(c: Context, key: String, value: String?) = sp(c).edit().putString(key, value).apply()
    fun put(c: Context, key: String, value: Long) = sp(c).edit().putLong(key, value).apply()
}
