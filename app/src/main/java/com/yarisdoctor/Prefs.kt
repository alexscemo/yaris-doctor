package com.yarisdoctor

import android.content.Context

object Prefs {
    const val DONGLE = "dongle"
    const val DONGLE_NAME = "dongle_name"
    const val LOGGING = "logging"
    const val ALERTS = "alerts"
    const val KM = "km"

    // DPFViewer's dongle, kept as the default so existing installs keep connecting
    private const val DEFAULT_DONGLE = "66:1E:32:7A:2E:72"

    /** Process-lifetime only, never persisted: a reboot or process kill always returns to real logging. */
    @Volatile var debug = false

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun dongle(c: Context) = sp(c).getString(DONGLE, DEFAULT_DONGLE)!!
    fun dongleName(c: Context) = sp(c).getString(DONGLE_NAME, null)
    fun logging(c: Context) = sp(c).getBoolean(LOGGING, true)
    fun alerts(c: Context) = sp(c).getBoolean(ALERTS, true)
    fun km(c: Context) = sp(c).getBoolean(KM, false)

    fun put(c: Context, key: String, value: Boolean) = sp(c).edit().putBoolean(key, value).apply()
    fun put(c: Context, key: String, value: String?) = sp(c).edit().putString(key, value).apply()
}
