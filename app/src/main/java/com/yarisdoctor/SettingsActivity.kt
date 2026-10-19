package com.yarisdoctor

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
