package com.yarisdoctor.domain.health

import com.yarisdoctor.domain.car.PIDS
import com.yarisdoctor.domain.model.Regen
import kotlin.math.abs

/** One problem the checks found. [level]: [WARN] or [CRIT]. [key] is stable, so the same issue is one alert row. */
data class Finding(val key: String, val level: Int, val text: String)

const val WARN = 1
const val CRIT = 2

/**
 * Health checks on the live values. Thresholds are for the 1ND-TV where a source exists; "estimate" marks the ones
 * borrowed from other Toyota diesels or generic OBD logic. A rule must hold for its hold time before it fires, so one
 * odd reading never raises an alert. One instance per drive: it keeps a little history for the rate rules.
 * [model2015]: the car is a 2015-or-later Yaris (Prefs.model2015), whose soot scale is Toyota's 2015+ one.
 */
class Checker(private val model2015: Boolean = false) {
    /**
     * One check. [about] is what the Rules tab shows: the condition, the threshold and where it comes from ("estimate"
     * when borrowed from other Toyota diesels or generic OBD logic). [uses] names the [PIDS] it reads, for their poll rate.
     */
    class Rule(val key: String, val title: String, val level: Int, val holdMs: Long, val about: String, val uses: List<String>, internal val test: Checker.() -> String?)

    private val values = HashMap<String, Pair<Long, Double>>() // pid name → (t, value)
    private val trueSince = HashMap<String, Long>()
    private val coolant = ArrayDeque<Pair<Long, Double>>()      // last 2 min
    private var now = 0L
    private var inRegen = false
    private var injectedAt = Long.MIN_VALUE / 2 // last read with After Injection Period above 0

    /** Latest value of [name] if read in the last few minutes (slow items are read every 5). */
    private fun v(name: String): Double? = values[name]?.takeIf { now - it.first <= STALE_MS }?.second

    private val running get() = (v("Engine Speed") ?: 0.0) > 400
    private val warm get() = (v("Coolant Temp") ?: 0.0) >= 70
    private val idle get() = running && (v("Engine Speed") ?: 9e9) < 1000 && v("Vehicle Speed") == 0.0
    private fun on(name: String) = v(name) == 1.0
    private val sootNeeded get() = sootLimits(model2015).first
    private val sootFault get() = sootLimits(model2015).second

    /** Feeds one cycle's replies (request code → reply) and returns what is wrong right now. */
    fun check(t: Long, replies: Map<String, String>, regen: Boolean): List<Finding> {
        now = t
        inRegen = regen
        for (p in PIDS) {
            val reply = replies["21${p.lid}"] ?: continue
            // Duplicate names (Target Booster Pressure in DD and DF): the first in the table wins
            if (values[p.name]?.first == t) continue
            p.decode(reply.substring(4))?.let { values[p.name] = t to it }
        }
        values["After Injection Period"]?.let { (rt, x) -> if (x > 0) injectedAt = rt }
        v("Coolant Temp")?.let { coolant.addLast(t to it) }
        while (coolant.isNotEmpty() && t - coolant.first().first > 120_000) coolant.removeFirst()

        return RULES.mapNotNull { r ->
            val text = r.test(this)
            if (text == null) { trueSince.remove(r.key); return@mapNotNull null }
            val since = trueSince.getOrPut(r.key) { t }
            if (t - since >= r.holdMs) Finding(r.key, r.level, text) else null
        }
    }

    companion object {
        private const val STALE_MS = 6 * 60_000L
        /** After injection drops to 0 for single reads mid-regen (overrun, idle): up to 55 s on the owner's car, read every ~18 s. */
        private const val INJECT_GAP_MS = 90_000L
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
            Rule("oil_pressure_low", "Oil pressure low", CRIT, 3_000, "Oil pressure under 24.5 kPa with the engine running. Toyota 1ND-TV manual: 24.5 kPa or more at warm idle (in the low-pressure mode, oil at 80 °C). The owner's car idles at 100-350 kPa.", listOf("Engine Oil Pressure", "Engine Speed")) { v("Engine Oil Pressure")?.takeIf { running && it < 24.5 }?.let { "Oil pressure is ${f0(it)} kPa. Stop the engine and check the oil." } },
            Rule("oil_pressure_rpm", "Oil pressure low at high revs", WARN, 5_000, "Oil pressure under 150 kPa at 3000 rpm or more. Toyota 1ND-TV manual: 150 kPa or more at 3200 rpm, warm. The owner's car reads 310-370 kPa there.", listOf("Engine Oil Pressure", "Engine Speed")) {
                v("Engine Oil Pressure")?.takeIf { (v("Engine Speed") ?: 0.0) >= 3000 && it < 150 }?.let { "Oil pressure is only ${f0(it)} kPa at ${f0(v("Engine Speed")!!)} rpm (Toyota: 150 kPa or more)." }
            },
            Rule("battery_low", "Not charging", WARN, 30_000, "Battery under 13.0 V with the engine running: the alternator should give 13.2-14.8 V.", listOf("Battery Voltage", "Engine Speed")) { v("Battery Voltage")?.takeIf { running && it < 13.0 }?.let { "Battery at ${f1(it)} V with the engine running: the alternator may not be charging." } },
            Rule("battery_high", "Overcharging", WARN, 10_000, "Battery over 15.0 V with the engine running: overcharging.", listOf("Battery Voltage", "Engine Speed")) { v("Battery Voltage")?.takeIf { running && it > 15.0 }?.let { "Charging voltage is ${f1(it)} V, too high for the battery." } },
            Rule("soot_high", "DPF soot high", WARN, 30_000, "DPF soot past the regen-needed level, below the fault level: 100-149 % on 2011-2014 cars, 118-173 % on 2015+ (Settings → Model year). Toyota 1ND-TV manual: normal is 0-149 % (2015+: 0-173), a regen may start above 77 % (2015+: 105) and is needed at 100 % (2015+: 118).", listOf("PM Accumulation Ratio")) { v("PM Accumulation Ratio")?.takeIf { it >= sootNeeded && it < sootFault }?.let { "DPF soot is at ${f0(it)} %, past the point where it needs a regen. Drive 20 min at a steady 65-100 km/h so it can regenerate." } },
            Rule("soot_critical", "DPF soot critical", CRIT, 30_000, "DPF soot at 150 % or more on 2011-2014 cars, 174 % or more on 2015+ (Settings → Model year). Toyota 1ND-TV manual: over 149 % at 4000 rpm is fault code P2463 (DPF restriction, soot) on 2011-2014; 2015+ cars read normal up to 173 %.", listOf("PM Accumulation Ratio")) { v("PM Accumulation Ratio")?.takeIf { it >= sootFault }?.let { "DPF soot is at ${f0(it)} %, the level of fault code P2463. See a garage soon: it may need a forced regen." } },
            Rule("dpf_pressure_idle", "DPF pressure high at idle", WARN, 30_000, "DPF pressure over 3 kPa at warm idle, outside a regen. Toyota 1ND-TV manual: -3 to 3 kPa at idle. The owner's car reads 0.2-2.1 kPa there.", listOf("DPF Differential Pressure", "Coolant Temp", "Engine Speed", "Vehicle Speed")) {
                v("DPF Differential Pressure")?.takeIf { warm && idle && !inRegen && it > 3 }?.let { "DPF pressure is ${f1(it)} kPa at idle (Toyota: 3 kPa at most): the filter may be clogging." }
            },
            Rule("exhaust_sensor", "Exhaust temperature sensor fault", WARN, 60_000, "An exhaust temperature reading exactly 50 °C with the engine warm, or 950 °C or more, for a minute. Toyota 1ND-TV manual: the tester shows 50 °C for an open circuit and 950 °C for a short.", listOf("Exhaust Temperature B1S1", "Exhaust Temperature B1S2", "Coolant Temp", "Engine Speed")) {
                val bad = listOfNotNull(
                    "before the catalyst".takeIf { v("Exhaust Temperature B1S1")?.let { it >= 950 || (warm && running && it == 50.0) } == true },
                    "after the DPF".takeIf { v("Exhaust Temperature B1S2")?.let { it >= 950 || (warm && running && it == 50.0) } == true },
                )
                if (bad.isEmpty()) null else "The exhaust temperature sensor ${bad.joinToString(" and ")} reads a fault value: check its wiring."
            },
            Rule("exhaust_hot", "Exhaust too hot", WARN, 5_000, "Estimate: either exhaust temperature at 800 °C or more. Active regens run around 600-650 °C.", listOf("Exhaust Temperature B1S1", "Exhaust Temperature B1S2")) {
                // 950+ is a shorted sensor, reported by exhaust_sensor
                listOfNotNull(v("Exhaust Temperature B1S1"), v("Exhaust Temperature B1S2")).filter { it < 950 }.maxOrNull()?.takeIf { it >= 800 }?.let { "Exhaust is at ${f0(it)} °C, hotter than a normal regen." }
            },
            Rule("rail_pressure", "Fuel rail pressure off target", WARN, 30_000, "Fuel rail pressure off its target by more than 10 MPa or 15 % for 30 s, above 600 rpm. Toyota 1ND-TV manual: within 10 MPa of the target in steady conditions; fault codes P1229/P1272 need 17-20 MPa under (or 25 MPa over) the target for 1 s with the pressure valve already at its limit, which this app can't see. The target moves 30-120 MPa within seconds when the pedal moves, and actual and target are read a moment apart, so short gaps are normal: on the owner's car they lasted up to 20 s.", listOf("Fuel Press", "Target Common Rail Pressure", "Engine Speed")) {
                val a = v("Fuel Press") ?: return@Rule null
                val target = v("Target Common Rail Pressure") ?: return@Rule null
                if ((v("Engine Speed") ?: 0.0) > 600 && abs(a - target) > maxOf(10_000.0, target * 0.15)) "Fuel rail is at ${f0(a / 1000)} MPa against a target of ${f0(target / 1000)} MPa." else null
            },
            Rule("maf_low", "Air flow sensor reading low", WARN, 30_000, "MAF under 80 % of the ECU's estimate for 30 s with the EGR valve under 15 % open, above 1000 rpm. Toyota 1ND-TV manual: the ratio is normally 0.4-1.2, the estimate comes from MAP and intake temperature, and it only means something at high load with the EGR off. With the EGR closed the owner's car reads 0.92-0.96 (never under 0.82), so 0.8 is set from that. A dirty or failing MAF reads low (Toyota lists it under P1608), which makes extra soot.",listOf("MAF/Estimate MAF Ratio", "Actual EGR Valve Pos.", "Engine Speed")) {
                val ratio = v("MAF/Estimate MAF Ratio") ?: return@Rule null
                val egr = v("Actual EGR Valve Pos.") ?: return@Rule null
                if ((v("Engine Speed") ?: 0.0) > 1000 && egr < 15 && ratio < 0.8) "Air flow sensor reads ${f0(ratio * 100)} % of what the ECU expects with the EGR closed: it may be dirty or failing." else null
            },
            Rule("boost_deviation", "Boost off target", WARN, 5_000, "Boost off target by 28 kPa or more while the ECU is actually asking for boost: accelerator down, load 20 % or more, target above 115 kPa and not during a regen (generic P0299 logic: 27.6 kPa for 5 s). Overrun shuts the intake throttle, so MAP falls to 40 kPa against a 101 kPa target and the raw deviation reads 60 kPa with nothing wrong: in the 20-25 Oct export all 8 alerts were overrun, tip-in lag or a regen, while steady high-load deviation stayed within ±5 kPa.", listOf("Boost Pressure Deviation", "Engine Speed", "Accel Position", "Calculate Load", "Target Booster Pressure")) {
                val dev = v("Boost Pressure Deviation") ?: return@Rule null
                val asked = (v("Accel Position") ?: 0.0) > 0 && (v("Calculate Load") ?: 0.0) >= 20 && (v("Target Booster Pressure") ?: 0.0) > 115
                if (running && !inRegen && asked && abs(dev) >= 28) "Boost is ${f0(abs(dev))} kPa off target: check for leaks in the turbo hoses or a sticking turbo vane." else null
            },
            Rule("injector", "Injector correction high", WARN, 30_000, "An injector correction beyond ±2.0 mm³/stroke at warm idle. Toyota 1ND-TV manual: -2.0 to 2.0 at idle; more means an injector delivering the wrong amount or low compression on that cylinder. The owner's car reads 0.6 at most.", listOf("Injection Feedback Val #1", "Injection Feedback Val #2", "Injection Feedback Val #3", "Injection Feedback Val #4", "Coolant Temp", "Engine Speed", "Vehicle Speed")) {
                if (!(warm && idle)) return@Rule null
                val bad = (1..4).filter { abs(v("Injection Feedback Val #$it") ?: 0.0) > 2.0 }
                if (bad.isEmpty()) null else "Injector ${bad.joinToString(", ")} needs over ±2 mm³ of correction at idle: worn or blocked injector, or low compression?"
            },
            Rule("idle_fuel_high", "Idle fuel use high", WARN, 60_000, "Injection volume of 8 mm³/stroke or more at warm idle for a minute, outside a regen. Toyota 1ND-TV manual: 2-8 at idle, and under 8 in its P2463 check; more points at clogged injectors, poor fuel, a blocked fuel filter, too much EGR or engine friction.", listOf("Injection Volume", "After Injection Period", "Coolant Temp", "Engine Speed", "Vehicle Speed")) {
                v("Injection Volume")?.takeIf { warm && idle && v("After Injection Period") == 0.0 && it >= 8 }?.let { "The engine needs ${f1(it)} mm³ of fuel per stroke at idle (Toyota: under 8): injectors, fuel filter or EGR?" }
            },
            Rule("pump_scv", "Fuel pump working hard", WARN, 30_000, "The supply pump's suction control valve current above 1530 mA for 30 s with the engine running. Toyota 1ND-TV manual: 1530 mA or less; stuck at 1800 or more means a sticking valve, and a high value means the pump runs hard yet can't reach the target pressure. The owner's car only spikes past 1530 for single readings.", listOf("Target Pump SCV Current", "Engine Speed")) {
                v("Target Pump SCV Current")?.takeIf { running && it > 1530 }?.let { "The fuel pump valve current is ${f0(it)} mA (Toyota: 1530 at most): fuel filter, pump or valve?" }
            },
            Rule("learned_values", "Learned position out of range", WARN, 60_000, "A learned value the ECU keeps outside Toyota's range (1ND-TV manual): turbo vanes closed 1.0-2.0 V, intake throttle closed 13-21 deg (21.25 = not learned yet), A/F sensor 2.2-3.2 V, EGR valve closed 1.7 V at most. The EGR lower limit (0.7 V) is left out: the owner's car reads 0.48 and Toyota's own test car 0.52. A value at a limit can mean dirt in the valve seat.", listOf("VN Close Learn Value", "Throttle Close Learning Val.", "AF Sensor Learning Value", "EGR Close Lrn. Val.", "Engine Speed")) {
                if (!running) return@Rule null
                val bad = listOfNotNull(
                    v("VN Close Learn Value")?.takeIf { it !in 1.0..2.0 }?.let { "turbo vanes ${"%.2f".format(it)} V" },
                    // 21.25 (decoded 21.2) is the reset value until the first ignition-off after a battery disconnect
                    v("Throttle Close Learning Val.")?.takeIf { it < 13 || it > 21.3 }?.let { "intake throttle ${f1(it)} deg" },
                    v("AF Sensor Learning Value")?.takeIf { it !in 2.2..3.2 }?.let { "A/F sensor ${"%.2f".format(it)} V" },
                    v("EGR Close Lrn. Val.")?.takeIf { it > 1.7 }?.let { "EGR valve ${"%.2f".format(it)} V" },
                )
                if (bad.isEmpty()) null else "Learned value out of Toyota's range: ${bad.joinToString(", ")}."
            },
            Rule("egr_tracking", "EGR valve off target", WARN, 15_000, "The EGR valve more than 10 % away from the position the ECU asks for, for 15 s. Toyota 1ND-TV manual: a gap over ±10 % means the EGR system may be faulty (sticking or sooted valve). Gaps are normal for a moment while revving: on the owner's car they last 1.6 s at most.", listOf("Target EGR Position", "Actual EGR Valve Pos.", "Engine Speed")) {
                val target = v("Target EGR Position") ?: return@Rule null
                val actual = v("Actual EGR Valve Pos.") ?: return@Rule null
                if (running && abs(target - actual) > 10) "The EGR valve is at ${f0(actual)} % but the ECU asks for ${f0(target)} %: it may be sticking or sooted up." else null
            },
            Rule("egr_bypass_warm", "EGR cooler bypassed when warm", WARN, 180_000, "Estimate: the EGR cooler still bypassed after 3 min of driving above 50 km/h with the engine warm (75 °C+). The ECU bypasses the cooler to warm up faster and switches by coolant temperature and load (Toyota 1KD manual); the capture already switched to the cooler under load at 45 °C.", listOf("EGR Cooler Bypass Position", "Coolant Temp", "Vehicle Speed")) {
                val c = v("Coolant Temp") ?: return@Rule null
                if (v("EGR Cooler Bypass Position") == 3.0 && c >= 75 && (v("Vehicle Speed") ?: 0.0) > 50) "The EGR cooler is still bypassed with the engine at ${f0(c)} °C: the bypass valve may be stuck open." else null
            },
            Rule("throttle_tracking", "Intake throttle off target", WARN, 10_000, "Estimate: the diesel intake throttle more than 15 % away from its target for 10 s, a sign of a sticking or sooted throttle. Same commanded-versus-actual check as the EGR valve.", listOf("Target Throttle Position", "Actual Throttle Position", "Engine Speed")) {
                val target = v("Target Throttle Position") ?: return@Rule null
                val actual = v("Actual Throttle Position") ?: return@Rule null
                if (running && abs(target - actual) > 15) "The intake throttle is at ${f0(actual)} % but the ECU asks for ${f0(target)} %: it may be sticking." else null
            },
            Rule("turbo_vanes", "Turbo vanes off target", WARN, 10_000, "Estimate: the variable turbo's vanes more than 15 % away from their command for 10 s. Sooted, sticking vanes are a common cause of low boost on diesels; the capture shows command and position within 1 %.", listOf("VN Turbo Command", "Actual VN Position", "Engine Speed")) {
                val cmd = v("VN Turbo Command") ?: return@Rule null
                val actual = v("Actual VN Position") ?: return@Rule null
                if (running && abs(cmd - actual) > 15) "The turbo vanes are at ${f0(actual)} % against a command of ${f0(cmd)} %: they may be sticking." else null
            },
            Rule("learn_status", "Closed-position learning not done", WARN, 300_000, "The ECU still reports the EGR valve or intake throttle closed position as not learned (NG) after 5 min warm. It is normal briefly after a battery disconnect; the ECU relearns at idle.", listOf("EGR Close Lrn. Status", "Diesel Throttle Learn Status", "Coolant Temp", "Engine Speed")) {
                val bad = listOfNotNull("EGR valve".takeIf { v("EGR Close Lrn. Status") == 0.0 }, "intake throttle".takeIf { v("Diesel Throttle Learn Status") == 0.0 })
                if (running && warm && bad.isNotEmpty()) "The ECU hasn't learned the ${bad.joinToString(" and ")} closed position. Let the engine idle for a few minutes." else null
            },
            Rule("idle_rpm", "Idle speed unusual", WARN, 60_000, "A warm idle (stopped, accelerator released) under 650 or over 1100 rpm for a minute. Toyota 1ND-TV manual: 720-820 rpm; the ECU raises it with lights, A/C or a regen running, so the band is wider (owners report up to about 1000).",listOf("Engine Speed", "Vehicle Speed", "Accel Position", "Coolant Temp")) {
                val rpm = v("Engine Speed") ?: return@Rule null
                if (warm && rpm > 400 && v("Vehicle Speed") == 0.0 && v("Accel Position") == 0.0 && (rpm < 650 || rpm > 1100)) "The engine idles at ${f0(rpm)} rpm (about 800 is normal)." else null
            },
            Rule("dpf_pressure_flow", "DPF pressure too low", WARN, 30_000, "Estimate: DPF pressure under 0.3 kPa with high exhaust flow (MAF 25 g/s+). A filter always resists some flow (the capture read 1.8 kPa at 21 g/s), so near zero points at a split pressure-sensor hose or a damaged filter (DPF efficiency codes like P2002).", listOf("DPF Differential Pressure", "MAF")) {
                val dp = v("DPF Differential Pressure") ?: return@Rule null
                if ((v("MAF") ?: 0.0) >= 25 && dp < 0.3 && !inRegen) "DPF pressure reads only ${f1(dp)} kPa at high flow: check the pressure-sensor hoses, or the filter may be damaged." else null
            },
            Rule("oil_overfill", "Oil level too high", WARN, 10_000, "The ECU's oil overfill flag is on. On a DPF diesel that is often fuel from regens thinning the oil.", listOf("Engine Oil Volume (Overfill)")) { if (on("Engine Oil Volume (Overfill)")) "The ECU reports the oil level too high. On a DPF diesel that is often fuel from regens thinning the oil: check the dipstick." else null },
            Rule("ecu_coolant_high", "ECU: coolant too hot", CRIT, 0, "The ECU's own coolant-too-hot flag is on.", listOf("Engine Coolant Temp High")) { if (on("Engine Coolant Temp High")) "The ECU flags the coolant temperature as too high." else null },
            Rule("ecu_boost_low", "ECU: boost too low", WARN, 0, "The ECU's own low-boost flag is on.", listOf("Boost Pressure Low")) { if (on("Boost Pressure Low")) "The ECU flags boost pressure as too low." else null },
            Rule("ecu_maf_low", "ECU: air flow too low", WARN, 0, "The ECU's own low air flow (MAF) flag is on.", listOf("MAF Low")) { if (on("MAF Low")) "The ECU flags the air flow (MAF) as too low." else null },
            Rule("rough_idle", "ECU: rough running", WARN, 0, "The ECU flags rough running on one of the four cylinders.", listOf("Rough Idle #1", "Rough Idle #2", "Rough Idle #3", "Rough Idle #4")) {
                val bad = (1..4).filter { on("Rough Idle #$it") }
                if (bad.isEmpty()) null else "The ECU detects rough running on cylinder ${bad.joinToString(", ")}."
            },
            Rule("regen_flag_check", "Regen flag disagrees with the ECU", WARN, 300_000, "The app's regen flag (21DF byte 16) and After Injection Period disagree for 5 min with the engine running: after injection with the flag off, or the flag on with none for 90 s. Toyota 1ND-TV manual: after injection is 0 except during PM, NOx or sulphur regeneration, so it is Toyota's own regen sign; NOx purges last seconds. Mid-regen it drops to 0 for up to 55 s on the owner's car, and it is read every ~18 s. A car whose ECU puts the regen bit elsewhere shows up here.", listOf("After Injection Period", "Engine Speed")) {
                if (v("After Injection Period") == null || !running) return@Rule null
                val injecting = now - injectedAt <= INJECT_GAP_MS
                when {
                    injecting && !inRegen -> "The ECU is after-injecting (a regen) but the app's regen flag is off: regens on this car may go unrecorded."
                    inRegen && !injecting -> "The app's regen flag is on but the ECU isn't after-injecting: the app may be recording regens that aren't happening."
                    else -> null
                }
            },
            Rule("dtc", "Stored fault codes", WARN, 0, "One or more fault codes stored in the engine ECU, including history codes.", listOf("# Codes(Include History)")) { v("# Codes(Include History)")?.takeIf { it > 0 }?.let { "${f0(it)} fault code${if (it > 1) "s are" else " is"} stored in the engine ECU." } },
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
    Triple("soot_floor", "Soot high after a regen", "Estimate: soot at $SOOT_FLOOR_HIGH % or more 5 min after the newest regen ended. A burn ends near 10 %, and the figure 5 min on is what the ECU really reads after it. Toyota 1ND-TV manual (2011-2014): the DPF test passes a filter that reads 40 % or less after two forced regens. The owner's car read 38-40 % after each regen while its ash record was wrongly cleared, with regens every ~40 km. A high floor points at a wrong ash record, ash build-up or the pressure sensor."),
    Triple("regen_hot", "Regen ran hot", "The newest regen's peak DPF temperature (Exhaust Temperature B1S2) above Toyota's regen range: 500-700 °C on 2011-2014 cars, 500-750 °C on 2015+ (Settings → Model year), from the 1ND-TV manual. The owner's car peaks at 627-638 °C. A hotter burn (too much soot at once, or too much after injection) can crack the filter."),
    Triple("oil_change_due", "Oil change due", "$OIL_CHANGE_KM km or more since the oil change recorded in Settings: Toyota's interval, checked in its P252F (oil dilution) procedure. Each regen's after injection puts some fuel in the oil, which thins it. The distance is this phone's GPS. Off until an oil change is recorded."),
    Triple("dpf_sensor_zero", "DPF pressure sensor off zero", "The DPF pressure sensor reads over 1 kPa, or under -0.5 kPa, with the engine stopped and the ignition on, when no exhaust flows. Toyota 1ND-TV manual: the displayed value leaves out the ECU's zero-point correction, so it may read about ±0.3 kPa at zero; the owner's car reads 0.3-0.4. A drifting sensor or a split or blocked hose makes the ECU's soot figure wrong."),
)

/** [engineOffDp]: the newest DPF pressure readings (kPa) taken with the engine stopped and the ignition on, newest first. */
fun sensorZero(engineOffDp: List<Double>): Finding? {
    if (engineOffDp.size < 3) return null
    val dp = engineOffDp.take(5).sorted()[minOf(engineOffDp.size, 5) / 2]
    return if (dp > 1.0 || dp < -0.5) Finding("dpf_sensor_zero", WARN, "The DPF pressure sensor reads ${"%.1f".format(dp)} kPa with the engine off (it should be about 0): check the sensor and its two hoses.") else null
}

/**
 * Exhaust restriction of the DPF: its pressure drop per 100 l/s of exhaust, so motorway and town readings compare.
 * Flow is MAF turned into volume at the after-DPF temperature and ~1 atm. Null below 1200 rpm or 25 l/s, where the
 * pressure is too small to read well.
 */
fun restriction(dpKpa: Double, mafGs: Double, exhaustC: Double, rpm: Double): Double? {
    val litres = mafGs * 287 * (exhaustC + 273.15) / 101_325
    return if (rpm < 1200 || litres < 25) null else dpKpa / litres * 100
}

/**
 * One finished regen's view of the filter: [base] = restriction in the 5 min after it (what soot burning can't
 * remove, i.e. ash), [perKm] = how fast restriction rose per km until the next regen (or until now).
 */
class DpfCondition(val regenStart: Long, val base: Double?, val perKm: Double?)

/** [after]: restriction readings just after the regen; [later]: the last ones before the next regen; [km] driven between. */
fun dpfCondition(regenStart: Long, after: List<Double>, later: List<Double>, km: Double?): DpfCondition {
    fun median(xs: List<Double>) = if (xs.size < 5) null else xs.sorted()[xs.size / 2]
    val base = median(after)
    val end = median(later)
    return DpfCondition(regenStart, base, if (base != null && end != null && km != null && km >= 5) (end - base) / km else null)
}

/** A finished regen as dpfConditions reads it; a missing odometer reading is NaN, which fails the km check instead of faking a distance. */
class FinishedRegen(val start: Long, val end: Long, val odoStart: Double, val odoEnd: Double)

/**
 * [DpfCondition] for each of [regens] (oldest first): restriction in the 5 min after each (a fast-loading filter has
 * already risen a lot by 15), and its rise per km over the last 10 min of driving before the next regen, or before
 * [newestFrame] for the latest one. [restrictions] reads the readings between two instants; [odometer] is the GPS odometer now.
 */
fun dpfConditions(regens: List<FinishedRegen>, newestFrame: Long, odometer: Double, restrictions: (Long, Long) -> List<Double>): List<DpfCondition> =
    regens.mapIndexed { i, r ->
        val next = regens.getOrNull(i + 1)
        val until = next?.start ?: newestFrame
        val km = ((next?.odoStart ?: odometer) - r.odoEnd) / 1000
        dpfCondition(r.start, restrictions(r.end, r.end + 5 * 60_000L), restrictions(until - 10 * 60_000L, until), km)
    }

/** One line for the end-of-drive notification. [perUnit]: metres per mile or km. */
fun tripSummary(metres: Double, sootStart: Int?, sootEnd: Int?, regens: Int, warnings: Int, perUnit: Double, unit: String): String =
    listOf(
        "%.0f $unit".format(metres / perUnit),
        if (sootStart != null && sootEnd != null) "soot $sootStart → $sootEnd %" else null,
        when (regens) { 0 -> "no regen"; 1 -> "1 regen"; else -> "$regens regens" },
        when (warnings) { 0 -> "no warnings"; 1 -> "1 warning"; else -> "$warnings warnings" },
    ).filterNotNull().joinToString(" · ")

/** Soot regen-needed and fault (P2463) levels, %: Toyota's 2011-2014 or 2015+ scale. */
fun sootLimits(model2015: Boolean) = if (model2015) 118.0 to 174.0 else 100.0 to 150.0

/** Top of Toyota's normal DPF temperature (B1S2) during a regen, °C. */
fun regenTempMax(model2015: Boolean) = if (model2015) 750 else 700

const val SOOT_FLOOR_HIGH = 30
const val OIL_CHANGE_KM = 15_000

/** [metres]: driven since the recorded oil change, null when none was recorded. */
fun oilDue(metres: Double?): Finding? = metres?.takeIf { it >= OIL_CHANGE_KM * 1000.0 }?.let {
    Finding("oil_change_due", WARN, "It is ${"%.0f".format(it / 1000)} km since the oil change (Toyota: every $OIL_CHANGE_KM km). Regens thin the oil with fuel: change it.")
}

/**
 * Checks on the regen history (oldest first): how often they come, whether they finish, and the newest finished one's
 * soot floor and peak temperature (the newest that has one: [Db.withSigns] leaves them null while the car was off).
 */
fun trends(regens: List<Regen>, model2015: Boolean = false): List<Finding> {
    val out = mutableListOf<Finding>()
    val done = regens.filter { it.end != null }
    done.lastOrNull { it.floor != null }?.floor?.takeIf { it >= SOOT_FLOOR_HIGH }?.let {
        out += Finding("soot_floor", WARN, "Soot was still $it % 5 min after the last regen (it should drop well below $SOOT_FLOOR_HIGH %): the ECU's ash record, ash build-up or the DPF pressure sensor?")
    }
    val hot = regenTempMax(model2015)
    done.lastOrNull { it.peakTemp != null }?.peakTemp?.takeIf { it > hot }?.let {
        out += Finding("regen_hot", WARN, "The last regen peaked at $it °C after the DPF (Toyota: $hot °C at most).")
    }
    done.lastOrNull()?.gapM?.takeIf { it < 150_000 }?.let {
        out += Finding("regen_frequent", WARN, "The last two regens were only ${"%.0f".format(it / 1000)} km apart (normal is 150-400 km).")
    }
    if (done.size >= 3 && done.takeLast(3).all { it.end!! - it.start < 10 * 60_000L }) {
        out += Finding("regen_interrupted", WARN, "The last 3 regens stopped within 10 minutes. Drive 20 min at a steady speed above 60 km/h to let one finish.")
    }
    return out
}
