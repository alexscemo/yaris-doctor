package com.yarisdoctor

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.yarisdoctor.Ui.Type

/** The "Rules" tab: every health check, what it looks for, and how often the values it reads are polled. Static. */
class RulesTab(private val a: Activity, private val ui: Ui) {

    val view: View = ui.column().apply {
        setPadding(ui.px(16), ui.px(8), ui.px(16), ui.px(32))
        addView(ui.text(Type.BODY_SM, R.color.muted,
            "The phone checks the engine values while you drive. A rule has to stay true for its hold time before it warns, so " +
                "one odd reading never does. Values are read in tiers: key ones every ${Poller.CYCLE_MS / 1000} s " +
                "(${Poller.REGEN_CYCLE_MS / 1000} s during a regen), the rest about every ${Poller.MEDIUM_MS / 1000} s, counters every " +
                "${Poller.SLOW_MS / 60_000} min. A slow Bluetooth link stretches every tier."))
        section("While driving · ${Checker.RULES.size} rules")
        Checker.RULES.forEachIndexed { i, r ->
            if (i > 0) addView(ui.divider())
            addView(entry(r.title, r.level, r.about,
                (if (r.holdMs == 0L) "Warns at once" else "Warns after ${r.holdMs / 1000} s") + " · Reads " +
                    // Grouped by rate: "Coolant Temp, Vehicle Speed every 2 s; Engine Run Time every 5 min"
                    r.uses.groupBy { name -> Poller.pollRate(PIDS.first { it.name == name }.lid) }
                        .entries.joinToString("; ") { (rate, names) -> "${names.joinToString(", ")} $rate" }))
        }
        section("After each regen · ${TREND_RULES.size} rules")
        TREND_RULES.forEachIndexed { i, (_, title, about) ->
            if (i > 0) addView(ui.divider())
            addView(entry(title, WARN, about, "Reads the regen history · checked when a regen ends and when the app starts"))
        }
    }

    private fun LinearLayout.section(title: String) =
        addView(ui.text(Type.CAPTION_UPPER, R.color.muted, title), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(24); bottomMargin = ui.px(4) })

    private fun entry(title: String, level: Int, about: String, how: String) = ui.column(
        LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(Type.TITLE_SM, R.color.ink, title), LinearLayout.LayoutParams(0, -2, 1f))
            addView(if (level == CRIT) ui.pill(ui.text(Type.CAPTION, R.color.on_primary, "Critical"), R.color.primary)
                else ui.pill(ui.text(Type.CAPTION, R.color.ink, "Warning")))
        },
        ui.text(Type.BODY_SM, R.color.body, about).apply { setPadding(0, ui.px(4), 0, 0) },
        ui.text(Type.BODY_SM, R.color.muted, how).apply { setPadding(0, ui.px(4), 0, 0) },
    ).apply { setPadding(0, ui.px(12), 0, ui.px(12)) }
}
