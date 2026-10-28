package com.yarisdoctor

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.DialogInterface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.yarisdoctor.Ui.Type
import com.yarisdoctor.domain.maintenance.Due
import com.yarisdoctor.domain.maintenance.Job
import com.yarisdoctor.domain.maintenance.KINDS
import com.yarisdoctor.domain.maintenance.MILEAGE
import com.yarisdoctor.domain.maintenance.NEWEST
import com.yarisdoctor.domain.maintenance.OIL
import com.yarisdoctor.domain.maintenance.OTHER
import com.yarisdoctor.domain.maintenance.anchor
import com.yarisdoctor.domain.maintenance.carKm
import com.yarisdoctor.domain.maintenance.defaultEveryKm
import com.yarisdoctor.domain.maintenance.title
import com.yarisdoctor.domain.maintenance.upcoming
import com.yarisdoctor.domain.model.Units
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Maintenance" tab: the car's mileage, the reminders coming up and the logbook. Always the real log, even in
 * Debug mode: it is the owner's record, not sensor data. Loaded per visit and after each change, never on the live
 * tick, so an open dialog or the scroll position survives the readings.
 */
class MaintenanceTab(private val a: ComponentActivity, private val ui: Ui) {

    /** One load: the logbook, this phone's GPS odometer, and the regens since the newest oil change (all, cut short). */
    private class State(val jobs: List<Job>, val odoM: Double, val today: Long, val oilRegens: Pair<Int, Int>?)

    private var state: State? = null
    private val body = ui.column()

    val view: View = ui.column().apply {
        setPadding(ui.px(20), ui.px(8), ui.px(20), ui.px(40))
        addView(ui.eyebrow("Maintenance"))
        addView(ui.text(Type.DISPLAY_MD, R.color.ink, "The car's logbook").apply { setPadding(0, ui.px(10), 0, 0) })
        addView(ui.text(Type.DISPLAY_MD, R.color.ink, "and what comes next").apply { fontVariationSettings = "'wght' 400" })
        addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(20) })
    }

    fun load() {
        a.lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) {
                val db = a.container.real
                val jobs = db.jobs()
                val oil = jobs.filter { it.done && it.kind == OIL }.maxWithOrNull(NEWEST)
                val regens = oil?.day?.let { day ->
                    val from = LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val since = db.regens().filter { it.start >= from && it.end != null }
                    // Under 10 min, as regen_interrupted counts: each stopped burn's after injection still thins the oil
                    since.size to since.count { it.end!! - it.start < 10 * 60_000L }
                }
                State(jobs, db.odometer(), LocalDate.now().toEpochDay(), regens)
            }
            state = s
            render(s)
        }
    }

    private fun render(s: State) {
        body.removeAllViews()
        val units = Prefs.units(a)
        body.addView(mileageCard(s, units))

        body.addView(section("Coming up"))
        val due = upcoming(s.jobs, s.odoM, s.today)
        body.addView(
            if (due.isEmpty()) ui.text(Type.BODY_SM, R.color.muted, "Nothing yet. Log a job with a reminder, or plan one.")
            else ui.group(*due.map { dueRow(it, s, units) }.toTypedArray()),
        )

        body.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.eyebrow("Logbook").apply { setPadding(ui.px(4), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("+ Add job") { pickKind() })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(20); bottomMargin = ui.px(10) })
        val log = s.jobs.filter { it.done }.sortedWith(NEWEST.reversed())
        body.addView(
            if (log.isEmpty()) ui.text(Type.BODY_SM, R.color.muted, "No entries yet. Add past services too: each reminder counts from the newest job of its kind.")
            else ui.group(*log.map { logRow(it, units) }.toTypedArray()),
        )
    }

    /** The dark instrument card: the car's odometer as the one big number, and where it comes from. */
    private fun mileageCard(s: State, units: Units): View {
        val car = carKm(s.jobs, s.odoM)
        val from = anchor(s.jobs)
        return ui.column(
            ui.eyebrow("Mileage", R.color.on_instrument_soft),
            ui.text(Type.STAT, R.color.on_instrument, ui.signal(car?.let { units.distanceFromKmText(it, grouped = true) } ?: "Not set").first)
                .apply { setPadding(0, ui.px(14), 0, ui.px(6)) },
            ui.text(Type.BODY_SM, R.color.on_instrument_soft, if (from == null) "Enter the odometer from the dashboard. GPS adds every drive the app logs."
                else "Your reading of ${units.distanceFromKmText(from.km!!, grouped = true)} on ${date(from.day!!)}, plus GPS since"),
        ).apply {
            background = ui.shape(R.color.instrument, 20)
            setPadding(ui.px(20), ui.px(18), ui.px(20), ui.px(20))
            addView(button("Set mileage") {
                edit(Job(0, s.today, MILEAGE, "", true, car?.let { (it * 1000).roundToLong() / 1000.0 }, null, null, null))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.px(16) })
        }
    }

    /** "in 2,300 km · at 147,500 km · by 3 Mar 2027" over when it was last done; a warning dot when overdue. */
    private fun dueRow(d: Due, s: State, units: Units): View {
        val j = d.job
        val dist = { km: Double -> units.distanceFromKmText(km, grouped = true) }
        val timing = listOfNotNull(
            d.kmLeft?.let { if (it <= 0) "${dist(-it)} overdue" else "in ${dist(it)}" },
            d.dueKm?.let { "at ${dist(it)}" },
            d.dueDay?.let { when { it == s.today -> "due today"; it < s.today -> "was due ${date(it)}"; else -> "by ${date(it)}" } },
        ).joinToString(" · ")
        val unknownKm = d.kmLeft == null && (if (j.done) j.everyKm != null else j.km != null)
        val since = if (!j.done) j.note.takeIf { it.isNotBlank() && j.kind != OTHER } // the pill says "Planned"
            else "Last done ${date(j.day!!)}" + (s.oilRegens?.takeIf { j.kind == OIL }?.let { (n, short) ->
                " · ${plural(n, "regen")} since" + if (short > 0) " ($short cut short)" else ""
            } ?: "")
        return row(title(j), listOfNotNull(timing.ifEmpty { null }, "Set the mileage to count the distance".takeIf { unknownKm }, since),
            dot = if (d.overdue) R.color.status_warn else null,
            end = if (j.done) null else ui.pill(ui.text(Type.CAPTION, R.color.muted, "Planned"))) { edit(j) }
    }

    /** Date, odometer and note, then the reminder it sets. */
    private fun logRow(j: Job, units: Units): View {
        val first = listOfNotNull(date(j.day!!), j.km?.let { units.distanceFromKmText(it, grouped = true) },
            j.note.takeIf { it.isNotBlank() && j.kind != OTHER }).joinToString(" · ")
        val every = listOfNotNull(j.everyKm?.let { units.distanceFromKmText(it.toDouble(), grouped = true) },
            j.everyMonths?.let { plural(it, "month") }).joinToString(" or ")
        return row(title(j), listOfNotNull(first, "Remind every $every".takeIf { every.isNotEmpty() })) { edit(j) }
    }

    private fun row(title: String, lines: List<String>, dot: Int? = null, end: View? = null, onClick: () -> Unit) = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.px(64)
        setPadding(ui.px(20), ui.px(14), ui.px(20), ui.px(14))
        if (dot != null) addView(ui.dot(dot), LinearLayout.LayoutParams(ui.px(10), ui.px(10)).apply { marginEnd = ui.px(12) })
        addView(ui.column(ui.text(Type.TITLE_SM, R.color.ink, title)).apply {
            for (l in lines) addView(ui.text(Type.BODY_SM, R.color.muted, l).apply { setPadding(0, ui.px(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) addView(end, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.px(12) })
        if (dot != null) contentDescription = "Overdue: $title, ${lines.joinToString(", ")}"
        ui.ripple(this)
        setOnClickListener { onClick() }
    }

    /** Solid signal-orange pill with black text: the tab's actions. */
    private fun button(label: String, onClick: () -> Unit) =
        ui.pill(ui.text(Type.CAPTION, R.color.on_primary, label), R.color.primary).apply {
            minimumHeight = ui.px(44)
            setPadding(ui.px(18), 0, ui.px(18), 0)
            isClickable = true
            ui.ripple(this)
            setOnClickListener { onClick() }
        }

    private fun section(title: String) = ui.eyebrow(title).apply { setPadding(ui.px(4), ui.px(28), 0, ui.px(10)) }

    private fun pickKind() {
        val s = state ?: return
        AlertDialog.Builder(a)
            .setTitle("What was done?")
            .setItems(KINDS.toTypedArray()) { _, i ->
                val kind = KINDS[i]
                // Its kind's last interval, so a reminder is set once and carried on
                val last = s.jobs.filter { it.done && it.kind == kind && kind != OTHER }.maxWithOrNull(NEWEST)
                val car = carKm(s.jobs, s.odoM)?.let { (it * 1000).roundToLong() / 1000.0 }
                edit(Job(0, s.today, kind, "", true, car, null, last?.everyKm ?: defaultEveryKm(kind), last?.everyMonths))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Adds ([j] with id 0) or edits a line. Done lines take a date up to today and set the next reminder; planned ones a
     * date and/or an odometer. Distances are typed in the display unit and stored in km.
     */
    private fun edit(j: Job) {
        val s = state ?: return
        val units = Prefs.units(a)
        val mileage = j.kind == MILEAGE
        var done = j.done
        var day = j.day
        fun shown(km: Double) = units.distanceFromKm(km).roundToLong().toString()

        val prefill = j.km?.let(::shown)
        val km = field(prefill, "Odometer")
        val note = EditText(a).apply {
            setText(j.note)
            hint = if (j.kind == OTHER) "e.g. Wiper blades" else "e.g. parts or garage"
            setSingleLine()
        }
        val shownEvery = j.everyKm?.let { shown(it.toDouble()) }
        val everyKm = field(shownEvery, "none")
        val everyMonths = field(j.everyMonths?.toString(), "none")
        val kmLabel = ui.eyebrow("")
        val dateText = ui.text(Type.TITLE_SM, R.color.ink)
        val chips = LinearLayout(a)
        val dateRow = LinearLayout(a)
        val interval = ui.column(
            ui.eyebrow("Remind me again after").apply { setPadding(0, ui.px(20), 0, 0) },
            LinearLayout(a).apply {
                setPadding(0, ui.px(8), 0, 0)
                // Labelled above, not only by the hint: the hint goes once a number is typed
                fun labelled(label: String, f: EditText) = ui.column(ui.text(Type.BODY_SM, R.color.muted, label).apply {
                    f.id = View.generateViewId()
                    labelFor = f.id // TalkBack reads the unit, not just the hint
                }, f)
                addView(labelled(units.distanceLabel, everyKm), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.px(16) })
                addView(labelled("months", everyMonths), LinearLayout.LayoutParams(0, -2, 1f))
            },
            ui.text(Type.BODY_SM, R.color.muted, "Distance, months or both; whichever comes first. Leave empty for no reminder."),
        )
        fun sync() {
            chips.removeAllViews()
            chips.addView(ui.chip("Done", done) {
                done = true
                if (day == null || day!! > s.today) day = s.today // a done job can't be in the future
                if (km.text.isEmpty()) km.setText(prefill.orEmpty())
                sync()
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.px(8) })
            chips.addView(ui.chip("Planned", !done) {
                done = false
                // Today's mileage, filled in for done work, would make the plan due at once
                if (km.text.toString() == prefill) km.setText("")
                sync()
            })
            dateText.text = day?.let(::date) ?: "No date"
            dateRow.contentDescription = "Date: ${dateText.text}. Change"
            kmLabel.text = if (done) "Odometer, ${units.distanceLabel}" + (if (mileage) "" else " (optional)") else "Due at, ${units.distanceLabel} (optional)"
            interval.visibility = if (done) View.VISIBLE else View.GONE
        }
        dateRow.apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.px(48)
            background = ui.shape(R.color.surface_alt, 12)
            setPadding(ui.px(16), 0, ui.px(16), 0)
            addView(dateText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.text(Type.CAPTION, R.color.primary, "Change"))
            ui.ripple(this)
            setOnClickListener { pickDate(day ?: s.today, if (done) s.today else null, !done) { day = it; sync() } }
        }
        val box = ui.column().apply {
            setPadding(ui.px(24), ui.px(8), ui.px(24), 0)
            if (!mileage) addView(chips, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.px(16) })
            // A reading anchors the mileage only on its own day (the GPS odometer is today's), so it is always today
            if (!mileage) {
                addView(ui.eyebrow("Date").apply { setPadding(0, 0, 0, ui.px(8)) })
                addView(dateRow)
            }
            addView(kmLabel.apply { setPadding(0, ui.px(20), 0, 0) })
            addView(km)
            if (!mileage) {
                addView(ui.eyebrow(if (j.kind == OTHER) "What was done" else "Note").apply { setPadding(0, ui.px(20), 0, 0) })
                addView(note)
                addView(interval)
            }
        }
        sync()
        val dialog = AlertDialog.Builder(a)
            .setTitle(if (mileage) "Odometer reading" else j.kind)
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (j.id != 0L) setNeutralButton("Delete", null) }
            .show()
        // Set after show(): a listener given to the builder would close the dialog on bad input too
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val kmIn = number(km, 0, 2_000_000) ?: return@setOnClickListener toast("Odometer: a whole number up to 2,000,000")
            val everyKmIn = number(everyKm, 1, 500_000) ?: return@setOnClickListener toast("Remind after: up to 500,000 ${units.distanceLabel}")
            val monthsIn = number(everyMonths, 1, 240) ?: return@setOnClickListener toast("Remind after: 1 to 240 months")
            val noteIn = note.text.toString().trim().take(200)
            when {
                mileage && kmIn.value == null -> return@setOnClickListener toast("Enter the odometer reading")
                j.kind == OTHER && noteIn.isEmpty() -> return@setOnClickListener toast("Say what was done")
                !done && day == null && kmIn.value == null -> return@setOnClickListener toast("Give a date, an odometer reading or both")
            }
            val toKm = { v: Long -> v * units.distancePerUnit / 1000 }
            dialog.dismiss()
            save { db ->
                // The GPS odometer is kept for a done line written on its own day: it anchors the mileage and counts the distance
                val odoM = when {
                    !done -> null
                    j.done && j.odoM != null && j.day == day -> j.odoM
                    day == LocalDate.now().toEpochDay() -> db.odometer()
                    else -> null
                }
                // An untouched field keeps its stored km: miles rounded for display would drift on every save
                db.putJob(Job(j.id, day, j.kind, noteIn, done, kmIn.value?.let { if (it.toString() == prefill) j.km else toKm(it) }, odoM,
                    // Kept on a planned line too (only done lines remind), so marking it done later keeps its interval
                    everyKmIn.value?.let { if (it.toString() == shownEvery) j.everyKm else toKm(it).roundToLong().toInt() }, monthsIn.value?.toInt()))
            }
        }
        if (j.id != 0L) dialog.getButton(DialogInterface.BUTTON_NEUTRAL).apply {
            setTextColor(ui.color(R.color.status_crit))
            setOnClickListener {
                val confirm = AlertDialog.Builder(a)
                    .setTitle("Delete this entry?")
                    .setMessage("${title(j)}${j.day?.let { " · ${date(it)}" } ?: ""}. This can't be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        dialog.dismiss()
                        save { it.deleteJob(j.id) }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                confirm.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ui.color(R.color.status_crit))
            }
        }
    }

    /** [max]: the latest pickable day (today for done work), null for none; [clearable] adds "No date". */
    private fun pickDate(from: Long, max: Long?, clearable: Boolean, onPick: (Long?) -> Unit) {
        val d = LocalDate.ofEpochDay(from)
        DatePickerDialog(a, R.style.AppDatePicker, { _, y, m, dom -> onPick(LocalDate.of(y, m + 1, dom).toEpochDay()) }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
            if (max != null) datePicker.maxDate = LocalDate.ofEpochDay(max + 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
            if (clearable) setButton(DialogInterface.BUTTON_NEUTRAL, "No date") { _, _ -> onPick(null) }
            show()
        }
    }

    /** Writes on IO, then restarts the service so a warning opens or closes now (it rechecks at start), and reloads. */
    private fun save(write: (Db) -> Unit) {
        a.lifecycleScope.launch {
            withContext(Dispatchers.IO) { write(a.container.real) }
            // Not mid-drive: a restart drops the dongle and closes an open regen. The drive's end rechecks instead
            val driving = Live.reading.value?.let { System.currentTimeMillis() - it.sample.t <= Live.LIVE_MS } == true
            if (!driving) DpfService.restart(a)
            load()
        }
    }

    /** A whole number in [min]..[max]; empty is a valid null. Null result = invalid input. */
    private class Num(val value: Long?)

    private fun number(f: EditText, min: Long, max: Long): Num? {
        val t = f.text.toString().trim()
        if (t.isEmpty()) return Num(null)
        return t.toLongOrNull()?.takeIf { it in min..max }?.let { Num(it) }
    }

    private fun field(value: String?, hint: String) = EditText(a).apply {
        inputType = InputType.TYPE_CLASS_NUMBER // digits only: no separators to misread
        setText(value.orEmpty())
        this.hint = hint
        setSingleLine()
    }

    private fun date(day: Long): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(LocalDate.ofEpochDay(day))
    private fun plural(n: Int, what: String) = "%,d $what${if (n == 1) "" else "s"}".format(n)
    private fun toast(s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()
}
