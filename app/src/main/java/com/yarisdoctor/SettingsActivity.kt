package com.yarisdoctor

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
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
        render()
    }

    /** Rebuilt after every change so subtitles always show the current value. */
    private fun render() {
        val content = ui.column().apply {
            setPadding(ui.px(16), 0, ui.px(16), ui.px(32))
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
                row("Distance units", if (Prefs.km(context)) "Kilometres" else "Miles") { pickUnits() },
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
                        Prefs.put(context, Prefs.SERVER, hubUrl(it))
                    }
                },
                row("Home Wi-Fi name", Prefs.wifi(context).ifBlank { "Not set: uploads are off" }) {
                    // Pre-fill with the network the phone is on now, the usual answer
                    ask("Home Wi-Fi name", "Uploads only happen on this Wi-Fi network (needs location \"Allow all the time\")",
                        Prefs.wifi(context).ifBlank { Sync.ssid.orEmpty() }) {
                        Prefs.put(context, Prefs.WIFI, it.trim())
                    }
                },
                row("Sync now", syncSummary()) {
                    if (Prefs.server(context).isBlank()) return@row toast("Set the hub address first")
                    toast("Syncing…")
                    Thread {
                        val result = Sync.run(applicationContext)
                        runOnUiThread { toast(result); render() }
                    }.start()
                },
            ))
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
    private fun section(title: String) = ui.text(Type.CAPTION_UPPER, R.color.muted, title).apply {
        setPadding(ui.px(4), ui.px(24), 0, ui.px(8))
    }

    /** Rows in one hairline card (connector-tile style), separated by soft dividers. */
    private fun group(vararg rows: View) = ui.column().apply {
        background = ui.shape(R.color.canvas, 12, R.color.hairline)
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
        setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(12))
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
            setPadding(ui.px(16), 0, 0, 0)
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        return row(title, sub, sw) { sw.toggle() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
