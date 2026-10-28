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
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.yarisdoctor.Ui.Type
import com.yarisdoctor.domain.drive.forecast
import com.yarisdoctor.domain.health.CRIT
import com.yarisdoctor.domain.health.WARN
import com.yarisdoctor.domain.model.Alert
import com.yarisdoctor.domain.model.Regen
import com.yarisdoctor.domain.model.UnitSystem
import com.yarisdoctor.domain.model.Units
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
    private lateinit var sinceRegen: TextView
    private lateinit var sinceRegenSub: TextView
    private lateinit var regenState: TextView
    private lateinit var regenSub: TextView
    private lateinit var nextRegen: TextView
    private lateinit var nextRegenSub: TextView
    private lateinit var fuel: TextView
    private lateinit var fuelSub: TextView
    private lateinit var use: TextView
    private lateinit var useSub: TextView
    private lateinit var sootFill: SootView
    private lateinit var sootChart: ChartView
    private var sootSeriesShown: Pair<List<Pair<Long, Float>>, UnitSystem>? = null // the series on screen, to skip a redraw
    private lateinit var regenChart: ChartView
    private lateinit var regenTitle: TextView
    private lateinit var dpfChart: ChartView
    private lateinit var dpfTitle: TextView
    private lateinit var ashText: TextView
    private lateinit var dpfAbout: TextView
    private var dpfShown: Pair<DpfCard, UnitSystem>? = null // the card and unit system on screen, so the chart redraws only on a change
    private lateinit var history: LinearLayout
    private var historyShown: Pair<List<Regen>, UnitSystem>? = null
    private lateinit var health: LinearLayout
    private var healthKey: String? = null
    private val allData by lazy { AllData(this, ui) }
    private val maintenance by lazy { MaintenanceTab(this, ui) }
    private lateinit var overview: View
    private lateinit var scroll: ScrollView
    private val nav = ArrayList<NavItem>() // one per TABS entry
    private var tab = OVERVIEW // index into TABS
    private val scrollY = IntArray(TABS.size) // where each tab was left, so going back to it lands there
    private val backToOverview = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showTab(OVERVIEW)
    }

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
        sinceRegen = ui.text(Type.STAT, R.color.on_instrument); sinceRegenSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        regenState = ui.text(Type.STAT, R.color.on_instrument); regenSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        nextRegen = ui.text(Type.STAT, R.color.on_instrument); nextRegenSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        fuel = ui.text(Type.STAT, R.color.on_instrument); fuelSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        use = ui.text(Type.STAT, R.color.on_instrument); useSub = ui.text(Type.BODY_SM, R.color.on_instrument_soft)
        val tileW = ((resources.displayMetrics.widthPixels - ui.px(40)) * 0.62f).toInt()
        val tiles = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(LinearLayout(context).apply {
                listOf(
                    tile("DPF temp", temp, tempSub),
                    // A tap logs a fill-up: the Maintenance tab keeps them
                    tile("Fuel", fuel, fuelSub).apply {
                        ui.ripple(this)
                        setOnClickListener { showTab(MAINTENANCE); maintenance.addFill() }
                    },
                    tile("Fuel use", use, useSub),
                    tile("Since regen", sinceRegen, sinceRegenSub),
                    tile("Regen", regenState, regenSub),
                    tile("Next regen", nextRegen, nextRegenSub),
                ).forEachIndexed { i, t -> addView(t, LinearLayout.LayoutParams(tileW, -2).apply { if (i > 0) marginStart = ui.px(12) }) }
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
            dpfAbout = ui.text(Type.BODY_SM, R.color.muted,
                "Exhaust restriction right after each regen. Burning removes the soot, so what " +
                    "stays is ash: a rising bar means the filter is filling with ash and needs a clean.").apply { setPadding(0, ui.px(12), 0, 0) }
            addView(dpfAbout)
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
        // Bottom navigation: in thumb reach and always on screen, one tap from any tab to any other
        val bar = LinearLayout(this).apply {
            TABS.indices.forEach { i -> addView(navItem(i).also { nav += it }.root, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        onBackPressedDispatcher.addCallback(this, backToOverview)
        showTab(OVERVIEW)
        val root = ui.column(ui.topBar("Yaris Doctor", end = settings)).apply {
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(ui.divider(R.color.hairline))
            addView(bar)
        }
        ui.fitSystemBars(root)
        setContentView(root)
        ui.enter(listOf(status, health, instrument, tiles, sootCard, regenCard))
        requestPermissions(arrayOf(BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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

    /**
     * Live while visible: the service pushes a reading every poll cycle, so the screen redraws at the pace the car
     * answers (about 1-2 s) instead of waiting out a timer. A [TICK_MS] tick stays underneath for everything that isn't
     * a reading: the wall clock, other phones' rows from the hub, and a service that stopped pushing. A dongle that
     * answers very fast is held to [MIN_REDRAW_MS], so neither the screen nor SQLite works faster than the eye follows.
     */
    override fun onResume() {
        super.onResume()
        if (tab == MAINTENANCE) maintenance.load() // back from Settings: the units may have changed
        ticker = lifecycleScope.launch {
            var drawn = 0L
            merge(
                flow { while (true) { emit(Unit); delay(TICK_MS) } },
                Live.reading.map { Unit },
            ).collect {
                val now = SystemClock.elapsedRealtime()
                if (now - drawn < MIN_REDRAW_MS) return@collect
                drawn = now
                refreshNow()
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
        if (scroll.childCount > 0) scrollY[from] = scroll.scrollY
        tab = i
        nav.forEachIndexed { k, item -> item.paint(k == i) }
        backToOverview.isEnabled = i != OVERVIEW
        scroll.removeAllViews()
        // The Rules tab is rebuilt on each visit so its descriptions follow a unit-system change made in Settings
        val v = when (i) { DATA -> allData.view; RULES -> RulesTab(this, ui).view; MAINTENANCE -> maintenance.view; else -> overview }
        scroll.addView(v)
        scroll.post { scroll.scrollTo(0, scrollY[i]) } // after layout, or a taller tab's position is clamped
        // Slide in from the side of the chip that was tapped, so the content follows the chip row
        if (from != i && Ui.animationsOn()) {
            v.alpha = 0f
            v.translationX = ui.px(if (i > from) 24 else -24).toFloat()
            v.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(280)
                .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f)).start()
        }
        if (i == MAINTENANCE) maintenance.load()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch { refreshNow() }
    }

    /** Reads off the main thread, then draws the visible tab. */
    private suspend fun refreshNow() {
        when (tab) {
            OVERVIEW -> vm.load().let { if (tab == OVERVIEW) render(it) }
            DATA -> allData.refresh()
            // Rules don't change; the logbook loads per visit (see MaintenanceTab)
        }
    }

    private fun render(s: OverviewState) {
        val last = s.last
        val regens = s.regens
        val now = s.now
        val live = last != null && now - last.t <= Live.LIVE_MS

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
        val units = Prefs.units(this)
        callout.visibility = if (regenNow) View.VISIBLE else View.GONE
        if (regenNow) calloutText.text = "DPF ${last!!.temp?.let { units.temperatureNoSpace(it.toDouble()) } ?: "?"} · " +
            "soot ${last.soot ?: "?"}% · $mins min so far"

        showSoot(last?.soot)
        sootFill.setSoot(last?.soot)
        sootSub.text = last?.let { if (live) "Now" else "At ${fmt(it.t)}" } ?: "No readings yet"
        setPulse(regenNow)

        temp.text = last?.temp?.let { units.temperatureNoSpace(it.toDouble()) } ?: "—"
        tempSub.text = if (live) "Exhaust after the DPF" else "Last reading"
        // shortcut: litres whatever the unit setting (Units has no volume), add gallons if an imperial user asks
        fuel.text = s.fuel?.let { "%.1f L".format(it.second) } ?: "—"
        fuelSub.text = (s.fuel?.let { (t, _) -> if (now - t <= 2 * 60_000L) "In the tank" else "At ${fmt(t)}" } ?: "Read on the next drive") +
            " · tap to log a fill-up"
        val c = s.consumption
        use.text = c?.let { it.per100Km?.let(units::consumptionText) ?: units.fuelRateText(it.litresPerHour) } ?: "—"
        useSub.text = when {
            c == null -> "Shown once driving"
            c.per100Km == null -> "Idling"
            live -> "Last 5 min · estimate"
            else -> "End of the last drive · estimate"
        }
        val gaps = regens.mapNotNull { it.gapM }
        sinceRegen.text = s.metresSinceRegen?.let { "%.0f %s".format(units.distance(it), units.distanceLabel) } ?: "—"
        sinceRegenSub.text = if (gaps.isEmpty()) "Average after two regens" else "Avg %.0f %s between".format(units.distance(gaps.average()), units.distanceLabel)
        regenState.text = if (regenNow) "Burning" else "Off"
        regenState.setTextColor(ui.color(if (regenNow) R.color.primary else R.color.on_instrument))
        regenSub.text = when {
            regenNow -> "$mins min so far"
            regens.lastOrNull()?.end != null -> "Last took ${duration(regens.last())}"
            else -> "None logged yet"
        }

        // Next regen: the soot rise since the last finished regen, against the level regens start at on this car
        val finished = regens.filter { it.end != null }
        val next = forecast(
            last?.soot, finished.lastOrNull()?.sootEnd, s.metresSinceRegen,
            finished.mapNotNull { it.sootStart }, Prefs.model2015(this),
        )
        val kmLeft = next.kmToRegen
        nextRegen.text = when {
            kmLeft == null -> "—"
            kmLeft <= 0.0 -> "Now"
            else -> "~%.0f %s".format(units.distance(kmLeft * 1000), units.distanceLabel)
        }
        nextRegenSub.text = when {
            kmLeft == null -> "drive a little more"
            kmLeft <= 0.0 -> next.triggerSoot?.let { "due at about %.0f %%".format(it) } ?: "due now"
            else -> next.sootPer100Km?.let {
                "soot +%.1f %% per 100 %s".format(units.sootPer100Distance(it), units.distanceLabel)
            } ?: "drive a little more"
        }

        // The series and the regens behind it come from the view model's minute cache: redraw a chart only when that
        // instance changes, not on each live reading (both rebuild a few thousand points)
        val series = s.sootSeries
        if (sootSeriesShown?.let { it.first === series && it.second == units.system } != true) {
            sootSeriesShown = series to units.system
            sootChart.set(
                series.map { it.first }.toLongArray(), series.map { it.second }.toFloatArray(),
                { i -> "${fmt(series[i].first)} · %.0f%% soot".format(series[i].second) }, ::fmtDay,
                regens.map { it.start to (it.end ?: now) },
                // Parked time takes no width. Points are 10-min buckets, so only a skipped bucket counts as parked
                gapMs = Db.SOOT_BUCKET_MS,
            )
            regenTitle.text = if (units.metric) "Kilometres between regens" else "Miles between regens"
            val withGap = regens.filter { it.gapM != null }.takeLast(20)
            regenChart.set(
                withGap.map { it.start }.toLongArray(), withGap.map { units.distance(it.gapM!!).toFloat() }.toFloatArray(),
                { i -> "${fmt(withGap[i].start)} · %.0f %s · ${duration(withGap[i])}".format(units.distance(withGap[i].gapM!!), units.distanceLabel) },
                ::fmtDay,
            )
        }

        dpfAbout.text = "Exhaust restriction right after each regen, in ${units.restrictionLabel} of exhaust. Burning removes the " +
            "soot, so what stays is ash: a rising bar means the filter is filling with ash and needs a clean."
        // The view model reloads the card at most once a minute; redraw only then or on a unit change
        if (dpfShown?.let { it.first === s.dpf && it.second == units.system } != true) {
            dpfShown = s.dpf to units.system
            val d = s.dpf.conditions
            val rate = d.lastOrNull()?.perKm
            dpfTitle.text = if (rate == null) "DPF condition" else
                "DPF condition · fills %.2f per %s".format(units.restrictionPerDistance(rate), units.distanceLabel)
            // Grouped as three counters, the way the ECU's record is laid out
            fun ash(hex: String) = hex.chunked(4).joinToString(" ")
            val changes = s.dpf.ashChanges
            ashText.text = s.dpf.ash?.let { (t, hex) ->
                "ECU ash record (DPF Deterioration Record): ${ash(hex)} · ${fmt(t)}" +
                    // Where it changed, newest first: a local write or the ECU's own ash count moving
                    if (changes.size > 1) "\nChanges:" + changes.joinToString("") { (ct, h) -> "\n${fmt(ct)} · ${ash(h)}" } else ""
            } ?: "ECU ash record: not read yet"
            dpfChart.set(
                d.map { it.regenStart }.toLongArray(), d.map { units.restriction(it.base!!).toFloat() }.toFloatArray(),
                { i -> "${fmt(d[i].regenStart)} · %.1f %s".format(units.restriction(d[i].base!!), units.restrictionLabel) +
                    (d[i].perKm?.let { " · then +%.2f per ${units.distanceLabel}".format(units.restrictionPerDistance(it)) } ?: "") },
                ::fmtDay,
            )
        }

        val alerts = s.alerts // open ones only: a recovered warning stays stored (and on the hub) but leaves the dashboard
        val alertsKey = "${units.system}|" + alerts.joinToString("|") { "${it.key}${it.text}${it.end}" }
        if (alertsKey != healthKey) {
            healthKey = alertsKey
            renderHealth(alerts)
        }

        // Rebuilt only when the list or units change, or the newest regen's floor becomes readable; refresh runs every 5 s
        if (historyShown?.let { it.first === s.history && it.second == units.system } != true) {
            historyShown = s.history to units.system
            renderHistory(s.history, units)
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
        val units = Prefs.units(this)
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
        for (a in open) health.addView(alertRow(a, if (a.level == CRIT) R.color.status_crit else R.color.status_warn, R.color.ink, units))
        health.addView(ui.text(Type.CAPTION, R.color.primary, SpannableString("SEE THE RULES  ↗").apply { setSpan(UnderlineSpan(), 0, 13, 0) }).apply {
            minHeight = ui.px(48)
            gravity = Gravity.CENTER_VERTICAL
            letterSpacing = 0.04f
            contentDescription = "See the rules"
            isClickable = true
            ui.ripple(this)
            setOnClickListener { showTab(RULES) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.px(8) })
        health.addView(ui.divider(R.color.hairline), LinearLayout.LayoutParams(-1, ui.px(1).coerceAtLeast(1)).apply { topMargin = ui.px(8) })
    }

    private fun alertRow(a: Alert, dot: Int, ink: Int, units: Units) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.px(12), 0, 0)
        addView(ui.dot(dot), LinearLayout.LayoutParams(ui.px(10), ui.px(10)).apply { marginEnd = ui.px(12) })
        addView(ui.column(
            ui.text(Type.TITLE_SM, ink, (if (a.level == CRIT) "Critical: " else "") + units.text(a.text)),
            ui.eyebrow(fmt(a.start)).apply { setPadding(0, ui.px(2), 0, 0) },
        ), LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** [regens] with their signs, newest first, capped at 50 rows. */
    private fun renderHistory(regens: List<Regen>, units: Units) {
        history.removeAllViews()
        if (regens.isEmpty()) {
            history.addView(ui.text(Type.BODY_MD, R.color.muted, "None logged yet. Regens appear here after a drive.").apply { gravity = Gravity.CENTER_HORIZONTAL })
            return
        }
        regens.forEachIndexed { i, r ->
            if (i > 0) history.addView(ui.divider())
            val badge = when {
                r.end == null -> ui.pill(ui.text(Type.CAPTION_UPPER, R.color.on_primary, "Ongoing"), R.color.primary)
                r.gapM != null -> ui.pill(ui.text(Type.CAPTION, R.color.ink, "%.0f %s".format(units.distance(r.gapM!!), units.distanceLabel)))
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
                    ui.eyebrow(listOfNotNull(r.floor?.let { "after 5 min $it%" }, r.peakTemp?.let { "peak ${units.temperatureNoSpace(it.toDouble())}" }).joinToString(" · ")).apply {
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

    /** A bottom-bar entry: icon over label; the open tab's icon sits in an orange pill with black ink, the brand's one signal colour. */
    private inner class NavItem(val root: LinearLayout, val pill: FrameLayout, val icon: ImageView, val label: TextView) {
        fun paint(on: Boolean) {
            pill.background = if (on) ui.shape(R.color.primary, 999) else null
            icon.setColorFilter(ui.color(if (on) R.color.on_primary else R.color.muted))
            label.setTextColor(ui.color(if (on) R.color.ink else R.color.muted))
            label.fontVariationSettings = "'wght' ${if (on) 800 else 600}"
            root.isSelected = on
        }
    }

    private fun navItem(i: Int): NavItem {
        val (title, iconId) = TABS[i]
        val icon = ImageView(this).apply { setImageResource(iconId); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val pill = FrameLayout(this).apply { addView(icon, FrameLayout.LayoutParams(ui.px(24), ui.px(24), Gravity.CENTER)) }
        val label = ui.text(Type.CAPTION, R.color.muted, title).apply { maxLines = 1; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val root = ui.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.px(12), 0, ui.px(14))
            addView(pill, LinearLayout.LayoutParams(ui.px(64), ui.px(32)))
            addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.px(4) })
            contentDescription = title
            isClickable = true
            ui.ripple(this)
            // The open tab again: back to its top, as every Android app's bottom bar does
            setOnClickListener { if (i == tab) scroll.smoothScrollTo(0, 0) else showTab(i) }
        }
        return NavItem(root, pill, icon, label)
    }

    private fun duration(r: Regen) = r.end?.let { "${(it - r.start) / 60_000} min" } ?: "ongoing"
    private fun granted(perm: String) = checkSelfPermission(perm) == PERMISSION_GRANTED

    companion object {
        /** Daily first, reference last. */
        private val TABS = listOf("Overview" to R.drawable.ic_tab_overview, "Data" to R.drawable.ic_tab_data,
            "Maintenance" to R.drawable.ic_tab_maintenance, "Rules" to R.drawable.ic_tab_rules)
        private const val OVERVIEW = 0
        private const val DATA = 1
        private const val MAINTENANCE = 2
        private const val RULES = 3
        /** The fallback redraw: what the screens can't learn from a reading (see [onResume]). */
        private const val TICK_MS = 5_000L
        /** Readings land about every 1-2 s; this only guards against a dongle that answers unusually fast. */
        private const val MIN_REDRAW_MS = 250L
    }
}
