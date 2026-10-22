package com.yarisdoctor

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.POST_NOTIFICATIONS
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.yarisdoctor.Ui.Type
import com.yarisdoctor.domain.health.CRIT
import com.yarisdoctor.domain.health.WARN
import com.yarisdoctor.domain.model.Alert
import com.yarisdoctor.domain.model.Regen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val ui by lazy { Ui(this) }
    private val vm: OverviewViewModel by viewModels()
    private var ticker: Job? = null

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var notice: LinearLayout
    private lateinit var noticeBadge: View
    private lateinit var noticeText: TextView
    private lateinit var callout: LinearLayout
    private lateinit var calloutText: TextView
    private lateinit var soot: TextView
    private lateinit var sootSub: TextView
    private lateinit var sootSquare: Ui.SquareSpan
    private var sootShown: Int? = null
    private var sootAnim: ValueAnimator? = null
    private var pulse: ValueAnimator? = null
    private lateinit var temp: TextView
    private lateinit var tempSub: TextView
    private lateinit var miles: TextView
    private lateinit var milesSub: TextView
    private lateinit var regenState: TextView
    private lateinit var regenSub: TextView
    private lateinit var sootFill: SootView
    private lateinit var sootChart: ChartView
    private lateinit var regenChart: ChartView
    private lateinit var regenTitle: TextView
    private lateinit var dpfChart: ChartView
    private lateinit var dpfTitle: TextView
    private lateinit var ashText: TextView
    private var dpfShown: Pair<DpfCard, String>? = null // the card and unit on screen, so the chart redraws only on a change
    private lateinit var history: LinearLayout
    private var historyShown: Pair<List<Regen>, String>? = null
    private lateinit var health: LinearLayout
    private var healthKey: String? = null
    private val allData by lazy { AllData(this, ui) }
    private val rules by lazy { RulesTab(this, ui) }
    private lateinit var overview: View
    private lateinit var scroll: ScrollView
    private lateinit var tabs: LinearLayout
    private var tab = 0 // index into TABS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Connection status: a quiet pill with a live dot
        statusText = ui.text(Type.CAPTION, R.color.ink)
        statusDot = ui.dot(R.color.muted_soft)
        val status = ui.pill(statusText, R.color.surface_card, statusDot, R.color.hairline)

        // Notices (Debug mode, missing permission, logging off)
        noticeBadge = ui.pill(ui.text(Type.CAPTION_UPPER, R.color.on_primary, "Debug"), R.color.primary)
        noticeText = ui.text(Type.BODY_SM, R.color.body)
        notice = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.shape(R.color.surface_card, 16, R.color.hairline)
            setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(12))
            addView(noticeBadge, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.px(12) })
            addView(noticeText, LinearLayout.LayoutParams(0, -2, 1f))
        }

        // Live regen: the one orange field on Overview
        calloutText = ui.text(Type.BODY_SM, R.color.on_primary)
        callout = ui.card(R.color.primary, 24,
            ui.eyebrow("Regen running", R.color.on_primary),
            ui.text(Type.DISPLAY_SM, R.color.on_primary, "Keep driving").apply { setPadding(0, ui.px(8), 0, ui.px(4)) },
            calloutText,
        )

        // Instrument card: soot fills it from the bottom; the soot % is the loudest thing on the screen
        val (sq, span) = ui.signal("—")
        sootSquare = span
        soot = ui.text(Type.NUMERAL, R.color.on_instrument, sq)
        sootSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        val sootText = ui.column(ui.eyebrow("Soot load", R.color.on_instrument_soft)).apply {
            setPadding(ui.px(24), ui.px(22), ui.px(24), ui.px(22))
            addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
            addView(soot)
            addView(sootSub.apply { setPadding(0, ui.px(8), 0, 0) })
        }
        sootFill = SootView(this, ui.color(R.color.soot_fill), ui.color(R.color.primary))
        val instrument = FrameLayout(this).apply {
            background = ui.shape(R.color.instrument, 20)
            clipToOutline = true
            addView(sootFill, FrameLayout.LayoutParams(-1, -1))
            addView(sootText, FrameLayout.LayoutParams(-1, -1))
        }

        // Stat tiles: dark, side-scrolling, ~0.62 of the content width each so the next one peeks
        temp = ui.text(Type.STAT, R.color.on_instrument); tempSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        miles = ui.text(Type.STAT, R.color.on_instrument); milesSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        regenState = ui.text(Type.STAT, R.color.on_instrument); regenSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        val tileW = ((resources.displayMetrics.widthPixels - ui.px(40)) * 0.62f).toInt()
        val tiles = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(LinearLayout(context).apply {
                listOf(tile("DPF temp", temp, tempSub), tile("Since regen", miles, milesSub), tile("Regen", regenState, regenSub))
                    .forEachIndexed { i, t -> addView(t, LinearLayout.LayoutParams(tileW, -2).apply { if (i > 0) marginStart = ui.px(12) }) }
            })
        }

        // Charts
        sootChart = ChartView(this, columns = false, ui.chartColors())
        regenChart = ChartView(this, columns = true, ui.chartColors())
        regenTitle = ui.text(Type.TITLE_MD, R.color.ink)
        val sootCard = chartCard("Soot · last 14 days", ui.text(Type.TITLE_MD, R.color.ink, "Soot trend"), sootChart)
        val regenCard = chartCard("Regens · last 20", regenTitle, regenChart)
        dpfChart = ChartView(this, columns = true, ui.chartColors())
        dpfTitle = ui.text(Type.TITLE_MD, R.color.ink, "DPF condition")
        val dpfCard = chartCard("After each regen · last 20", dpfTitle, dpfChart).apply {
            addView(ui.text(Type.BODY_SM, R.color.muted,
                "Exhaust restriction right after each regen, in kPa per 100 l/s of exhaust. Burning removes the soot, so what " +
                    "stays is ash: a rising bar means the filter is filling with ash and needs a clean.").apply { setPadding(0, ui.px(12), 0, 0) })
            ashText = ui.text(Type.BODY_SM, R.color.muted).apply { setPadding(0, ui.px(8), 0, 0) }
            addView(ashText)
        }

        history = ui.column()
        health = ui.column()

        val content = ui.column().apply {
            setPadding(ui.px(20), ui.px(8), ui.px(20), ui.px(40))
            addView(status, LinearLayout.LayoutParams(-2, -2))
            addView(health, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(24) })
            for (v in listOf(notice, callout)) addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) })
            addView(instrument, LinearLayout.LayoutParams(-1, ui.px(232)).apply { topMargin = ui.px(24) })
            addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(12) })
            for (v in listOf(sootCard, regenCard, dpfCard)) addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) })
            addView(ui.eyebrow("Regens").apply { gravity = Gravity.CENTER_HORIZONTAL }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(40) })
            addView(ui.text(Type.DISPLAY_SM, R.color.ink, "Every regen, recorded").apply { gravity = Gravity.CENTER_HORIZONTAL },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(8) })
            addView(history, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) })
        }
        val settings = ui.iconButton(R.drawable.ic_settings, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        overview = content
        scroll = ScrollView(this)
        tabs = LinearLayout(this).apply { setPadding(ui.px(20), ui.px(4), ui.px(20), ui.px(12)) }
        showTab(0)
        val root = ui.column(ui.topBar("Yaris Doctor", end = settings), tabs).apply {
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ui.fitSystemBars(root)
        setContentView(root)
        ui.enter(listOf(status, health, instrument, tiles, sootCard, regenCard))
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
        ticker = lifecycleScope.launch {
            while (true) {
                refreshNow()
                delay(5_000)
            }
        }
    }

    override fun onPause() {
        ticker?.cancel()
        pulse?.cancel(); pulse = null
        super.onPause()
    }

    private fun showTab(i: Int) {
        val from = tab
        tab = i
        tabs.removeAllViews()
        TABS.forEachIndexed { k, label ->
            tabs.addView(ui.chip(label, k == i) { if (k != tab) showTab(k) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.px(8) })
        }
        scroll.removeAllViews()
        val v = when (i) { 1 -> allData.view; 2 -> rules.view; else -> overview }
        scroll.addView(v)
        scroll.scrollTo(0, 0)
        // Slide in from the side of the chip that was tapped, so the content follows the chip row
        if (from != i && Ui.animationsOn()) {
            v.alpha = 0f
            v.translationX = ui.px(if (i > from) 24 else -24).toFloat()
            v.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(280)
                .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f)).start()
        }
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch { refreshNow() }
    }

    /** Reads off the main thread, then draws the visible tab. */
    private suspend fun refreshNow() {
        when (tab) {
            0 -> vm.load().let { if (tab == 0) render(it) }
            1 -> allData.refresh()
            // 2: the rules don't change
        }
    }

    private fun render(s: OverviewState) {
        val last = s.last
        val regens = s.regens
        val now = s.now
        val live = last != null && now - last.t <= 30_000

        statusText.text = when {
            last == null -> "Waiting for the first drive"
            !live -> "Last reading ${fmt(last.t)}"
            else -> "Live from the car"
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
        val open = regens.lastOrNull()?.takeIf { it.end == null }
        val mins = open?.let { (now - it.start) / 60_000 } ?: 0L
        callout.visibility = if (regenNow) View.VISIBLE else View.GONE
        if (regenNow) calloutText.text = "DPF ${last!!.temp ?: "?"}°C · soot ${last.soot ?: "?"}% · $mins min so far"

        val (perUnit, unit) = Prefs.unit(this)

        showSoot(last?.soot)
        sootFill.setSoot(last?.soot)
        sootSub.text = last?.let { if (live) "Now" else "At ${fmt(it.t)}" } ?: "No readings yet"
        setPulse(regenNow)

        temp.text = last?.temp?.let { "$it°C" } ?: "—"
        tempSub.text = if (live) "Exhaust after the DPF" else "Last reading"
        val gaps = regens.mapNotNull { it.gapM }
        miles.text = s.metresSinceRegen?.let { "%.0f $unit".format(it / perUnit) } ?: "—"
        milesSub.text = if (gaps.isEmpty()) "Average after two regens" else "Avg %.0f $unit between".format(gaps.average() / perUnit)
        regenState.text = if (regenNow) "Burning" else "Off"
        regenState.setTextColor(ui.color(if (regenNow) R.color.primary else R.color.on_instrument))
        regenSub.text = when {
            regenNow -> "$mins min so far"
            regens.lastOrNull()?.end != null -> "Last took ${duration(regens.last())}"
            else -> "None logged yet"
        }

        val series = s.sootSeries
        sootChart.set(
            series.map { it.first }.toLongArray(), series.map { it.second }.toFloatArray(),
            { i -> "${fmt(series[i].first)} · %.0f%% soot".format(series[i].second) }, ::fmtDay,
            regens.map { it.start to (it.end ?: now) },
        )
        regenTitle.text = if (unit == "km") "Kilometres between regens" else "Miles between regens"
        val withGap = regens.filter { it.gapM != null }.takeLast(20)
        regenChart.set(
            withGap.map { it.start }.toLongArray(), withGap.map { (it.gapM!! / perUnit).toFloat() }.toFloatArray(),
            { i -> "${fmt(withGap[i].start)} · %.0f $unit · ${duration(withGap[i])}".format(withGap[i].gapM!! / perUnit) }, ::fmtDay,
        )

        // The view model reloads the card at most once a minute; redraw only then or on a unit change
        if (dpfShown?.let { it.first === s.dpf && it.second == unit } != true) {
            dpfShown = s.dpf to unit
            val d = s.dpf.conditions
            val rate = d.lastOrNull()?.perKm
            dpfTitle.text = if (rate == null) "DPF condition" else "DPF condition · fills %.2f per %s".format(rate * perUnit / 1000, unit)
            // Grouped like Techstream's Learning Values Confirmation, so the two can be compared by eye
            fun ash(hex: String) = hex.chunked(4).joinToString(" ")
            val changes = s.dpf.ashChanges
            ashText.text = s.dpf.ash?.let { (t, hex) ->
                "ECU ash record (Techstream: DPF Deterioration Record): ${ash(hex)} · ${fmt(t)}" +
                    // Where it changed, newest first: a Techstream write or the ECU's own ash count moving
                    if (changes.size > 1) "\nChanges:" + changes.joinToString("") { (ct, h) -> "\n${fmt(ct)} · ${ash(h)}" } else ""
            } ?: "ECU ash record: not read yet"
            dpfChart.set(
                d.map { it.regenStart }.toLongArray(), d.map { it.base!!.toFloat() }.toFloatArray(),
                { i -> "${fmt(d[i].regenStart)} · %.1f kPa".format(d[i].base) + (d[i].perKm?.let { " · then +%.2f per $unit".format(it * perUnit / 1000) } ?: "") },
                ::fmtDay,
            )
        }

        val alerts = s.alerts // open ones only: a recovered warning stays stored (and on the hub) but leaves the dashboard
        val alertsKey = alerts.joinToString("|") { "${it.key}${it.text}${it.end}" }
        if (alertsKey != healthKey) {
            healthKey = alertsKey
            renderHealth(alerts)
        }

        // Rebuilt only when the list or units change, or the newest regen's floor becomes readable; refresh runs every 5 s
        if (historyShown?.let { it.first === s.history && it.second == unit } != true) {
            historyShown = s.history to unit
            renderHistory(s.history, perUnit, unit)
        }
    }

    /** Counts the soot figure from the old value to the new one alongside the fill (700 ms); jumps without animations. */
    private fun showSoot(v: Int?) {
        val from = sootShown
        sootShown = v
        sootAnim?.cancel()
        fun put(n: Int?) { soot.text = ui.signalWith(n?.let { "$it%" } ?: "—", sootSquare) }
        if (v == null || from == null || from == v || !Ui.animationsOn()) return put(v)
        sootAnim = ValueAnimator.ofInt(from, v).apply {
            duration = 700; interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { put(it.animatedValue as Int) }
            start()
        }
    }

    /** The square after the soot % breathes (1 → 0.45, 1.2 s) while a regen runs; static otherwise. */
    private fun setPulse(on: Boolean) {
        if (on == (pulse != null)) return
        pulse?.cancel(); pulse = null
        sootSquare.alpha = 1f
        soot.invalidate()
        if (!on || !Ui.animationsOn()) return
        pulse = ValueAnimator.ofFloat(1f, 0.45f).apply {
            duration = 1200; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { sootSquare.alpha = it.animatedValue as Float; soot.invalidate() }
            start()
        }
    }

    /** The verdict set huge and ended by a square in the status colour, then what the checks found. */
    private fun renderHealth(alerts: List<Alert>) {
        health.removeAllViews()
        val open = alerts.filter { it.end == null }
        val level = open.maxOfOrNull { it.level } ?: 0
        val (title, statusColor) = when (level) {
            CRIT -> "Check the car now" to R.color.status_crit
            WARN -> "Warning" to R.color.status_warn
            else -> "All good" to R.color.accent_teal
        }
        health.addView(ui.eyebrow("Health"))
        health.addView(ui.text(Type.HERO, R.color.ink, ui.signal(title, statusColor).first).apply { setPadding(0, ui.px(10), 0, 0) })
        if (open.isEmpty()) health.addView(ui.text(Type.BODY_MD, R.color.body, "No warnings right now.").apply { setPadding(0, ui.px(14), ui.px(40), 0) })
        for (a in open) health.addView(alertRow(a, if (a.level == CRIT) R.color.status_crit else R.color.status_warn, R.color.ink))
        health.addView(ui.text(Type.CAPTION, R.color.primary, SpannableString("SEE THE RULES  ↗").apply { setSpan(UnderlineSpan(), 0, 13, 0) }).apply {
            minHeight = ui.px(48)
            gravity = Gravity.CENTER_VERTICAL
            letterSpacing = 0.04f
            contentDescription = "See the rules"
            isClickable = true
            ui.ripple(this)
            setOnClickListener { showTab(2) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.px(8) })
        health.addView(ui.divider(R.color.hairline), LinearLayout.LayoutParams(-1, ui.px(1).coerceAtLeast(1)).apply { topMargin = ui.px(8) })
    }

    private fun alertRow(a: Alert, dot: Int, ink: Int) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.px(12), 0, 0)
        addView(ui.dot(dot), LinearLayout.LayoutParams(ui.px(10), ui.px(10)).apply { marginEnd = ui.px(12) })
        addView(ui.column(
            ui.text(Type.TITLE_SM, ink, (if (a.level == CRIT) "Critical: " else "") + a.text),
            ui.eyebrow(fmt(a.start)).apply { setPadding(0, ui.px(2), 0, 0) },
        ), LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** [regens] with their signs, newest first, capped at 50 rows. */
    private fun renderHistory(regens: List<Regen>, perUnit: Double, unit: String) {
        history.removeAllViews()
        if (regens.isEmpty()) {
            history.addView(ui.text(Type.BODY_MD, R.color.muted, "None logged yet. Regens appear here after a drive.").apply { gravity = Gravity.CENTER_HORIZONTAL })
            return
        }
        regens.forEachIndexed { i, r ->
            if (i > 0) history.addView(ui.divider())
            val badge = when {
                r.end == null -> ui.pill(ui.text(Type.CAPTION_UPPER, R.color.on_primary, "Ongoing"), R.color.primary)
                r.gapM != null -> ui.pill(ui.text(Type.CAPTION, R.color.ink, "%.0f $unit".format(r.gapM!! / perUnit)))
                else -> ui.pill(ui.text(Type.CAPTION, R.color.muted, "First logged"))
            }
            history.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.px(64)
                setPadding(0, ui.px(12), 0, ui.px(12))
                addView(ui.column(
                    ui.text(Type.TITLE_SM, R.color.ink, fmt(r.start)),
                    ui.eyebrow("${duration(r)} · soot ${r.sootStart ?: "?"}→${r.sootEnd ?: "?"}%").apply { setPadding(0, ui.px(4), 0, 0) },
                    // Warning signs: soot 5 min after (the real floor) and the hottest the DPF got
                    ui.eyebrow(listOfNotNull(r.floor?.let { "after 5 min $it%" }, r.peakTemp?.let { "peak $it°C" }).joinToString(" · ")).apply {
                        setPadding(0, ui.px(2), 0, 0)
                        if (text.isEmpty()) visibility = View.GONE
                    },
                ), LinearLayout.LayoutParams(0, -2, 1f))
                addView(badge)
            })
        }
    }

    private fun tile(label: String, value: TextView, sub: TextView) = ui.column(
        ui.eyebrow(label, R.color.on_instrument_soft),
        value.apply { setPadding(0, ui.px(14), 0, ui.px(6)) },
        sub,
    ).apply {
        background = ui.shape(R.color.instrument, 20)
        setPadding(ui.px(20), ui.px(18), ui.px(20), ui.px(18))
        minimumHeight = ui.px(120)
    }

    private fun chartCard(label: String, title: TextView, chart: ChartView) = ui.card(R.color.surface_card, 20,
        ui.eyebrow(label),
        title.apply { setPadding(0, ui.px(6), 0, ui.px(4)) },
        ui.text(Type.BODY_SM, R.color.muted, "Tap or drag the chart to read values").apply { setPadding(0, 0, 0, ui.px(12)) },
    ).apply { addView(chart, LinearLayout.LayoutParams(-1, ui.px(190))) }

    private fun duration(r: Regen) = r.end?.let { "${(it - r.start) / 60_000} min" } ?: "ongoing"
    private fun granted(perm: String) = checkSelfPermission(perm) == PERMISSION_GRANTED

    companion object {
        private val TABS = listOf("Overview", "All data", "Rules")
    }
}
