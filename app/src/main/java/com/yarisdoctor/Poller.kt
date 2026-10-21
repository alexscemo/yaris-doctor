package com.yarisdoctor

/**
 * Picks what to ask the ECU each cycle, so the full table (35 requests) doesn't stall the readings that matter, and keeps
 * only replies that match the table. [send] is Elm.send: the cleaned reply, or null on timeout / ELM error.
 */
class Poller(private val send: (String) -> String?) {
    private var count = true // ELM "response count" suffix; turned off when the dongle doesn't take it
    private var countFails = 0
    private var seen = false
    private val misses = HashMap<String, Int>()
    private val lastOk = HashMap<String, Long>()
    private var medium = 0
    private var slow = 0

    /** Request code ("21DD") → reply ("61DD…") for each one answered this cycle; empty when the ECU said nothing. */
    fun cycle(now: Long, always: Collection<String>): Map<String, String> {
        val codes = LinkedHashSet<String>()
        codes += always
        codes += FAST
        repeat(2) { codes += MEDIUM[medium++ % MEDIUM.size] }
        // Slow items: the next one not read in the last few minutes
        for (i in SLOW.indices) {
            val c = SLOW[slow++ % SLOW.size]
            if (now - (lastOk[c] ?: Long.MIN_VALUE / 2) > SLOW_MS) { codes += c; break }
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

    /** A reply for [code] of the length the table says, one retry; codes not in the table (user's own) are taken as they come. */
    private fun ask(code: String): String? {
        val len = PID_LENGTHS[code] ?: return send(code)
        val want = (len + 2) * 2
        // Exact length, header and hex only: catches cut-off or mixed frames and ELM chatter like BUFFER FULL
        fun ok(r: String?) = r != null && r.length == want && r.startsWith("6" + code.substring(1)) && r.all { it in '0'..'9' || it in 'A'..'F' }
        // Single-frame replies (≤ 7 bytes): "21 0C 1" lets the ELM return on the first reply instead of waiting out its timeout
        val single = count && len + 2 <= 7
        val first = send(if (single) "${code}1" else code)
        if (ok(first)) return first
        val second = send(code)
        if (!ok(second)) return null
        if (single && ++countFails >= 3) count = false
        return second
    }

    companion object {
        /**
         * Every cycle, in this order. The service puts 21DD (rail pressure target) last of its own codes, so the actual
         * rail pressure (2123) follows it at once; pairs a rule compares sit next to each other. Coolant and MAP change
         * slowly and are MEDIUM, to keep the cycle short on a slow dongle.
         */
        val FAST = listOf("2123", "210C", "210D", "21EF",
            // MAF and the MAF / estimated MAF ratio, together so the two line up
            "2110", "21A3",
            // EGR target (2C) and actual position (EA) are compared by egr_tracking
            "212C", "21EA",
            // Usually the service's own DPF codes, sent first already; listed so a user's other codes don't drop them
            "21DD", "21DF", "21F1")
        /** Two per cycle, in turn. */
        val MEDIUM = listOf("2104", "2105", "210B", "210F", "2111", "2124", "2134", "2142", "2149", "214A", "214C", "21AD", "21DA", "21DE", "21E6", "21EE")
        /** Counters and learned values: once a session, then every [SLOW_MS]. */
        val SLOW = listOf("211F", "2121", "2130", "2131", "2133", "214D", "214E", "21E1")
        const val SLOW_MS = 5 * 60_000L
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
