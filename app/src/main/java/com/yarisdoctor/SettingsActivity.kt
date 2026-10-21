package com.yarisdoctor

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import com.yarisdoctor.Ui.Type

class SettingsActivity : Activity() {

    private val ui by lazy { Ui(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    /** Rebuilt after every change so subtitles always show the current value. */
    private fun render() {
        val content = ui.column().apply {
            setPadding(ui.px(20), 0, ui.px(20), ui.px(40))
            addView(ui.text(Type.DISPLAY_MD, R.color.ink, "Set up the dongle and your car.").apply { setPadding(0, ui.px(8), ui.px(24), ui.px(4)) })
            addView(section("Car"))
            addView(group(
                row("Bluetooth dongle", Prefs.dongleName(context)?.let { "$it · ${Prefs.dongle(context)}" } ?: Prefs.dongle(context)) { pickDongle() },
                toggle("Background logging", "Connect to the dongle automatically and log every drive", Prefs.logging(context)) {
                    Prefs.put(context, Prefs.LOGGING, it)
                    DpfService.restart(context)
                },
                toggle("Regen notification", "Show a live notification while the DPF regenerates", Prefs.alerts(context)) {
                    Prefs.put(context, Prefs.ALERTS, it)
                },
                *listOfNotNull(liveUpdatesRow()).toTypedArray(),
                edgeLightRow(),
                row("Distance units", if (Prefs.km(context)) "Kilometres" else "Miles") { pickUnits() },
            ))
            addView(section("DPF codes"))
            addView(group(
                pidRow("Soot load", Prefs.PID_SOOT, "the soot load (formula gives %)"),
                pidRow("DPF temperature", Prefs.PID_TEMP, "the temperature (formula gives °C)"),
                pidRow("Regeneration flag", Prefs.PID_REGEN, "the regen flag (formula gives 1 during a regen)"),
            ))
            addView(section("Home hub"))
            addView(group(
                row("Driver name", Prefs.driver(context)) {
                    ask("Driver name", "Shown on the hub dashboard next to your trips", Prefs.driver(context)) {
                        if (it.isNotBlank()) Prefs.put(context, Prefs.DRIVER, it.trim().take(40))
                    }
                },
                row("Hub address", Prefs.server(context).ifBlank { "Not set: uploads are off" }) {
                    ask("Hub address", "Your Yaris Doctor Hub on the home network, e.g. 192.168.1.50 (port $DEFAULT_HUB_PORT is assumed)", Prefs.server(context)) {
                        val url = hubUrl(it)
                        if (url != Prefs.server(context)) {
                            // A different hub: send everything again and download from its start; duplicates are ignored both ways
                            Prefs.put(context, Prefs.SYNCED_ROWID, 0L)
                            Prefs.put(context, Prefs.SYNCED_REGENS, null)
                            Prefs.put(context, Prefs.SYNCED_FRAME_ROWID, 0L)
                            Prefs.put(context, Prefs.SYNCED_ALERTS, null)
                            Prefs.put(context, Prefs.HUB_FRAMES, true)
                            Prefs.put(context, Prefs.PULLED_ROWID, 0L)
                        }
                        Prefs.put(context, Prefs.SERVER, url)
                    }
                },
                row("Home Wi-Fi name", Prefs.wifi(context).ifBlank { "Not set: uploads are off" }) {
                    // Pre-fill with the network the phone is on now, the usual answer
                    ask("Home Wi-Fi name", "Uploads only happen on this Wi-Fi network (needs location \"Allow all the time\")",
                        Prefs.wifi(context).ifBlank { Sync.ssid.orEmpty() }) {
                        Prefs.put(context, Prefs.WIFI, it.trim())
                    }
                },
            ))
            addView(syncBlock(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(12) })
            addView(section("Your data"))
            addView(group(row("Export database", "Saves every reading, regen, engine frame and warning to Downloads as a SQLite file") { export() }))
            addView(section("Developer"))
            addView(group(
                toggle("Debug mode", "Simulated dongle with random data, for testing without the car. Nothing is saved, and it turns off when the app is closed or the phone restarts.", Prefs.debug) {
                    Prefs.debug = it
                    if (!it) Db.dropDebug()
                    DpfService.restart(context)
                },
            ))
            addView(section("About"))
            addView(group(row("Version", packageManager.getPackageInfo(packageName, 0).versionName ?: "?")))
        }
        val back = ui.iconButton(R.drawable.ic_back, "Back") { finish() }
        val root = ui.column(ui.topBar("Settings", start = back)).apply {
            addView(ScrollView(this@SettingsActivity).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ui.fitSystemBars(root)
        setContentView(root)
    }

    /** The OBD request for one reading, where its value sits in the reply and how to turn it into a number. Blank = default. */
    private fun pidRow(title: String, key: String, what: String): View {
        val defCode = Prefs.PID_DEFAULTS.getValue(key)
        val defByte = Prefs.BYTE_DEFAULTS.getValue(key)
        val defFormula = Prefs.FORMULA_DEFAULTS.getValue(key)
        val code = Prefs.pid(this, key)
        val byte = Prefs.byte(this, key)
        val formula = Prefs.formula(this, key)
        val isDefault = code == defCode && byte == defByte && formula == defFormula
        val sub = "$code · byte $byte · $formula" + if (isDefault) " (default)" else ""
        return row(title, sub) {
            val codeIn = EditText(this).apply { setText(code); setSingleLine(); hint = defCode }
            val byteIn = EditText(this).apply {
                setText(byte.toString()); setSingleLine(); hint = defByte.toString()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            val formulaIn = EditText(this).apply { setText(formula); setSingleLine(); hint = defFormula }
            fun label(s: String, top: Int = 12) = ui.text(Type.BODY_SM, R.color.muted, s).apply { setPadding(0, ui.px(top), 0, 0) }
            val box = ui.column(
                label("OBD code the app asks the car for (default $defCode)", 0), codeIn,
                label("Byte in the reply where $what starts, counting the reply's first byte as 0 (default $defByte)"), byteIn,
                label("Formula: A is that byte, B the next, then C, D. Use + − * / & and brackets (default $defFormula)"), formulaIn,
                label("Leave a field blank for its default.", 8),
            ).apply { setPadding(ui.px(24), ui.px(8), ui.px(24), 0) }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val newCode = pidCode(codeIn.text.toString(), defCode)
                        ?: return@setPositiveButton toast("Not a valid code: use 2–4 hex bytes starting with 21, 22 or 01 (read requests only), like $defCode")
                    val newByte = bytePos(byteIn.text.toString(), defByte)
                        ?: return@setPositiveButton toast("Not a valid byte: use a number from 0 to 63")
                    val newFormula = formulaText(formulaIn.text.toString(), defFormula)
                        ?: return@setPositiveButton toast("Not a valid formula: use A–D, numbers, + − * / & and brackets, like $defFormula")
                    Prefs.put(this, key, newCode)
                    Prefs.putByte(this, key, newByte)
                    Prefs.putFormula(this, key, newFormula)
                    DpfService.restart(this) // a running session read the old values at connect
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun pickDongle() {
        if (checkSelfPermission(BLUETOOTH_CONNECT) != PERMISSION_GRANTED) {
            toast("Allow \"Nearby devices\" for Yaris Doctor first")
            return
        }
        val devices = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            toast("No paired devices. Pair the dongle in Android's Bluetooth settings first.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Bluetooth dongle")
            .setItems(devices.map { "${it.name ?: "Unknown device"}\n${it.address}" }.toTypedArray()) { _, i ->
                Prefs.put(this, Prefs.DONGLE, devices[i].address)
                Prefs.put(this, Prefs.DONGLE_NAME, devices[i].name)
                DpfService.restart(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Android 16+ on any brand: whether the regen notification may be promoted to a Live Update. Samsung (One UI 8)
     * shows those in the Now Bar; Pixels and others as a status-bar chip.
     */
    private fun liveUpdatesRow(): View? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        val allowed = getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        val where = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) "in the Now Bar" else "as a status bar chip"
        return row("Live Updates", if (allowed) "Regens show live $where" else "Off: tap to allow regens to show live $where") {
            // Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS, which the API 36 SDK doesn't have yet (added in 36.1)
            val promoted = Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            // Not every manufacturer ships that screen: fall back to the app's notification settings instead of crashing
            val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            try { startActivity(promoted) } catch (e: ActivityNotFoundException) { startActivity(fallback) }
        }
    }

    /** On only when both the setting and Android's "Display over other apps" permission are on. */
    private fun edgeLightRow(): View {
        val allowed = Settings.canDrawOverlays(this)
        val sub = if (Prefs.edge(this) && !allowed) "Off: tap and allow \"Display over other apps\""
            else "Coral glow around the screen during a regen, also over Google Maps"
        return toggle("Edge light", sub, Prefs.edge(this) && allowed) { on ->
            Prefs.put(this, Prefs.EDGE, on)
            if (on && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render() // the Live Updates row reflects a change made in system settings
    }

    private fun syncSummary(): String {
        val ok = Prefs.long(this, Prefs.SYNC_OK_AT)
        val last = if (ok == 0L) "Never synced" else "Last synced " +
            android.text.format.DateUtils.getRelativeTimeSpanString(ok, System.currentTimeMillis(), 60_000L)
        return Prefs.str(this, Prefs.SYNC_STATUS)?.let { "$last · $it" } ?: last
    }

    /** Text prompt in the themed dialog; [onOk] gets the entered text. */
    private fun ask(title: String, hint: String, current: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            setSelection(current.length)
        }
        val box = ui.column(ui.text(Type.BODY_SM, R.color.muted, hint), input).apply {
            setPadding(ui.px(24), ui.px(8), ui.px(24), 0)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> onOk(input.text.toString()); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickUnits() {
        AlertDialog.Builder(this)
            .setTitle("Distance units")
            .setSingleChoiceItems(arrayOf("Miles", "Kilometres"), if (Prefs.km(this)) 1 else 0) { dialog, i ->
                Prefs.put(this, Prefs.KM, i == 1)
                dialog.dismiss()
                render()
            }
            .show()
    }

    /** caption-uppercase label above a group. */
    private fun section(title: String) = ui.eyebrow(title).apply {
        setPadding(ui.px(4), ui.px(28), 0, ui.px(10))
    }

    /** Home hub sync: the one orange field in the app. A dot and mono label, the word set huge, a hard black button. */
    private fun syncBlock(): View {
        val set = Prefs.server(this).isNotBlank()
        val button = ui.text(Type.CAPTION_UPPER, R.color.canvas, "Sync now  ↗").apply {
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ui.color(R.color.on_primary))
            setPadding(ui.px(20), 0, ui.px(20), 0)
            minHeight = ui.px(52)
            isEnabled = set
            alpha = if (set) 1f else 0.4f
            ui.pressScale(this)
            setOnClickListener {
                text = "Syncing"
                Thread {
                    val result = Sync.run(applicationContext)
                    runOnUiThread { toast(result); render() }
                }.start()
            }
        }
        return ui.column(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.dot(R.color.on_primary), LinearLayout.LayoutParams(ui.px(8), ui.px(8)).apply { marginEnd = ui.px(10) })
                addView(ui.eyebrow("Home hub · syncs on home Wi-Fi", R.color.on_primary))
            },
            ui.text(Type.NUMERAL, R.color.on_primary, "SYNC").apply { textSize = 88f; setPadding(0, ui.px(16), 0, ui.px(12)) },
            ui.divider(R.color.on_primary),
        ).apply {
            background = ui.shape(R.color.primary, 20)
            setPadding(ui.px(24), ui.px(24), ui.px(24), ui.px(24))
            addView(button, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.px(20) })
            addView(ui.text(Type.BODY_SM, R.color.on_primary, if (set) syncSummary() else "Add the hub address above to turn sync on.").apply { setPadding(0, ui.px(14), 0, 0) })
        }
    }

    /** Rows in one hairline card (connector-tile style), separated by soft dividers. */
    private fun group(vararg rows: View) = ui.column().apply {
        background = ui.shape(R.color.surface_card, 20, R.color.hairline_soft)
        clipToOutline = true
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(ui.divider())
            addView(r)
        }
    }

    /** 64dp-min row; [onClick] null makes it display-only. */
    private fun row(title: String, sub: String, end: View? = null, onClick: (() -> Unit)? = null) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.px(64)
        setPadding(ui.px(20), ui.px(14), ui.px(20), ui.px(14))
        addView(ui.column(
            ui.text(Type.TITLE_SM, R.color.ink, title),
            ui.text(Type.BODY_SM, R.color.muted, sub).apply { setPadding(0, ui.px(2), 0, 0) },
        ), LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) addView(end)
        if (onClick != null) {
            ui.ripple(this)
            setOnClickListener { onClick() }
        }
    }

    private fun toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = Switch(this).apply {
            isChecked = on
            contentDescription = title
            ui.styleSwitch(this)
            setPadding(ui.px(16), 0, 0, 0)
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        return row(title, sub, sw) { sw.toggle() }
    }

    /** Snapshot of the real log (never Debug data) into Downloads; MediaStore needs no storage permission on Android 10+. */
    private fun export() {
        toast("Exporting…")
        Thread {
            val result = try {
                val tmp = java.io.File(cacheDir, "export.db")
                Db.real(this).exportTo(tmp)
                val name = "yaris-doctor-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(System.currentTimeMillis()) + ".db"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.sqlite3")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads refused the file")
                try {
                    contentResolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null) // no half-written file left in Downloads
                    throw e
                } finally {
                    tmp.delete()
                }
                "Saved to Downloads as $name"
            } catch (e: Exception) {
                "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { toast(result) }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
