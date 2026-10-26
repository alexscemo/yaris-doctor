package com.yarisdoctor

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import com.yarisdoctor.Ui.Type
import com.yarisdoctor.domain.health.OIL_CHANGE_KM
import com.yarisdoctor.domain.model.Trip
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

class SettingsActivity : Activity() {

    private val ui by lazy { Ui(this) }

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
                row("Units", unitsLabel()) { pickUnits() },
                row("Model year", if (Prefs.model2015(context)) "2015 or later" else "2011-2014") { pickModelYear() },
                oilRow(),
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
                            Prefs.put(context, Prefs.HUB_DELETES, true)
                            Prefs.put(context, Prefs.PULLED_ROWID, 0L)
                        }
                        Prefs.put(context, Prefs.SERVER, url)
                    }
                },
                row("Home Wi-Fi name", Prefs.wifi(context).let { if (it.isBlank()) "Not set: uploads are off" else "$it · no dongle search while on it" }) {
                    // Pre-fill with the network the phone is on now, the usual answer
                    ask("Home Wi-Fi name", "Uploads only happen on this Wi-Fi network, and the dongle search (with its notification) pauses while you are on it (needs location \"Allow all the time\")",
                        Prefs.wifi(context).ifBlank { Sync.ssid.orEmpty() }) {
                        Prefs.put(context, Prefs.WIFI, it.trim())
                    }
                },
            ))
            addView(syncBlock(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(12) })
            addView(section("Your data"))
            addView(group(
                row("Back up database", "Saves the whole log to Downloads as one file you can restore here") { backUp() },
                row("Restore database", "Replaces every reading, regen and warning on this phone with a backup") { pickBackup() },
                row("Export database", "Saves the whole log to Downloads as one Markdown file an AI can read") { exportAll() },
                row("Delete trips", "Pick drives to remove from this phone and the hub") { pickTrips() },
            ))
            addView(section("Developer"))
            addView(group(
                toggle("Debug mode", "Simulated dongle with random data, for testing without the car. Nothing is saved, and it turns off when the app is closed or the phone restarts.", Prefs.debug) {
                    Prefs.debug = it
                    if (!it) container.dropDebug()
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
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        oil = null // the odometer and regen count move while the screen is away: read them again
        render() // the Live Updates row reflects a change made in system settings
    }

    private fun syncSummary(): String {
        val ok = Prefs.long(this, Prefs.SYNC_OK_AT)
        val last = if (ok == 0L) "Never synced" else "Last synced " +
            DateUtils.getRelativeTimeSpanString(ok, System.currentTimeMillis(), 60_000L)
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

    private fun unitsLabel() = Prefs.units(this).let {
        if (it.metric) "Metric · km, °C, kPa, km/h" else "Imperial · mi, °F, psi, mph"
    }

    private fun pickUnits() {
        AlertDialog.Builder(this)
            .setTitle("Units")
            .setSingleChoiceItems(arrayOf("Imperial (mi, °F, psi, mph)", "Metric (km, °C, kPa, km/h)"), if (Prefs.km(this)) 1 else 0) { dialog, i ->
                Prefs.put(this, Prefs.KM, i == 1)
                dialog.dismiss()
                render()
            }
            .show()
    }

    /** Toyota's soot scale and regen temperature range changed in 2015; the checks follow this. */
    private fun pickModelYear() {
        AlertDialog.Builder(this)
            .setTitle("Model year")
            .setSingleChoiceItems(arrayOf("2011-2014", "2015 or later"), if (Prefs.model2015(this)) 1 else 0) { dialog, i ->
                Prefs.put(this, Prefs.MODEL_2015, i == 1)
                DpfService.restart(this) // the checks read it at start
                dialog.dismiss()
                render()
            }
            .show()
    }

    /** The oil row's numbers: the GPS odometer and the regens started since the change, both database reads. */
    private class Oil(val at: Long, val metres: Double, val regens: Int)

    /** Filled by [oilRow]'s background read; null until it returns, and cleared by [onResume] for fresh numbers. */
    private var oil: Oil? = null

    /**
     * Records an oil change at today's date and the GPS odometer; the oil_change_due check counts from it. Unlike the
     * other rows this one has to read the log, so its numbers arrive from a background thread.
     */
    private fun oilRow(): View {
        val at = Prefs.long(this, Prefs.OIL_AT)
        val cached = oil?.takeIf { it.at == at }
        val sub = ui.text(Type.BODY_SM, R.color.muted, oilLabel(at, cached)).apply { setPadding(0, ui.px(2), 0, 0) }
        if (at != 0L && cached == null) Thread {
            val fresh = Oil(at, container.real.odometer(), container.real.regens().count { it.start >= at })
            runOnUiThread {
                oil = fresh
                if (!isFinishing) sub.text = oilLabel(at, fresh)
            }
        }.start()
        return row("Oil changed", sub) {
            val interval = Prefs.units(this).distanceFromKmText(OIL_CHANGE_KM.toDouble(), grouped = true)
            AlertDialog.Builder(this)
                .setTitle("Oil changed today?")
                .setMessage("Counts distance and regens from now. Toyota changes the oil every $interval on this engine.")
                .setPositiveButton("Record") { _, _ ->
                    val now = System.currentTimeMillis()
                    Thread {
                        val odo = container.real.odometer().toLong() // off the main thread, like the row's numbers
                        runOnUiThread {
                            Prefs.put(this, Prefs.OIL_AT, now)
                            Prefs.put(this, Prefs.OIL_ODO, odo)
                            DpfService.restart(this) // closes an oil-change warning now
                            render()
                        }
                    }.start()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** "26 Oct 2026 · 1,204 mi, 3 regens since", or just the date while the background read is still running. */
    private fun oilLabel(at: Long, oil: Oil?): String {
        if (at == 0L) return "Never recorded · tap when the oil is changed"
        val date = DateFormat.getDateInstance().format(at)
        if (oil == null) return "$date · reading the log…"
        val units = Prefs.units(this)
        return "%s · %.0f %s, %s since".format(date, units.distance(oil.metres), units.distanceLabel, plural(oil.regens, "regen"))
    }

    /** caption-uppercase label above a group. */
    private fun section(title: String) = ui.eyebrow(title).apply {
        setPadding(ui.px(4), ui.px(28), 0, ui.px(10))
    }

    /** Home hub sync: the one orange field in the app. A dot and mono label, the word set huge, a hard black button. */
    private fun syncBlock(): View {
        val set = Prefs.server(this).isNotBlank()
        // Light text in both themes: the button stays black (on_primary), so canvas (near-black at night) would vanish
        val button = ui.text(Type.CAPTION_UPPER, R.color.on_instrument, "Sync now  ↗").apply {
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
    private fun row(title: String, sub: String, end: View? = null, onClick: (() -> Unit)? = null) =
        row(title, ui.text(Type.BODY_SM, R.color.muted, sub).apply { setPadding(0, ui.px(2), 0, 0) }, end, onClick)

    /** [row] with a subtitle the caller keeps, to fill once a background read returns (see [oilRow]). */
    private fun row(title: String, sub: View, end: View? = null, onClick: (() -> Unit)? = null) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.px(64)
        setPadding(ui.px(20), ui.px(14), ui.px(20), ui.px(14))
        addView(ui.column(
            ui.text(Type.TITLE_SM, R.color.ink, title),
            sub,
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

    /** This phone's trips (the real log, never Debug data), read off the main thread, to delete. */
    private fun pickTrips() {
        Thread {
            val trips = container.real.trips()
            runOnUiThread { if (!isFinishing) showTrips(trips) }
        }.start()
    }

    /** "Thu 22 Oct, 09:12" over "34.2 km · 1 regen · 48 min" (or "In progress"), as a checkbox label. */
    private fun tripLabel(trip: Trip, live: Boolean): CharSequence {
        val units = Prefs.units(this)
        val sub = if (live) "In progress" else
            "%.1f %s · ${plural(trip.regens, "regen")} · ${(trip.end - trip.start) / 60_000} min"
                .format(units.distance(trip.metres), units.distanceLabel)
        return SpannableStringBuilder(fmt(trip.start)).append("\n").apply {
            val from = length
            append(sub)
            setSpan(ForegroundColorSpan(ui.color(R.color.muted)), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(Type.BODY_SM.sp / Type.TITLE_SM.sp), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Trip list with "Select all" and a red Delete. The trip still being logged can't be picked. */
    private fun showTrips(trips: List<Trip>) {
        if (trips.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Delete trips").setMessage("No trips logged yet.").setPositiveButton("OK", null).show()
            return
        }
        val now = System.currentTimeMillis()
        var syncing = false // set while "Select all" ticks the rows, so their listener doesn't untick it back
        fun box(label: CharSequence) = CheckBox(this).apply {
            text = label
            textSize = Type.TITLE_SM.sp
            ui.style(this, Type.TITLE_SM)
            setTextColor(ui.color(R.color.ink))
            minHeight = ui.px(56)
        }
        val all = box("Select all")
        val boxes = trips.map { trip ->
            val live = trip.live(now)
            box(tripLabel(trip, live)).apply {
                isEnabled = !live
                alpha = if (live) 0.5f else 1f
            }
        }
        val pickable = boxes.filter { it.isEnabled }
        all.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            syncing = true
            pickable.forEach { it.isChecked = on }
            syncing = false
        }
        for (b in pickable) b.setOnCheckedChangeListener { _, _ ->
            if (syncing) return@setOnCheckedChangeListener
            syncing = true
            all.isChecked = pickable.all { it.isChecked }
            syncing = false
        }
        val list = ui.column(all, ui.divider(), *boxes.toTypedArray()).apply { setPadding(ui.px(20), ui.px(8), ui.px(20), 0) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Delete trips")
            .setView(ScrollView(this).apply { addView(list) })
            .setNeutralButton("Delete", null)
            .setNegativeButton("Cancel", null)
            .show()
        fun picked() = trips.filterIndexed { i, _ -> boxes[i].isEnabled && boxes[i].isChecked }
        // Set after show(): a listener given to the builder would close the dialog even with nothing ticked
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            background = ui.shape(R.color.status_crit, 999)
            setTextColor(ui.color(R.color.on_crit))
            setPadding(ui.px(18), 0, ui.px(18), 0)
            setOnClickListener {
                val p = picked()
                if (p.isEmpty()) return@setOnClickListener toast("Tick the trips to delete first")
                confirmDelete(p) { dialog.dismiss() }
            }
        }
    }

    /** What deleting [trips] removes, then a button that has to be held; [onDeleted] closes the trip list. */
    private fun confirmDelete(trips: List<Trip>, onDeleted: () -> Unit) {
        Thread {
            val counts = container.real.tripCounts(trips)
            runOnUiThread { if (!isFinishing) showDelete(trips, counts, onDeleted) }
        }.start()
    }

    private fun showDelete(trips: List<Trip>, counts: Map<String, Int>, onDeleted: () -> Unit) {
        val units = Prefs.units(this)
        val shown = 8
        val lines = trips.take(shown).map {
            "${fmt(it.start)} · %.1f %s · ${plural(it.regens, "regen")}".format(units.distance(it.metres), units.distanceLabel)
        } +
            listOfNotNull("…and ${trips.size - shown} more".takeIf { trips.size > shown })
        val rows = listOf(plural(counts["sample"] ?: 0, "reading"), plural(counts["frame"] ?: 0, "engine frame"),
            plural(counts["regen"] ?: 0, "regen"), plural(counts["alert"] ?: 0, "warning"))
        val hub = if (Prefs.server(this).isNotBlank()) " They are also removed from the hub at the next sync." else ""
        val message = lines.joinToString("\n") + "\n\nRemoves ${rows.joinToString(", ")}.$hub This can't be undone."
        lateinit var dialog: AlertDialog
        val hold = HoldButton(this, "Yes, delete") {
            dialog.dismiss()
            onDeleted()
            Thread {
                val tops = container.real.deleteTrips(trips)
                Sync.queueDeletes(applicationContext, trips, tops)
                runOnUiThread { toast("Deleted ${plural(trips.size, "trip")}"); render() }
            }.start()
        }.apply {
            ui.style(this, Type.TITLE_SM)
            textSize = Type.TITLE_SM.sp
            setTextColor(ui.color(R.color.on_crit))
            background = ui.shape(R.color.status_crit, 999)
            minHeight = ui.px(56)
        }
        val box = ui.column(
            ui.text(Type.BODY_SM, R.color.ink, message),
            ui.eyebrow("Hold the button to delete").apply { setPadding(0, ui.px(20), 0, ui.px(8)) },
        ).apply {
            setPadding(ui.px(24), ui.px(8), ui.px(24), 0)
            addView(hold, LinearLayout.LayoutParams(-1, -2))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Delete ${plural(trips.size, "trip")}?")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun plural(n: Int, what: String) = "%,d $what${if (n == 1) "" else "s"}".format(n)

    /** The whole real log (never Debug data) as one AI-readable Markdown file in Downloads; MediaStore needs no storage permission. */
    private fun exportAll() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        toast("Building AI export…")
        Thread {
            val result = try {
                val tmp = File(cacheDir, "export.md")
                container.real.exportAi(tmp, version, Prefs.model2015(this))
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(System.currentTimeMillis())
                val name = "yaris-doctor-ai-$stamp.md"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads refused the file")
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

    /**
     * The whole real log (never Debug data) as one SQLite file in Downloads, restorable with "Restore database";
     * MediaStore needs no storage permission, like the AI export above.
     */
    private fun backUp() {
        toast("Copying the database…")
        Thread {
            val result = try {
                val tmp = File(cacheDir, "backup.db")
                DatabaseBackup.export(container.real, tmp)
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(System.currentTimeMillis())
                val name = "yaris-doctor-backup-$stamp.db"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-sqlite3")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads refused the file")
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
                "Backup failed: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { toast(result) }
        }.start()
    }

    /** Picks a .db snapshot to restore; ACTION_OPEN_DOCUMENT needs no storage permission either. */
    private fun pickBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            startActivityForResult(intent, REQUEST_RESTORE)
        } catch (e: ActivityNotFoundException) {
            toast("No file picker on this phone")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_RESTORE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        toast("Checking the backup…")
        Thread {
            val tmp = File(cacheDir, "restore.db")
            var name = "this backup"
            val error = try {
                name = displayName(uri) ?: name
                tmp.delete()
                contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: error("Could not read the picked file")
                DatabaseBackup.validate(container.real, tmp) // before the question, so a wrong file never asks
                null
            } catch (e: Exception) {
                Log.d("DatabaseBackup", "backup rejected: ${e.javaClass.simpleName}: ${e.message}")
                e.message ?: e.javaClass.simpleName
            }
            if (error != null) {
                tmp.delete() // not a backup: no reason to keep the copy
                runOnUiThread { toast("Not a usable backup: $error") }
            } else {
                runOnUiThread { if (!isFinishing) confirmRestore(tmp, name) }
            }
        }.start()
    }

    /** "yaris-doctor-backup-20261026-1014.db": the file's own name is what the user recognises. */
    private fun displayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    /** Destructive, so the file and what it replaces are spelled out; the hub keeps its own copy. */
    private fun confirmRestore(file: File, name: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Restore $name?")
            .setMessage("This replaces every reading, regen and warning on this phone. The hub is not changed.")
            .setPositiveButton("Restore") { _, _ -> restore(file) }
            .setNegativeButton("Cancel") { _, _ -> file.delete() }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.color(R.color.status_crit))
    }

    /**
     * Stops the service, then copies [file]'s rows in (one transaction: a failure leaves the log exactly as it was) and
     * makes the next sync upload everything again.
     */
    private fun restore(file: File) {
        stopService(Intent(this, DpfService::class.java))
        toast("Restoring…")
        Thread {
            val result = try {
                val counts = DatabaseBackup.restore(container.real, file)
                resetSyncForRestore()
                "Restored ${plural(counts.readings, "reading")}, ${plural(counts.regens, "regen")}, " +
                    "${plural(counts.frames, "frame")}, ${plural(counts.warnings, "warning")}"
            } catch (e: Exception) {
                "Restore failed: ${e.message ?: e.javaClass.simpleName}. Nothing was changed."
            } finally {
                file.delete()
            }
            runOnUiThread {
                oil = null
                DpfService.start(this) // stopped above, and a failed restore still needs logging back on
                toast(result)
                render()
            }
        }.start()
    }

    /**
     * A restored log's rowids are the hub's idea of this phone's rows, but its cursors say everything is already sent:
     * send it all again from the start, and download the hub's rows again too. Both sides ignore what they have.
     */
    private fun resetSyncForRestore() {
        Prefs.put(this, Prefs.SYNCED_ROWID, 0L)
        Prefs.put(this, Prefs.SYNCED_FRAME_ROWID, 0L)
        Prefs.put(this, Prefs.SYNCED_REGENS, null)
        Prefs.put(this, Prefs.SYNCED_ALERTS, null)
        Prefs.put(this, Prefs.HUB_FRAMES, true)
        Prefs.put(this, Prefs.HUB_DELETES, true)
        Prefs.put(this, Prefs.PULLED_ROWID, 0L)
        Prefs.put(this, Prefs.PENDING_DELETES, null)
        Prefs.put(this, Prefs.THINNED_TO, 0L)
        Prefs.put(this, "thinned_hourly_to", 0L) // the hourly thinning cursor, which Prefs has no constant for yet
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private companion object {
        /** [onActivityResult]'s request code for the Restore database file picker. */
        private const val REQUEST_RESTORE = 1
    }
}
