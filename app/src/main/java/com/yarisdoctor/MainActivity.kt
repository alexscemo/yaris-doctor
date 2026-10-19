package com.yarisdoctor

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : Activity() {

    private val p by lazy {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (night) Palette.DARK else Palette.LIGHT
    }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 5_000) }
    }
    private lateinit var status: TextView
    private lateinit var soot: TextView
    private lateinit var sootSub: TextView
    private lateinit var miles: TextView
    private lateinit var milesSub: TextView
    private lateinit var sootChart: ChartView
    private lateinit var regenChart: ChartView
    private lateinit var history: TextView
    private lateinit var warning: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        status = text(14f, p.secondary)
        warning = text(14f, p.text)
        soot = text(44f, p.text, bold = true); sootSub = text(12f, p.muted)
        miles = text(44f, p.text, bold = true); milesSub = text(12f, p.muted)
        sootChart = ChartView(this, columns = false, p)
        regenChart = ChartView(this, columns = true, p)
        history = text(14f, p.text).apply { setTextIsSelectable(true); setLineSpacing(4 * d, 1f) }

        val tiles = LinearLayout(this).apply {
            addView(tile("Soot load", soot, sootSub), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tile("Since last regen", miles, milesSub), LinearLayout.LayoutParams(0, -2, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (32 * d).toInt())
            addView(status)
            addView(warning)
            addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * d).toInt() })
            addView(heading("Soot load, last 14 days"))
            addView(sootChart, LinearLayout.LayoutParams(-1, (200 * d).toInt()))
            addView(heading("Miles between regens"))
            addView(regenChart, LinearLayout.LayoutParams(-1, (200 * d).toInt()))
            addView(heading("Regen history"))
            addView(history)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(p.surface); addView(root) })
        requestPermissions(arrayOf(BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        // Background location must be requested on its own, after fine location (Android 11+)
        if (requestCode == 1 && granted(ACCESS_FINE_LOCATION) && !granted(ACCESS_BACKGROUND_LOCATION)) {
            requestPermissions(arrayOf(ACCESS_BACKGROUND_LOCATION), 2)
            return
        }
        if (granted(BLUETOOTH_CONNECT)) startForegroundService(Intent(this, DpfService::class.java))
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        refresh()
    }

    // Live while visible: the service writes a sample every 1-5 s when connected
    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val db = Db.get(this)
        val last = db.lastSample()
        val regens = db.regens()
        val now = System.currentTimeMillis()

        status.text = when {
            last == null -> "Waiting for the first drive"
            now - last.t > 30_000 -> "Last reading ${fmt(last.t)}"
            last.regen -> "Live · regenerating now"
            else -> "Live · ${last.temp ?: "?"}°C DPF"
        }
        warning.visibility = if (granted(BLUETOOTH_CONNECT)) View.GONE else View.VISIBLE
        warning.text = "Bluetooth permission missing - logging is off."

        soot.text = last?.soot?.let { "$it%" } ?: "—"
        sootSub.text = last?.let { if (now - it.t > 30_000) "at ${fmt(it.t)}" else "now" } ?: "no readings yet"

        val gaps = regens.mapNotNull { it.miles }
        miles.text = db.milesSinceRegen()?.let { "%.0f mi".format(it) } ?: "—"
        milesSub.text = if (gaps.isEmpty()) "needs two logged regens for an average" else "avg %.0f mi between regens".format(gaps.average())

        val series = db.sootSeries(now - 14 * DAY_MS)
        sootChart.set(
            series.map { it.first }.toLongArray(), series.map { it.second }.toFloatArray(),
            { i -> "${fmt(series[i].first)} · %.0f%% soot".format(series[i].second) }, ::fmtDay,
        )
        val withGap = regens.filter { it.miles != null }.takeLast(20)
        regenChart.set(
            withGap.map { it.start }.toLongArray(), withGap.map { it.miles!!.toFloat() }.toFloatArray(),
            { i -> "${fmt(withGap[i].start)} · %.0f mi · ${duration(withGap[i])}".format(withGap[i].miles) }, ::fmtDay,
        )

        history.text = if (regens.isEmpty()) "None logged yet." else regens.reversed().joinToString("\n") { r ->
            val gap = r.miles?.let { "%.0f mi since last".format(it) } ?: "first logged"
            "${fmt(r.start)}  ${duration(r)}  $gap  soot ${r.sootStart ?: "?"}→${r.sootEnd ?: "?"}%"
        }
    }

    private fun tile(label: String, value: TextView, sub: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(13f, p.secondary).apply { text = label })
        addView(value)
        addView(sub)
    }

    private fun heading(s: String) = text(16f, p.text, bold = true).apply {
        text = s
        setPadding(0, (28 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
    }

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun duration(r: Db.Regen) = r.end?.let { "${(it - r.start) / 60_000} min" } ?: "ongoing"
    private fun fmt(t: Long) = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(t)
    private fun fmtDay(t: Long) = SimpleDateFormat("d MMM", Locale.getDefault()).format(t)
    private fun granted(perm: String) = checkSelfPermission(perm) == PERMISSION_GRANTED

    companion object {
        private const val DAY_MS = 86_400_000L
    }
}
