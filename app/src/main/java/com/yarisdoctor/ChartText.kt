package com.yarisdoctor

/**
 * The chart's accessibility text, kept pure so it is unit-tested. It names what is plotted, how many
 * points it holds, the time span and the value range. Times go through [fmtTime], so no raw epoch
 * reaches TalkBack. Kept under ~140 characters: one concise sentence for the focus announcement.
 */
fun chartDescription(label: String, times: LongArray, values: FloatArray, fmtTime: (Long) -> String): String {
    if (values.isEmpty()) return "No data yet"
    val name = label.trim().ifBlank { "Chart" }.replaceFirstChar { it.uppercase() }
    val who = if (name.endsWith("chart", ignoreCase = true)) name else "$name chart"
    val points = if (values.size == 1) "1 point" else "${values.size} points"
    val whenText = when {
        times.size > 1 -> "${fmtTime(times.first())} to ${fmtTime(times.last())}"
        times.isNotEmpty() -> fmtTime(times.first())
        else -> "time unknown"
    }
    val last = values.last()
    val lo = values.minOrNull()!!
    val hi = values.maxOrNull()!!
    val range = if (lo == hi) "always ${num(last)}" else "last ${num(last)}, min ${num(lo)}, max ${num(hi)}"
    return "$who · $points · $whenText · $range"
}

/**
 * Names a chart from its own readout ("Mon 20 Oct, 10:23 · 42% soot" → "soot"), so the callers keep
 * saying what they measure in one place: the same string they already use for the touched value.
 * Times, numbers and units alone carry no name, so those fall back to "" (and a plain "Chart").
 */
fun chartLabel(readout: String): String =
    readout.substringAfter('·', "")
        .split(' ', ',', '·')
        .map { it.trim().trimEnd('.') }
        .firstOrNull { it.length > 2 && it.all(Char::isLetter) && it.lowercase() !in UNITS }
        ?: ""

private fun num(v: Float): String {
    val whole = Math.round(v)
    return if (kotlin.math.abs(v - whole) < 0.05f) whole.toString() else "%.1f".format(v)
}

/** Units and connectives that appear in readouts but say nothing about what is measured. */
private val UNITS = setOf(
    "per", "then", "now", "at", "in", "to", "of",
    "km", "mi", "min", "ms", "rpm", "kpa", "bar", "l", "c",
)
