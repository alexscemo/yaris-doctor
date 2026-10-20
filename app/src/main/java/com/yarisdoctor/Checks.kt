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
    /**
     * One check. [about] is what the Rules tab shows: the condition, the threshold and where it comes from ("estimate"
     * when borrowed from other Toyota diesels or generic OBD logic). [uses] names the [PIDS] it reads, for their poll rate.
     */
    class Rule(val key: String, val title: String, val level: Int, val holdMs: Long, val about: String, val uses: List<String>, internal val test: Checker.() -> String?)

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

        val RULES = listOf(
            Rule("coolant_hot", "Coolant hot", WARN, 10_000, "Coolant at 105-111 °C. The thermostat opens at 80-84 °C and the cap holds 0.95-1.25 bar, so coolant boils near 120 °C (1ND-TV FAQ, Toyota Owners Club).", listOf("Coolant Temp")) { v("Coolant Temp")?.takeIf { it in 105.0..<112.0 }?.let { "Coolant is at ${f0(it)} °C, close to boiling. Ease off and watch the gauge." } },
            Rule("coolant_overheat", "Coolant overheating", CRIT, 5_000, "Coolant at 112 °C or more: stop and let it cool. Same sources as above.", listOf("Coolant Temp")) { v("Coolant Temp")?.takeIf { it >= 112 }?.let { "Coolant is at ${f0(it)} °C. Stop safely and let the engine cool." } },
            Rule("coolant_rising", "Coolant rising fast", WARN, 0, "Coolant above 95 °C that rose 5 °C or more within the last 2 minutes.", listOf("Coolant Temp")) {
                val (t0, c0) = coolant.firstOrNull() ?: return@Rule null
                val c = v("Coolant Temp") ?: return@Rule null
                if (c >= 95 && c - c0 >= 5 && now - t0 >= 30_000) "Coolant rose ${f0(c - c0)} °C in ${(now - t0) / 1000} s, now ${f0(c)} °C." else null
            },
            Rule("coolant_cold", "Engine not warming up", WARN, 60_000, "Estimate: coolant still under 70 °C after 20 min of running while driving above 30 km/h, a sign of a thermostat stuck open.", listOf("Coolant Temp", "Engine Run Time", "Vehicle Speed")) {
                val c = v("Coolant Temp") ?: return@Rule null
                if ((v("Engine Run Time") ?: 0.0) >= 1200 && (v("Vehicle Speed") ?: 0.0) > 30 && c < 70) "Engine still at ${f0(c)} °C after 20 min: the thermostat may be stuck open." else null
            },
            Rule("oil_pressure_low", "Oil pressure low", CRIT, 3_000, "Oil pressure under 30 kPa with the engine running. Toyota diesel spec (1HD-T manual; no 1ND-TV figure): 29 kPa or more at idle.", listOf("Engine Oil Pressure", "Engine Speed")) { v("Engine Oil Pressure")?.takeIf { running && it < 30 }?.let { "Oil pressure is ${f0(it)} kPa. Stop the engine and check the oil." } },
            Rule("oil_pressure_rpm", "Oil pressure low at high revs", WARN, 5_000, "Oil pressure under 200 kPa at 2800 rpm or more. Same spec: 245 kPa or more at 3000 rpm.", listOf("Engine Oil Pressure", "Engine Speed")) {
                v("Engine Oil Pressure")?.takeIf { (v("Engine Speed") ?: 0.0) >= 2800 && it < 200 }?.let { "Oil pressure is only ${f0(it)} kPa at ${f0(v("Engine Speed")!!)} rpm (expected 245+)." }
            },
            Rule("battery_low", "Not charging", WARN, 30_000, "Battery under 13.0 V with the engine running: the alternator should give 13.2-14.8 V.", listOf("Battery Voltage", "Engine Speed")) { v("Battery Voltage")?.takeIf { running && it < 13.0 }?.let { "Battery at ${f1(it)} V with the engine running: the alternator may not be charging." } },
            Rule("battery_high", "Overcharging", WARN, 10_000, "Battery over 15.0 V with the engine running: overcharging.", listOf("Battery Voltage", "Engine Speed")) { v("Battery Voltage")?.takeIf { running && it > 15.0 }?.let { "Charging voltage is ${f1(it)} V, too high for the battery." } },
            Rule("soot_high", "DPF soot high", WARN, 30_000, "DPF soot at 100-139 %. Code P2463 is reported from 100-120 % (go-parts P2463).", listOf("PM Accumulation Ratio")) { v("PM Accumulation Ratio")?.takeIf { it in 100.0..<140.0 }?.let { "DPF soot is at ${f0(it)} %. Drive 20 min at a steady motorway speed so it can regenerate." } },
            Rule("soot_critical", "DPF soot critical", CRIT, 30_000, "DPF soot at 140 % or more. Forced regeneration is reported locked out from 160-170 % (Land Cruiser Prado reports).", listOf("PM Accumulation Ratio")) { v("PM Accumulation Ratio")?.takeIf { it >= 140 }?.let { "DPF soot is at ${f0(it)} %. Near the level where the car blocks regeneration: see a garage soon." } },
            Rule("dpf_pressure_idle", "DPF pressure high at idle", WARN, 30_000, "Estimate: DPF pressure of 4 kPa or more at warm idle, outside a regen. Healthy filters read about 0-1 kPa, blocked ones 4-5 kPa (DPF guides, Land Cruiser logs).", listOf("DPF Differential Pressure", "Coolant Temp", "Engine Speed", "Vehicle Speed")) {
                v("DPF Differential Pressure")?.takeIf { warm && idle && !inRegen && it >= 4 }?.let { "DPF pressure is ${f1(it)} kPa at idle (healthy is about 1): the filter may be clogging." }
            },
            Rule("exhaust_hot", "Exhaust too hot", WARN, 5_000, "Estimate: either exhaust temperature at 800 °C or more. Active regens run around 600-650 °C.", listOf("Exhaust Temperature B1S1", "Exhaust Temperature B1S2")) {
                listOfNotNull(v("Exhaust Temperature B1S1"), v("Exhaust Temperature B1S2")).maxOrNull()?.takeIf { it >= 800 }?.let { "Exhaust is at ${f0(it)} °C, hotter than a normal regen." }
            },
            Rule("rail_pressure", "Fuel rail pressure off target", WARN, 5_000, "Estimate: fuel rail pressure off its target by more than 10 MPa or 15 %, above 600 rpm (generic P0087/P0088 logic).", listOf("Fuel Press", "Target Common Rail Pressure", "Engine Speed")) {
                val a = v("Fuel Press") ?: return@Rule null
                val target = v("Target Common Rail Pressure") ?: return@Rule null
                if ((v("Engine Speed") ?: 0.0) > 600 && abs(a - target) > maxOf(10_000.0, target * 0.15)) "Fuel rail is at ${f0(a / 1000)} MPa against a target of ${f0(target / 1000)} MPa." else null
            },
            Rule("boost_deviation", "Boost off target", WARN, 5_000, "Boost off target by 28 kPa or more with the engine running (generic P0299 logic: 27.6 kPa for 5 s).", listOf("Boost Pressure Deviation", "Engine Speed")) { v("Boost Pressure Deviation")?.takeIf { running && abs(it) >= 28 }?.let { "Boost is ${f0(abs(it))} kPa off target: check for leaks in the turbo hoses or a sticking turbo vane." } },
            Rule("injector", "Injector correction high", WARN, 30_000, "Estimate: an injector correction beyond ±3 mm³/stroke at warm idle, pointing at a worn or blocked injector (Hilux 1KD-FTV owners; no 1ND-TV figure).", listOf("Injection Feedback Val #1", "Injection Feedback Val #2", "Injection Feedback Val #3", "Injection Feedback Val #4", "Coolant Temp", "Engine Speed", "Vehicle Speed")) {
                if (!(warm && idle)) return@Rule null
                val bad = (1..4).filter { abs(v("Injection Feedback Val #$it") ?: 0.0) > 3.0 }
                if (bad.isEmpty()) null else "Injector ${bad.joinToString(", ")} needs over ±3 mm³ of correction at idle: worn or blocked injector?"
            },
            Rule("oil_overfill", "Oil level too high", WARN, 10_000, "The ECU's oil overfill flag is on. On a DPF diesel that is often fuel from regens thinning the oil.", listOf("Engine Oil Volume (Overfill)")) { if (on("Engine Oil Volume (Overfill)")) "The ECU reports the oil level too high. On a DPF diesel that is often fuel from regens thinning the oil: check the dipstick." else null },
            Rule("ecu_coolant_high", "ECU: coolant too hot", CRIT, 0, "The ECU's own coolant-too-hot flag is on.", listOf("Engine Coolant Temp High")) { if (on("Engine Coolant Temp High")) "The ECU flags the coolant temperature as too high." else null },
            Rule("ecu_boost_low", "ECU: boost too low", WARN, 0, "The ECU's own low-boost flag is on.", listOf("Boost Pressure Low")) { if (on("Boost Pressure Low")) "The ECU flags boost pressure as too low." else null },
            Rule("ecu_maf_low", "ECU: air flow too low", WARN, 0, "The ECU's own low air flow (MAF) flag is on.", listOf("MAF Low")) { if (on("MAF Low")) "The ECU flags the air flow (MAF) as too low." else null },
            Rule("rough_idle", "ECU: rough running", WARN, 0, "The ECU flags rough running on one of the four cylinders.", listOf("Rough Idle #1", "Rough Idle #2", "Rough Idle #3", "Rough Idle #4")) {
                val bad = (1..4).filter { on("Rough Idle #$it") }
                if (bad.isEmpty()) null else "The ECU detects rough running on cylinder ${bad.joinToString(", ")}."
            },
            Rule("dtc", "Stored fault codes", WARN, 0, "One or more fault codes stored in the engine ECU, including history codes.", listOf("# Codes(Include History)")) { v("# Codes(Include History)")?.takeIf { it > 0 }?.let { "${f0(it)} fault code${if (it > 1) "s are" else " is"} stored in the engine ECU." } },
            Rule("soot_rate", "Soot rising fast", WARN, 0, "Estimate: soot up 8 % or more within 10 km of driving (at least 2 km) outside a regen, several times faster than a 150-400 km regen cycle allows. Distance comes from GPS.", listOf("PM Accumulation Ratio")) {
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

/** The [trends] checks (key, title, description), for the Rules tab: they run when a regen finishes, on the regen history, not on live values. */
val TREND_RULES = listOf(
    Triple("regen_frequent", "Regens too frequent", "The last two regens less than 150 km apart. Toyota quotes 150-400 km between regens (Toyota Australia, via Toyota Owners Club)."),
    Triple("regen_interrupted", "Regens keep stopping early", "The last 3 regens each lasted under 10 minutes. A regen needs 15-20 min of steady driving (carchecker, 1.4 D-4D)."),
)

/** Checks on the regen history (finished regens, oldest first): how often they come and whether they finish. */
fun trends(regens: List<Db.Regen>): List<Finding> {
    val out = mutableListOf<Finding>()
    val done = regens.filter { it.end != null }
    done.lastOrNull()?.gapM?.takeIf { it < 150_000 }?.let {
        out += Finding("regen_frequent", WARN, "The last two regens were only ${"%.0f".format(it / 1000)} km apart (normal is 150-400 km).")
    }
    if (done.size >= 3 && done.takeLast(3).all { it.end!! - it.start < 10 * 60_000L }) {
        out += Finding("regen_interrupted", WARN, "The last 3 regens stopped within 10 minutes. Drive 20 min at a steady speed above 60 km/h to let one finish.")
    }
    return out
}
