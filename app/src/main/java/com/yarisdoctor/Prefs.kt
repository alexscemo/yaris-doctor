package com.yarisdoctor

import android.content.Context

object Prefs {
    const val DONGLE = "dongle"
    const val DONGLE_NAME = "dongle_name"
    const val LOGGING = "logging"
    const val ALERTS = "alerts"
    const val EDGE = "edge"
    const val PID_SOOT = "pid_soot"
    const val PID_TEMP = "pid_temp"
    const val PID_REGEN = "pid_regen"
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
    private const val DEVICE_ID = "device_id"

    /** OBD requests per reading, defaulting to the ones DPFViewer found on the owner's Yaris; other years may differ. */
    val PID_DEFAULTS = mapOf(PID_SOOT to "21DD", PID_TEMP to "21F1", PID_REGEN to "21DF")

    /** Byte position of each value in its reply (see sootOf/tempOf/regenOf), keyed like the codes: "<pid key>_byte". */
    val BYTE_DEFAULTS = mapOf(PID_SOOT to 19, PID_TEMP to 8, PID_REGEN to 11)

    /**
     * Formula per reading (see Formula): A = byte at the position, B the next. Regen: "DPNR/DPF Status Reju(PM)" (0 Standby,
     * 1 Ready, 2 Operate, 3 Compl, from the Techstream capture), so A-1 is 1 exactly while it burns.
     */
    val FORMULA_DEFAULTS = mapOf(PID_SOOT to "A*2", PID_TEMP to "(A*256+B)*0.625", PID_REGEN to "A-1")

    /**
     * The regen default used to be DPFViewer's byte 16, A&15, which the Techstream capture showed is "VN Turbo Operation
     * Prohibit". Settings saves every field, so installs that saved that old default still hold it: move them to the new one.
     */
    fun migrate(c: Context) {
        if (byte(c, PID_REGEN) == 16 && formula(c, PID_REGEN) == "A&15") sp(c).edit().remove("${PID_REGEN}_byte").remove("${PID_REGEN}_formula").apply()
    }

    // DPFViewer's dongle, kept as the default so existing installs keep connecting
    private const val DEFAULT_DONGLE = "66:1E:32:7A:2E:72"

    /** Process-lifetime only, never persisted: a reboot or process kill always returns to real logging. */
    @Volatile var debug = false

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun dongle(c: Context) = sp(c).getString(DONGLE, DEFAULT_DONGLE)!!
    fun dongleName(c: Context) = sp(c).getString(DONGLE_NAME, null)
    fun logging(c: Context) = sp(c).getBoolean(LOGGING, true)
    fun alerts(c: Context) = sp(c).getBoolean(ALERTS, true)
    fun pid(c: Context, key: String) = sp(c).getString(key, null) ?: PID_DEFAULTS.getValue(key)
    fun byte(c: Context, key: String) = sp(c).getInt("${key}_byte", BYTE_DEFAULTS.getValue(key))
    fun putByte(c: Context, key: String, value: Int) = sp(c).edit().putInt("${key}_byte", value).apply()
    fun formula(c: Context, key: String) = sp(c).getString("${key}_formula", null) ?: FORMULA_DEFAULTS.getValue(key)
    fun putFormula(c: Context, key: String, value: String) = sp(c).edit().putString("${key}_formula", value).apply()
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
