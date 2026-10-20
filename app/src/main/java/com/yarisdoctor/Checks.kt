package com.yarisdoctor

import kotlin.math.abs

/** One problem the checks found. [level]: [WARN] or [CRIT]. [key] is stable, so the same issue is one alert row. */
data class Finding(val key: String, val level: Int, val text: String)

const val WARN = 1
const val CRIT = 2

/**
 * Health checks on the live values. Thresholds are for the 1ND-TV where a source exists; "estimate" marks the ones
 * borrowed from other Toyota diesels or generic OBD logic. A rule must hold for its hold time before it fires, so one
 * odd reading never raises an alert. One instance per drive: it keeps a little history for the rate rules.
 */
class Checker {
    private class Rule(val key: String, val level: Int, val holdMs: Long, val test: Checker.() -> String?)

    private val values = HashMap<String, Pair<Long, Double>>() // pid name → (t, value)
    private val trueSince = HashMap<String, Long>()
    private val coolant = ArrayDeque<Pair<Long, Double>>()      // last 2 min
    private val soot = ArrayDeque<Pair<Double, Double>>()       // (odo m, soot %), last 10 km outside regens
    private var now = 0L
    private var inRegen = false

    /** Latest value of [name] if read in the last few minutes (slow items are read every 5). */
    private fun v(name: String): Double? = values[name]?.takeIf { now - it.first <= STALE_MS }?.second

    private val running get() = (v("Engine Speed") ?: 0.0) > 400
    private val warm get() = (v("Coolant Temp") ?: 0.0) >= 70
    private val idle get() = running && (v("Engine Speed") ?: 9e9) < 1000 && v("Vehicle Speed") == 0.0
    private fun on(name: String) = v(name) == 1.0

    /** Feeds one cycle's replies (request code → reply) and returns what is wrong right now. */
    fun check(t: Long, replies: Map<String, String>, odoM: Double, regen: Boolean): List<Finding> {
        now = t
        inRegen = regen
        for (p in PIDS) {
            val reply = replies["21${p.lid}"] ?: continue
            // Duplicate names (Target Booster Pressure in DD and DF): the first in the table wins
            if (values[p.name]?.first == t) continue
            p.decode(reply.substring(4))?.let { values[p.name] = t to it }
        }
        v("Coolant Temp")?.let { coolant.addLast(t to it) }
        while (coolant.isNotEmpty() && t - coolant.first().first > 120_000) coolant.removeFirst()
        if (regen) soot.clear() else v("PM Accumulation Ratio")?.let { if (soot.lastOrNull()?.first != odoM) soot.addLast(odoM to it) }
        while (soot.isNotEmpty() && odoM - soot.first().first > 10_000) soot.removeFirst()

        return RULES.mapNotNull { r ->
            val text = r.test(this)
            if (text == null) { trueSince.remove(r.key); return@mapNotNull null }
            val since = trueSince.getOrPut(r.key) { t }
            if (t - since >= r.holdMs) Finding(r.key, r.level, text) else null
        }
    }

    companion object {
        private const val STALE_MS = 6 * 60_000L
        private fun f0(x: Double) = "%.0f".format(x)
        private fun f1(x: Double) = "%.1f".format(x)

        private val RULES = listOf(
            // Thermostat opens at 80-84 °C, cap holds 0.95-1.25 bar, so coolant boils around 120 °C (toyota-club.net 1ND-TV FAQ; TOC)
            Rule("coolant_hot", WARN, 10_000) { v("Coolant Temp")?.takeIf { it in 105.0..<112.0 }?.let { "Coolant is at ${f0(it)} °C, close to boiling. Ease off and watch the gauge." } },
            Rule("coolant_overheat", CRIT, 5_000) { v("Coolant Temp")?.takeIf { it >= 112 }?.let { "Coolant is at ${f0(it)} °C. Stop safely and let the engine cool." } },
            Rule("coolant_rising", WARN, 0) {
                val (t0, c0) = coolant.firstOrNull() ?: return@Rule null
                val c = v("Coolant Temp") ?: return@Rule null
                if (c >= 95 && c - c0 >= 5 && now - t0 >= 30_000) "Coolant rose ${f0(c - c0)} °C in ${(now - t0) / 1000} s, now ${f0(c)} °C." else null
            },
            // Estimate: a stuck-open thermostat keeps a diesel well under its 80-84 °C opening point once driving
            Rule("coolant_cold", WARN, 60_000) {
                val c = v("Coolant Temp") ?: return@Rule null
                if ((v("Engine Run Time") ?: 0.0) >= 1200 && (v("Vehicle Speed") ?: 0.0) > 30 && c < 70) "Engine still at ${f0(c)} °C after 20 min: the thermostat may be stuck open." else null
            },
            // Toyota diesel spec (1HD-T manual; no 1ND-TV figure found): ≥ 29 kPa at idle, ≥ 245 kPa at 3000 rpm
            Rule("oil_pressure_low", CRIT, 3_000) { v("Engine Oil Pressure")?.takeIf { running && it < 30 }?.let { "Oil pressure is ${f0(it)} kPa. Stop the engine and check the oil." } },
            Rule("oil_pressure_rpm", WARN, 5_000) {
                v("Engine Oil Pressure")?.takeIf { (v("Engine Speed") ?: 0.0) >= 2800 && it < 200 }?.let { "Oil pressure is only ${f0(it)} kPa at ${f0(v("Engine Speed")!!)} rpm (expected 245+)." }
            },
            // Generic alternator output: 13.2-14.8 V with the engine running
            Rule("battery_low", WARN, 30_000) { v("Battery Voltage")?.takeIf { running && it < 13.0 }?.let { "Battery at ${f1(it)} V with the engine running: the alternator may not be charging." } },
            Rule("battery_high", WARN, 10_000) { v("Battery Voltage")?.takeIf { running && it > 15.0 }?.let { "Charging voltage is ${f1(it)} V, too high for the battery." } },
            // P2463 is reported from 100-120 % and forced regen locked out from 160-170 % (go-parts P2463; Prado reports)
            Rule("soot_high", WARN, 30_000) { v("PM Accumulation Ratio")?.takeIf { it in 100.0..<140.0 }?.let { "DPF soot is at ${f0(it)} %. Drive 20 min at a steady motorway speed so it can regenerate." } },
            Rule("soot_critical", CRIT, 30_000) { v("PM Accumulation Ratio")?.takeIf { it >= 140 }?.let { "DPF soot is at ${f0(it)} %. Near the level where the car blocks regeneration: see a garage soon." } },
            // Estimate: healthy filters read ~0-1 kPa at warm idle, blocked ones 4-5 kPa (DPF guides; Land Cruiser logs)
            Rule("dpf_pressure_idle", WARN, 30_000) {
                v("DPF Differential Pressure")?.takeIf { warm && idle && !inRegen && it >= 4 }?.let { "DPF pressure is ${f1(it)} kPa at idle (healthy is about 1): the filter may be clogging." }
            },
            // Estimate: active regens run the DPF around 600-650 °C; well above that risks the filter
            Rule("exhaust_hot", WARN, 5_000) {
                listOfNotNull(v("Exhaust Temperature B1S1"), v("Exhaust Temperature B1S2")).maxOrNull()?.takeIf { it >= 800 }?.let { "Exhaust is at ${f0(it)} °C, hotter than a normal regen." }
            },
            // Estimate: generic P0087/P0088 logic, actual rail pressure off target by more than 10-15 % for a few seconds
            Rule("rail_pressure", WARN, 5_000) {
                val a = v("Fuel Press") ?: return@Rule null
                val target = v("Target Common Rail Pressure") ?: return@Rule null
                if ((v("Engine Speed") ?: 0.0) > 600 && abs(a - target) > maxOf(10_000.0, target * 0.15)) "Fuel rail is at ${f0(a / 1000)} MPa against a target of ${f0(target / 1000)} MPa." else null
            },
            // Generic P0299 logic: boost off target by 27.6 kPa (4 psi) for 5 s
            Rule("boost_deviation", WARN, 5_000) { v("Boost Pressure Deviation")?.takeIf { running && abs(it) >= 28 }?.let { "Boost is ${f0(abs(it))} kPa off target: check for leaks in the turbo hoses or a sticking turbo vane." } },
            // Estimate (Hilux 1KD-FTV owners, no 1ND-TV figure): injector corrections beyond ±3 mm³/stroke point at a worn or blocked injector
            Rule("injector", WARN, 30_000) {
                if (!(warm && idle)) return@Rule null
                val bad = (1..4).filter { abs(v("Injection Feedback Val #$it") ?: 0.0) > 3.0 }
                if (bad.isEmpty()) null else "Injector ${bad.joinToString(", ")} needs over ±3 mm³ of correction at idle: worn or blocked injector?"
            },
            // The ECU's own flags
            Rule("oil_overfill", WARN, 10_000) { if (on("Engine Oil Volume (Overfill)")) "The ECU reports the oil level too high. On a DPF diesel that is often fuel from regens thinning the oil: check the dipstick." else null },
            Rule("ecu_coolant_high", CRIT, 0) { if (on("Engine Coolant Temp High")) "The ECU flags the coolant temperature as too high." else null },
            Rule("ecu_boost_low", WARN, 0) { if (on("Boost Pressure Low")) "The ECU flags boost pressure as too low." else null },
            Rule("ecu_maf_low", WARN, 0) { if (on("MAF Low")) "The ECU flags the air flow (MAF) as too low." else null },
            Rule("rough_idle", WARN, 0) {
                val bad = (1..4).filter { on("Rough Idle #$it") }
                if (bad.isEmpty()) null else "The ECU detects rough running on cylinder ${bad.joinToString(", ")}."
            },
            Rule("dtc", WARN, 0) { v("# Codes(Include History)")?.takeIf { it > 0 }?.let { "${f0(it)} fault code${if (it > 1) "s are" else " is"} stored in the engine ECU." } },
            // Estimate: if soot gains 8 %+ in under 10 km outside a regen, it fills several times faster than a 150-400 km regen cycle allows
            Rule("soot_rate", WARN, 0) {
                val (o0, s0) = soot.firstOrNull() ?: return@Rule null
                val (o1, s1) = soot.last()
                val km = (o1 - o0) / 1000
                if (km >= 2 && s1 - s0 >= 8) "Soot rose ${f0(s1 - s0)} % in ${f1(km)} km, much faster than normal." else null
            },
        )
    }
}

/** What [Db.syncAlerts] writes: new alerts to open, keys to close, open ones whose text changed. */
class AlertDiff(val open: List<Finding>, val close: Set<String>, val update: List<Finding>)

/** [openTexts]: open alert key → its stored text. [active]: what the checks find now (first finding per key counts). */
fun diffAlerts(openTexts: Map<String, String>, active: List<Finding>): AlertDiff {
    val now = active.distinctBy { it.key }
    return AlertDiff(
        open = now.filter { it.key !in openTexts },
        close = openTexts.keys - now.map { it.key }.toSet(),
        update = now.filter { it.key in openTexts && openTexts[it.key] != it.text },
    )
}

/** Checks on the regen history (finished regens, oldest first): how often they come and whether they finish. */
fun trends(regens: List<Db.Regen>): List<Finding> {
    val out = mutableListOf<Finding>()
    val done = regens.filter { it.end != null }
    // Toyota quotes 150-400 km between regens (Toyota Australia, via Toyota Owners Club)
    done.lastOrNull()?.gapM?.takeIf { it < 150_000 }?.let {
        out += Finding("regen_frequent", WARN, "The last two regens were only ${"%.0f".format(it / 1000)} km apart (normal is 150-400 km).")
    }
    // Regens need 15-20 min of steady driving (carchecker 1.4 D-4D); three short ones in a row never cleaned the filter
    if (done.size >= 3 && done.takeLast(3).all { it.end!! - it.start < 10 * 60_000L }) {
        out += Finding("regen_interrupted", WARN, "The last 3 regens stopped within 10 minutes. Drive 20 min at a steady speed above 60 km/h to let one finish.")
    }
    return out
}
