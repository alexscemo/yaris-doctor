package com.yarisdoctor

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.yarisdoctor.Ui.Type
import java.text.SimpleDateFormat
import java.util.Locale

/** The "All data" tab: every value in [PIDS], filtered by category or name; tap one for its chart over the last trip. */
class AllData(private val a: Activity, private val ui: Ui) {

    private var category: String? = null
    private var query = ""
    private var open: Pid? = null
    private val values = HashMap<String, TextView>() // pid id → value text of the rows on screen
    private val rates = HashMap<String, TextView>()  // pid id → "every 0.8 s" under its name
    private val last = HashMap<String, Double>()       // pid id → value at the previous refresh, to flash changes
    private val rows = ui.column()
    private val header = ui.text(Type.BODY_SM, R.color.muted)
    private val chips = LinearLayout(a)
    private val chart = ChartView(a, columns = false, ui.chartColors())

    val view: View = ui.column().apply {
        setPadding(ui.px(20), ui.px(8), ui.px(20), ui.px(40))
        addView(ui.eyebrow("Live data"))
        // Two weights on one heading: the subject heavy, the rest regular
        addView(ui.text(Type.DISPLAY_MD, R.color.ink, "Every value").apply { setPadding(0, ui.px(10), 0, 0) })
        addView(ui.text(Type.DISPLAY_MD, R.color.ink, "the engine reports").apply { fontVariationSettings = "'wght' 400" })
        addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(12) })
        val search = EditText(a).apply {
            hint = "Search values"
            setSingleLine()
            textSize = Type.BODY_MD.sp
            ui.style(this, Type.BODY_MD)
            setTextColor(ui.color(R.color.ink))
            setHintTextColor(ui.color(R.color.muted))
            background = ui.shape(R.color.surface_alt, 12)
            minHeight = ui.px(48)
            setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); render() }
            })
        }
        addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) })
        addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(12) })
        addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(8) })
        renderChips()
        render()
    }

    private fun renderChips() {
        chips.removeAllViews()
        for (c in listOf<String?>(null) + PIDS.map { it.category }.distinct().sorted()) {
            chips.addView(ui.chip(c ?: "All", c == category) { category = c; renderChips(); render() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.px(8) })
        }
    }

    /** Rebuilds the list for the current filter; values are filled in by [refresh]. */
    private fun render() {
        rows.removeAllViews()
        values.clear()
        rates.clear()
        val shown = PIDS.filter { (category == null || it.category == category) && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
        if (shown.isEmpty()) rows.addView(ui.text(Type.BODY_MD, R.color.muted, "No value matches.").apply { setPadding(0, ui.px(16), 0, 0) })
        shown.forEachIndexed { i, p ->
            if (i > 0) rows.addView(ui.divider())
            val value = ui.text(Type.TITLE_MD, R.color.ink, "—").apply {
                gravity = Gravity.END
                setPadding(ui.px(12), ui.px(6), ui.px(12), ui.px(6))
                background = GradientDrawable().apply { cornerRadius = ui.px(999).toFloat(); setColor(Color.TRANSPARENT) }
            }
            values[p.id] = value
            val rate = ui.text(Type.CAPTION, R.color.muted, "").apply { setPadding(0, ui.px(2), 0, 0) }
            rates[p.id] = rate
            rows.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.px(64)
                setPadding(0, ui.px(14), 0, ui.px(14))
                isClickable = true
                ui.ripple(this)
                setOnClickListener { open = if (open === p) null else p; render() }
                addView(ui.column(
                    ui.eyebrow("21 ${p.lid} · ${p.category}"),
                    ui.text(Type.BODY_MD, R.color.ink, p.name).apply { setPadding(0, ui.px(4), 0, 0) },
                    rate,
                ).apply {
                    toyotaNormal(p.name, Prefs.model2015(a))?.let { addView(ui.text(Type.CAPTION, R.color.muted, "Toyota normal: $it").apply { setPadding(0, ui.px(2), 0, 0) }) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.px(12) })
            })
            if (p === open) rows.addView(ui.card(R.color.surface_card, 16,
                ui.eyebrow("Last trip · pinch to zoom").apply { setPadding(0, 0, 0, ui.px(10)) },
            ).apply {
                (chart.parent as? LinearLayout)?.removeView(chart)
                addView(chart, LinearLayout.LayoutParams(-1, ui.px(160)))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.px(8) })
        }
        refresh()
    }

    /** Called every few seconds while the tab is visible. */
    fun refresh() {
        val db = Db.get(a)
        val latest = db.latestFrames()
        val newest = latest.values.maxOfOrNull { it.first }
        header.text = when {
            newest == null -> "No live data yet. Values appear here after a drive with the dongle connected."
            System.currentTimeMillis() - newest <= 30_000 -> "Live from the engine ECU · ${PIDS.size} values · tap a row for the last trip"
            else -> "From the last drive, ${fmt(newest)}"
        }
        // How often each request comes back, measured from its own frames: it follows whatever pace the dongle manages
        val trip = db.tripStart() ?: 0L
        val every = rates.keys.mapNotNull { id -> PIDS.firstOrNull { it.id == id }?.lid }.distinct().associateWith { db.readEvery(it, trip) }
        for (p in PIDS) {
            rates[p.id]?.text = every[p.lid]?.let { "Updates every " + interval(it) } ?: ""
            val v = latest[p.lid]?.let { p.decode(it.second) }
            val view = values[p.id]
            view?.text = v?.let(p::format) ?: "—"
            if (v == null) continue
            val before = last.put(p.id, v)
            if (view != null && before != null && v != before) flash(view, v > before)
        }
        open?.let { p ->
            // Whole last trip, however short; a lid read only once a trip still shows its point(s)
            val series = db.frameSeries(p.lid, trip).mapNotNull { (t, hex) -> p.decode(hex)?.let { t to it } }
            val now = System.currentTimeMillis()
            chart.set(
                series.map { it.first }.toLongArray(), series.map { it.second.toFloat() }.toFloatArray(),
                { i -> "${time(series[i].first)} · ${p.format(series[i].second)}" },
                ::time,
                db.regens().filter { (it.end ?: now) >= trip }.map { it.start to (it.end ?: now) },
            )
        }
    }

    /** Green-tinted for a value that went up, red-tinted for down, fading out over a second. */
    private fun flash(view: TextView, up: Boolean) {
        val bg = view.background as GradientDrawable
        val c = ui.color(if (up) R.color.flash_up else R.color.flash_down)
        ValueAnimator.ofArgb(c, c and 0x00FFFFFF).apply {
            duration = 1200
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun interval(ms: Long) = when {
        ms < 10_000 -> "%.1f s".format(ms / 1000.0)
        ms < 90_000 -> "${ms / 1000} s"
        else -> "${(ms + 30_000) / 60_000} min"
    }

    private fun fmt(t: Long) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(t)
    private fun time(t: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(t)
}
