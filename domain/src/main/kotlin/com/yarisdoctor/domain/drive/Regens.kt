package com.yarisdoctor.domain.drive

import com.yarisdoctor.domain.model.MERGE_SLACK_MS
import com.yarisdoctor.domain.model.Regen

/**
 * [rows] sorted by start. A finished regen that starts within [MERGE_SLACK_MS] of the previous finished one's end
 * is the same burn seen by another phone. Open regens are never merged, so a phone killed mid-burn can't swallow later ones.
 */
fun mergeRegens(rows: List<Regen>): List<Regen> {
    val out = mutableListOf<Regen>()
    for (r in rows) {
        val last = out.lastOrNull()
        if (last?.end != null && r.end != null && r.start <= last.end + MERGE_SLACK_MS) {
            if (r.end > last.end) out[out.lastIndex] = last.copy(end = r.end, sootEnd = r.sootEnd)
        } else {
            out += r
        }
    }
    return out
}

/**
 * [rows] sorted by start. Two finished regens are the same burn split by an ignition cycle when the second starts within
 * [gapMs] of the first's end and its soot carries on from the first's (not materially lower: the ECU's floor reading is a
 * few points off). A burn does not restart within half an hour of stopping, so a drop means a real second burn. Open
 * regens are never merged. The merged row keeps the first start and soot start, the second end and soot end.
 */
fun mergeSameBurn(rows: List<Regen>, gapMs: Long = 30 * 60_000L): List<Regen> {
    val out = mutableListOf<Regen>()
    for (r in rows) {
        val last = out.lastOrNull()
        val end = last?.end
        val sameBurn = last != null && end != null && r.end != null && r.start - end in 0..gapMs &&
            last.sootEnd != null && r.sootStart != null && r.sootStart >= last.sootEnd - 5
        if (sameBurn) out[out.lastIndex] = last.copy(
            end = r.end,
            sootEnd = r.sootEnd,
            floor = listOfNotNull(last.floor, r.floor).minOrNull(),
            peakTemp = listOfNotNull(last.peakTemp, r.peakTemp).maxOrNull(),
        ) else out += r
    }
    return out
}

/** How much of the soot present when the regen began has burned off (0-100), or null when unknown. */
fun burnPercent(sootStart: Int?, sootNow: Int?): Int? =
    if (sootStart == null || sootNow == null || sootStart <= 0) null
    else ((sootStart - sootNow) * 100 / sootStart).coerceIn(0, 100)
