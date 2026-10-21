package com.yarisdoctor

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.yarisdoctor.Ui.Type

/** The "Rules" tab: every health check, what it looks for, and how often the values it reads are polled. Static. */
class RulesTab(private val a: Activity, private val ui: Ui) {

    val view: View = ui.column().apply {
        setPadding(ui.px(20), ui.px(8), ui.px(20), ui.px(40))
        addView(ui.eyebrow("How the checks work").apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(ui.text(Type.DISPLAY_MD, R.color.ink, "Each warning has a rule").apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, ui.px(10), 0, ui.px(12)) })
        addView(ui.text(Type.BODY_SM, R.color.muted,
            "The phone checks the engine values while you drive. A rule has to stay true for its hold time before it warns, so " +
                "one odd reading never does. Requests run back to back, as fast as the dongle answers: key values every cycle, the " +
                "rest every ${Poller.MEDIUM_CYCLES} cycles, counters every ${Poller.SLOW_MS / 60_000} min. The All data tab shows how " +
                "often each value actually updates."))
        section("While driving · ${Checker.RULES.size} rules")
        Checker.RULES.forEachIndexed { i, r ->
            if (i > 0) addView(ui.divider())
            addView(entry(i + 1, r.title, r.level, r.about,
                (if (r.holdMs == 0L) "Warns at once" else "Warns after ${r.holdMs / 1000} s") + " · Reads " +
                    // Grouped by rate: "Coolant Temp, Vehicle Speed every 2 s; Engine Run Time every 5 min"
                    r.uses.groupBy { name -> Poller.pollRate(PIDS.first { it.name == name }.lid) }
                        .entries.joinToString("; ") { (rate, names) -> "${names.joinToString(", ")} $rate" }))
        }
        section("After each regen · ${TREND_RULES.size} rules")
        TREND_RULES.forEachIndexed { i, (_, title, about) ->
            if (i > 0) addView(ui.divider())
            addView(entry(i + 1, title, WARN, about, "Reads the regen history · checked when a regen ends and when the app starts"))
        }
    }

    private fun LinearLayout.section(title: String) =
        addView(ui.text(Type.TITLE_LG, R.color.ink, title), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(32); bottomMargin = ui.px(4) })

    /** Level tile left (status-coloured square), then level label, title, description and how it reads; index top-right. */
    private fun entry(n: Int, title: String, level: Int, about: String, how: String) = LinearLayout(a).apply {
        setPadding(0, ui.px(16), 0, ui.px(16))
        val tile = android.widget.FrameLayout(a).apply {
            background = ui.shape(R.color.surface_alt, 16)
            addView(View(a).apply { background = ui.shape(if (level == CRIT) R.color.status_crit else R.color.status_warn, 2) },
                android.widget.FrameLayout.LayoutParams(ui.px(14), ui.px(14), Gravity.CENTER))
        }
        addView(tile, LinearLayout.LayoutParams(ui.px(56), ui.px(56)).apply { marginEnd = ui.px(16) })
        addView(ui.column(
            ui.eyebrow(if (level == CRIT) "Critical" else "Warning"),
            ui.text(Type.TITLE_MD, R.color.ink, title).apply { setPadding(0, ui.px(4), 0, 0) },
            ui.text(Type.BODY_SM, R.color.body, about).apply { setPadding(0, ui.px(4), 0, 0) },
            ui.text(Type.BODY_SM, R.color.muted, how).apply { setPadding(0, ui.px(6), 0, 0) },
        ), LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.eyebrow("%02d".format(n)), LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.px(12) })
    }
}
