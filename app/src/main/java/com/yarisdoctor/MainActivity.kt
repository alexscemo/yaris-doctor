package com.yarisdoctor

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yarisdoctor.Ui.Type
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : Activity() {

    private val ui by lazy { Ui(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 5_000) }
    }

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var notice: LinearLayout
    private lateinit var noticeBadge: View
    private lateinit var noticeText: TextView
    private lateinit var callout: LinearLayout
    private lateinit var calloutText: TextView
    private lateinit var soot: TextView
    private lateinit var sootSub: TextView
    private lateinit var miles: TextView
    private lateinit var milesSub: TextView
    private lateinit var sootChart: ChartView
    private lateinit var regenChart: ChartView
    private lateinit var regenTitle: TextView
    private lateinit var history: LinearLayout
    private var historyKey = ""
    private lateinit var health: LinearLayout
    private var healthKey: String? = null
    private val allData by lazy { AllData(this, ui) }
    private lateinit var overview: View
    private lateinit var scroll: ScrollView
    private lateinit var tabs: LinearLayout
    private var allTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Status: badge-pill with a live dot
        statusText = ui.text(Type.CAPTION, R.color.ink)
        statusDot = ui.dot(R.color.muted_soft)
        val status = ui.pill(statusText, dot = statusDot)

        // Notices (Debug mode, missing permission, logging off): hairline card
        noticeBadge = ui.pill(ui.text(Type.CAPTION_UPPER, R.color.on_primary, "Debug"), R.color.primary)
        noticeText = ui.text(Type.BODY_SM, R.color.body)
        notice = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.shape(R.color.canvas, 12, R.color.hairline)
            setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(12))
            addView(noticeBadge, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.px(12) })
            addView(noticeText, LinearLayout.LayoutParams(0, -2, 1f))
        }

        // Live regen: callout-card-coral, the one generous use of coral
        calloutText = ui.text(Type.BODY_SM, R.color.on_primary)
        callout = ui.card(R.color.primary, 24,
            ui.text(Type.CAPTION_UPPER, R.color.on_primary, "Regenerating"),
            ui.text(Type.DISPLAY_SM, R.color.on_primary, "Keep driving").apply { setPadding(0, ui.px(8), 0, ui.px(4)) },
            calloutText,
        )

        // Instrument panel: product-mockup-card-dark
        soot = ui.text(Type.DISPLAY_MD, R.color.on_instrument)
        sootSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        miles = ui.text(Type.DISPLAY_MD, R.color.on_instrument)
        milesSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        val instrument = ui.card(R.color.instrument, 24).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stat("Soot load", soot, sootSub), LinearLayout.LayoutParams(0, -2, 1f))
            addView(stat("Since last regen", miles, milesSub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.px(16) })
        }

        // Charts: cream feature-cards
        sootChart = ChartView(this, columns = false, ui.chartColors())
        regenChart = ChartView(this, columns = true, ui.chartColors())
        regenTitle = ui.text(Type.TITLE_MD, R.color.ink)
        val sootCard = chartCard(ui.text(Type.TITLE_MD, R.color.ink, "Soot load"), "Last 14 days · tap or drag to read values", sootChart)
        val regenCard = chartCard(regenTitle, "Last 20 regens · tap or drag to read values", regenChart)

        history = ui.column()
        health = ui.card(R.color.surface_card, 20)

        val content = ui.column().apply {
            setPadding(ui.px(16), ui.px(8), ui.px(16), ui.px(32))
            addView(status, LinearLayout.LayoutParams(-2, -2))
            for (v in listOf(health, notice, callout, instrument, sootCard, regenCard)) {
                addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) })
            }
            addView(ui.text(Type.DISPLAY_SM, R.color.ink, "Regen history"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(32) })
            addView(history, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(8) })
        }
        val settings = ui.iconButton(R.drawable.ic_settings, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        overview = content
        scroll = ScrollView(this)
        tabs = LinearLayout(this).apply { setPadding(ui.px(16), 0, ui.px(16), ui.px(8)) }
        showTab(false)
        val root = ui.column(ui.topBar("Yaris Doctor", end = settings), tabs).apply {
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ui.fitSystemBars(root)
        setContentView(root)
        requestPermissions(arrayOf(BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        // Background location must be requested on its own, after fine location (Android 11+)
        if (requestCode == 1 && granted(ACCESS_FINE_LOCATION) && !granted(ACCESS_BACKGROUND_LOCATION)) {
            requestPermissions(arrayOf(ACCESS_BACKGROUND_LOCATION), 2)
            return
        }
        DpfService.start(this)
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

    private fun showTab(all: Boolean) {
        allTab = all
        tabs.removeAllViews()
        for ((label, on) in listOf("Overview" to !all, "All data" to all)) {
            tabs.addView(ui.chip(label, on) { if (!on) showTab(!all) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.px(8) })
        }
        scroll.removeAllViews()
        scroll.addView(if (all) allData.view else overview)
        scroll.scrollTo(0, 0)
        refresh()
    }

    private fun refresh() {
        if (allTab) return allData.refresh()
        val db = Db.get(this)
        val last = db.lastSample()
        val regens = db.regens()
        val now = System.currentTimeMillis()
        val live = last != null && now - last.t <= 30_000

        statusText.text = when {
            last == null -> "Waiting for the first drive"
            !live -> "Last reading ${fmt(last.t)}"
            else -> "Live · DPF ${last.temp ?: "?"}°C"
        }
        statusDot.background = ui.shape(if (live) R.color.accent_teal else R.color.muted_soft, 999)

        val problem = when {
            Prefs.debug && !granted(BLUETOOTH_CONNECT) -> "Simulated data needs the \"Nearby devices\" permission to run."
            Prefs.debug -> "Simulated data. Nothing is saved."
            !granted(BLUETOOTH_CONNECT) -> "Bluetooth permission is missing, so logging is off."
            !Prefs.logging(this) -> "Background logging is off. Turn it on in Settings."
            else -> null
        }
        notice.visibility = if (problem == null) View.GONE else View.VISIBLE
        noticeBadge.visibility = if (Prefs.debug) View.VISIBLE else View.GONE
        noticeText.text = problem

        val regenNow = live && last!!.regen
        callout.visibility = if (regenNow) View.VISIBLE else View.GONE
        if (regenNow) {
            val mins = regens.lastOrNull()?.takeIf { it.end == null }?.let { (now - it.start) / 60_000 } ?: 0L
            calloutText.text = "DPF ${last!!.temp ?: "?"}°C · soot ${last.soot ?: "?"}% · $mins min so far"
        }

        val km = Prefs.km(this)
        val perUnit = if (km) 1000.0 else Db.METRES_PER_MILE
        val unit = if (km) "km" else "mi"

        soot.text = last?.soot?.let { "$it%" } ?: "—"
        sootSub.text = last?.let { if (live) "now" else "at ${fmt(it.t)}" } ?: "no readings yet"
        val gaps = regens.mapNotNull { it.gapM }
        miles.text = db.metresSinceRegen()?.let { "%.0f $unit".format(it / perUnit) } ?: "—"
        milesSub.text = if (gaps.isEmpty()) "average after two regens" else "avg %.0f $unit between".format(gaps.average() / perUnit)

        val series = db.sootSeries(now - 14 * DAY_MS)
        sootChart.set(
            series.map { it.first }.toLongArray(), series.map { it.second }.toFloatArray(),
            { i -> "${fmt(series[i].first)} · %.0f%% soot".format(series[i].second) }, ::fmtDay,
        )
        regenTitle.text = if (km) "Kilometres between regens" else "Miles between regens"
        val withGap = regens.filter { it.gapM != null }.takeLast(20)
        regenChart.set(
            withGap.map { it.start }.toLongArray(), withGap.map { (it.gapM!! / perUnit).toFloat() }.toFloatArray(),
            { i -> "${fmt(withGap[i].start)} · %.0f $unit · ${duration(withGap[i])}".format(withGap[i].gapM!! / perUnit) }, ::fmtDay,
        )

        val alerts = db.alerts(now - 7 * DAY_MS)
        val alertsKey = alerts.joinToString("|") { "${it.key}${it.text}${it.end}" }
        if (alertsKey != healthKey) {
            healthKey = alertsKey
            renderHealth(alerts)
        }

        // Rebuilt only when the list or units change; refresh runs every 5 s
        val key = "$unit ${regens.size} ${regens.lastOrNull()?.end}"
        if (key != historyKey) {
            historyKey = key
            renderHistory(regens, perUnit, unit)
        }
    }

    /** "All good", or what the checks found: open alerts first, then the ones seen in the last 7 days. */
    private fun renderHealth(alerts: List<Db.Alert>) {
        health.removeAllViews()
        val open = alerts.filter { it.end == null }
        val level = open.maxOfOrNull { it.level } ?: 0
        val (title, dot) = when (level) {
            CRIT -> "Check the car now" to R.color.primary
            WARN -> "Warning" to R.color.primary
            else -> "All good" to R.color.accent_teal
        }
        health.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.dot(dot), LinearLayout.LayoutParams(ui.px(10), ui.px(10)).apply { marginEnd = ui.px(10) })
            addView(ui.text(Type.TITLE_MD, R.color.ink, title))
        })
        if (open.isEmpty()) {
            health.addView(ui.text(Type.BODY_SM, R.color.muted, "Every check on the engine data is within range.").apply { setPadding(0, ui.px(4), 0, 0) })
        }
        for (a in open) {
            health.addView(ui.text(Type.BODY_SM, R.color.ink, (if (a.level == CRIT) "Critical: " else "") + a.text).apply { setPadding(0, ui.px(8), 0, 0) })
        }
        val earlier = alerts.filter { it.end != null }
        if (earlier.isNotEmpty()) {
            health.addView(ui.text(Type.CAPTION_UPPER, R.color.muted, "Seen in the last 7 days").apply { setPadding(0, ui.px(16), 0, 0) })
            for (a in earlier) health.addView(ui.text(Type.BODY_SM, R.color.muted, "${fmt(a.start)} · ${a.text}").apply { setPadding(0, ui.px(6), 0, 0) })
        }
    }

    /** Newest first, capped at 50 rows. */
    private fun renderHistory(regens: List<Db.Regen>, perUnit: Double, unit: String) {
        history.removeAllViews()
        if (regens.isEmpty()) {
            history.addView(ui.text(Type.BODY_MD, R.color.muted, "None logged yet. Regens appear here after a drive."))
            return
        }
        regens.reversed().take(50).forEachIndexed { i, r ->
            if (i > 0) history.addView(ui.divider())
            val badge = when {
                r.end == null -> ui.pill(ui.text(Type.CAPTION_UPPER, R.color.on_primary, "Ongoing"), R.color.primary)
                r.gapM != null -> ui.pill(ui.text(Type.CAPTION, R.color.ink, "%.0f $unit".format(r.gapM / perUnit)))
                else -> ui.pill(ui.text(Type.CAPTION, R.color.muted, "First logged"))
            }
            history.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.px(64)
                setPadding(0, ui.px(12), 0, ui.px(12))
                addView(ui.column(
                    ui.text(Type.TITLE_SM, R.color.ink, fmt(r.start)),
                    ui.text(Type.BODY_SM, R.color.muted, "${duration(r)} · soot ${r.sootStart ?: "?"}→${r.sootEnd ?: "?"}%"),
                ), LinearLayout.LayoutParams(0, -2, 1f))
                addView(badge)
            })
        }
    }

    private fun stat(label: String, value: TextView, sub: TextView) = ui.column(
        ui.text(Type.CAPTION_UPPER, R.color.on_instrument_soft, label),
        value.apply { setPadding(0, ui.px(8), 0, ui.px(4)) },
        sub,
    )

    private fun chartCard(title: TextView, sub: String, chart: ChartView) = ui.card(R.color.surface_card, 20,
        title,
        ui.text(Type.BODY_SM, R.color.muted, sub).apply { setPadding(0, ui.px(2), 0, ui.px(12)) },
    ).apply { addView(chart, LinearLayout.LayoutParams(-1, ui.px(180))) }

    private fun duration(r: Db.Regen) = r.end?.let { "${(it - r.start) / 60_000} min" } ?: "ongoing"
    private fun fmt(t: Long) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(t)
    private fun fmtDay(t: Long) = SimpleDateFormat("d MMM", Locale.getDefault()).format(t)
    private fun granted(perm: String) = checkSelfPermission(perm) == PERMISSION_GRANTED

    companion object {
        private const val DAY_MS = 86_400_000L
    }
}
