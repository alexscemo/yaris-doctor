package com.yarisdoctor.domain.drive

import com.yarisdoctor.domain.car.PID_LENGTHS
import com.yarisdoctor.domain.car.PID_REGEN
import com.yarisdoctor.domain.car.PID_SOOT
import com.yarisdoctor.domain.car.PID_TEMP

/**
 * Picks what to ask the ECU each cycle, so the full table (35 requests) doesn't stall the readings that matter, and keeps
 * only replies that match the table. [send] is Elm.send: the cleaned reply, or null on timeout / ELM error.
 */
class Poller(private val send: (String) -> String?) {
    // ELM "response count" suffix per reply kind, [single frame, multi-frame]: each turned off after 3 failures in a row (one glitch every few hundred cycles must not turn it off)
    private val count = booleanArrayOf(true, true)
    private val countFails = IntArray(2)
    private var seen = false
    private val misses = HashMap<String, Int>()
    private val lastOk = HashMap<String, Long>()
    private var medium = 0
    private var slow = 0

    /**
     * Request code ("21DD") → reply ("61DD…") for each one answered this cycle; empty when the ECU said nothing. [first]
     * are sent before the rest (tests use it to pick the order).
     */
    fun cycle(now: Long, first: Collection<String> = emptyList()): Map<String, String> {
        val codes = LinkedHashSet<String>()
        codes += first
        codes += FAST
        repeat(2) { codes += MEDIUM[medium++ % MEDIUM.size] }
        // Slow items: the next one not read in the last few minutes (the ash record: the last minute)
        for (i in SLOW.indices) {
            val c = SLOW[slow++ % SLOW.size]
            if (now - (lastOk[c] ?: Long.MIN_VALUE / 2) > if (c == ASH_RECORD) ASH_MS else SLOW_MS) { codes += c; break }
        }
        val out = LinkedHashMap<String, String>()
        var dead = 0
        for (c in codes) {
            if (parked(c)) continue
            val r = ask(c)
            if (r != null) {
                out[c] = r
                misses.remove(c)
                lastOk[c] = now
            } else {
                misses[c] = (misses[c] ?: 0) + 1
                // Ignition off or a silent dongle: each request would wait out its timeout, so don't try them all
                if (out.isEmpty() && ++dead >= 3) break
            }
        }
        if (out.isNotEmpty()) seen = true
        return out
    }

    /** A code that never answers while others do isn't supported by this ECU: stop spending link time on it. */
    private fun parked(code: String) = seen && (misses[code] ?: 0) >= PARK_AFTER

    /** A reply for [code] of the length the table says, one retry. */
    private fun ask(code: String): String? {
        if (code == ASH_RECORD) return send(code).takeIf(::ashRecordOk) ?: send(code).takeIf(::ashRecordOk)
        val len = PID_LENGTHS.getValue(code)
        val want = (len + 2) * 2
        // Exact length, header and hex only: catches cut-off or mixed frames and ELM chatter like BUFFER FULL
        fun ok(r: String?) = r != null && r.length == want && r.startsWith("6" + code.substring(1)) && r.all { it in '0'..'9' || it in 'A'..'F' }
        // "21 0C 1" lets the ELM return on the first reply instead of waiting out its timeout. A genuine ELM counts a
        // multi-frame reply as one, clones may not (they cut it short), so single- (≤ 7 bytes) and multi-frame fall back apart
        val kind = if (len + 2 <= 7) 0 else 1
        val suffix = count[kind]
        val first = send(if (suffix) "${code}1" else code)
        if (ok(first)) { countFails[kind] = 0; return first }
        val second = send(code)
        if (!ok(second)) return null
        if (suffix && ++countFails[kind] >= 3) count[kind] = false
        return second
    }

    companion object {
        /**
         * Every cycle, in this order. The DPF codes first, with 21DD (soot, and the rail pressure target) last of them so
         * the actual rail pressure (2123) follows it at once; pairs a rule compares sit next to each other. Coolant and MAP
         * change slowly and are MEDIUM, to keep the cycle short on a slow dongle.
         */
        val FAST = listOf(PID_TEMP, PID_REGEN, PID_SOOT, "2123", "210C", "210D", "21EF",
            // MAF and the MAF / estimated MAF ratio, together so the two line up
            "2110", "21A3",
            // EGR target (2C) and actual position (EA) are compared by egr_tracking
            "212C", "21EA")
        /** Two per cycle, in turn. */
        val MEDIUM = listOf("2104", "2105", "210B", "210F", "2111", "2124", "2134", "2142", "2149", "214A", "214C", "21DA", "21DE", "21DF", "21E6", "21EE")
        /** Counters and learned values: once a session, then every [SLOW_MS] ([ASH_MS] for the ash record). */
        val SLOW = listOf("211F", "2121", "2130", "2131", "2133", "214D", "214E", "21E1", "A918")

        /**
         * The DPF ash ("deterioration") record, read once a cycle at [ASH_MS]: `A9 18` → `E9 18` + 13 bytes, the last the
         * XOR of the other 12. Format undocumented, so it is stored raw.
         */
        const val ASH_RECORD = "A918"

        fun ashRecordOk(r: String?): Boolean {
            if (r == null || r.length != 30 || !r.startsWith("E918") || !r.all { it in '0'..'9' || it in 'A'..'F' }) return false
            val b = r.substring(4).chunked(2).map { it.toInt(16) }
            return b.dropLast(1).fold(0) { x, v -> x xor v } == b.last()
        }
        /** OBD mode 09 calibration ID(s), no longer asked: older builds stored one under this lid, still exported. */
        const val CAL_ID = "0904"
        const val SLOW_MS = 5 * 60_000L
        /** Often enough to tie the ash record's counters to what the car did between reads (one read is ~40 ms). */
        const val ASH_MS = 60_000L
        const val PARK_AFTER = 5
        /** Two MEDIUM requests per cycle, so each comes round every this many cycles. */
        val MEDIUM_CYCLES = (MEDIUM.size + 1) / 2

        /**
         * How often [lid]'s request is sent while driving, for the Rules tab. Cycles run back to back, so their length is
         * whatever the dongle and ECU manage; the All data tab shows the measured interval.
         */
        fun pollRate(lid: String): String = when ("21$lid") {
            in FAST -> "every cycle"
            in MEDIUM -> "every $MEDIUM_CYCLES cycles"
            else -> "every ${SLOW_MS / 60_000} min"
        }
    }
}
