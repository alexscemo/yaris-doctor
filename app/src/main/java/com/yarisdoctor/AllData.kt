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

/** The "All data" tab: every value in [PIDS], filtered by category or name; tap one for its last half hour. */
class AllData(private val a: Activity, private val ui: Ui) {

    private var category: String? = null
    private var query = ""
    private var open: Pid? = null
    private val values = HashMap<String, TextView>() // pid id → value text of the rows on screen
    private val last = HashMap<String, Double>()       // pid id → value at the previous refresh, to flash changes
    private val rows = ui.column()
    private val header = ui.text(Type.BODY_SM, R.color.muted)
    private val chips = LinearLayout(a)
    private val chart = ChartView(a, columns = false, ui.chartColors())

    val view: View = ui.column().apply {
        setPadding(ui.px(16), ui.px(8), ui.px(16), ui.px(32))
        addView(header)
        val search = EditText(a).apply {
            hint = "Search values"
            setSingleLine()
            textSize = Type.BODY_MD.sp
            setTextColor(ui.color(R.color.ink))
            setHintTextColor(ui.color(R.color.muted))
            background = ui.shape(R.color.canvas, 12, R.color.hairline)
            setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); render() }
            })
        }
        addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(12) })
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
        val shown = PIDS.filter { (category == null || it.category == category) && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
        if (shown.isEmpty()) rows.addView(ui.text(Type.BODY_MD, R.color.muted, "No value matches.").apply { setPadding(0, ui.px(16), 0, 0) })
        shown.forEachIndexed { i, p ->
            if (i > 0) rows.addView(ui.divider())
            val value = ui.text(Type.TITLE_SM, R.color.ink, "—").apply {
                gravity = Gravity.END
                setPadding(ui.px(8), ui.px(2), ui.px(8), ui.px(2))
                background = GradientDrawable().apply { cornerRadius = ui.px(6).toFloat(); setColor(Color.TRANSPARENT) }
            }
            values[p.id] = value
            rows.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.px(56)
                setPadding(0, ui.px(10), 0, ui.px(10))
                isClickable = true
                ui.ripple(this)
                setOnClickListener { open = if (open === p) null else p; render() }
                addView(ui.column(
                    ui.text(Type.BODY_MD, R.color.ink, p.name),
                    ui.text(Type.BODY_SM, R.color.muted, if (category == null) p.category else "Request 21 ${p.lid}"),
                ), LinearLayout.LayoutParams(0, -2, 1f))
                addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.px(12) })
            })
            if (p === open) rows.addView(ui.card(R.color.surface_card, 16,
                ui.text(Type.BODY_SM, R.color.muted, "Last 30 minutes it was read · tap or drag to read values").apply { setPadding(0, 0, 0, ui.px(8)) },
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
            System.currentTimeMillis() - newest <= 30_000 -> "Live from the engine ECU · ${PIDS.size} values"
            else -> "From the last drive, ${fmt(newest)}"
        }
        for (p in PIDS) {
            val v = latest[p.lid]?.let { p.decode(it.second) }
            val view = values[p.id]
            view?.text = v?.let(p::format) ?: "—"
            if (v == null) continue
            val before = last.put(p.id, v)
            if (view != null && before != null && v != before) flash(view, v > before)
        }
        open?.let { p ->
            val series = db.frameSeries(p.lid, 30 * 60_000L).mapNotNull { (t, hex) -> p.decode(hex)?.let { t to it } }
            chart.set(
                series.map { it.first }.toLongArray(), series.map { it.second.toFloat() }.toFloatArray(),
                { i -> "${time(series[i].first)} · ${p.format(series[i].second)}" },
                ::time,
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

    private fun fmt(t: Long) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(t)
    private fun time(t: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(t)
}
