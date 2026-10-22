package com.yarisdoctor.domain.car

/**
 * Toyota's normal values from the 1ND-TV repair manual (Yaris RM, ECD SYSTEM w/ DPF > Data List, 2011-2014 pages, which fit
 * the owner's Nov 2013 car), by [PIDS] name, for the All data tab. Notes in ../techstream/RM_NOTES.md. The manual calls
 * them reference values: a reading outside one is a hint, not a verdict.
 */
val TOYOTA_NORMAL: Map<String, String> = mapOf(
    "Calculate Load" to "idle 10-35 %",
    "Coolant Temp" to "warm 75-100 °C",
    "MAP" to "idle 90-105 kPa, 3000 rpm no load 110-150",
    "Engine Speed" to "warm idle 720-820 rpm",
    "MAF" to "idle 3-7 g/s with the EGR open, 7-11 closed",
    "Intake Air Temp (Turbo)" to "70 °C or less",
    "Fuel Press" to "within 10 MPa of the target when steady; idle 25-35 MPa",
    "Target Common Rail Pressure" to "25-185 MPa running",
    "Battery Voltage" to "ignition on 11-14 V",
    "Atmosphere Pressure" to "within 25 kPa of MAP with the engine off",
    "Target Booster Pressure" to "idle 90-110 kPa, 3000 rpm 120-160",
    "Target Pump SCV Current" to "1530 mA or less",
    "Injection Volume" to "warm idle 2-8 mm³/st",
    "Injection Feedback Val #1" to "warm idle ±2.0 mm³/st",
    "Injection Feedback Val #2" to "warm idle ±2.0 mm³/st",
    "Injection Feedback Val #3" to "warm idle ±2.0 mm³/st",
    "Injection Feedback Val #4" to "warm idle ±2.0 mm³/st",
    "Pilot 1 Injection Period" to "idle 200-400 µs",
    "Pilot 2 Injection Period" to "idle 150-250 µs",
    "Main Injection Period" to "idle 200-600 µs",
    "After Injection Period" to "0 except during a regen",
    "EGR Close Lrn. Val." to "0.7-1.7 V (Toyota's own test car read 0.52)",
    "Throttle Close Learning Val." to "13-21 deg; 21.25 means not learned yet",
    "VN Close Learn Value" to "1.0-2.0 V",
    "AF Sensor Learning Value" to "idle 2.2-3.2 V",
    "Throttle Sensor Volt %" to "open 70-90 %, closed 10-20 %",
    "Exhaust Temperature B1S1" to "warm idle 80-300 °C, regen 200-600; 50 = wire open, 950 = short",
    "Exhaust Temperature B1S2" to "warm idle 80-300 °C, regen 500-700; 50 = wire open, 950 = short",
    "DPF Differential Pressure" to "idle -3 to 3 kPa",
    "PM Accumulation Ratio" to "0-149 %; a regen may start above 77 %, is needed at 100 %",
    "MAF/Estimate MAF Ratio" to "0.4-1.2",
    "Engine Oil Pressure" to "warm: idle 24.5 kPa or more, 3200 rpm 150 or more",
    "Glow Request Lighting Time" to "500-10000 ms",
    "Alternate Duty Ratio" to "0-100 %; always at max means a battery or charging fault",
    "EGR Cooler Bypass Position" to "Cooler or Bypass",
    "MAF Low" to "OFF", "Boost Pressure Low" to "OFF", "Engine Coolant Temp High" to "OFF",
    "Rough Idle #1" to "OFF", "Rough Idle #2" to "OFF", "Rough Idle #3" to "OFF", "Rough Idle #4" to "OFF",
    "Immobiliser Fuel Cut History" to "OFF",
)

/** The lines that differ on 2015+ cars (Settings → Model year), from the 2015+ Data List pages. */
val TOYOTA_NORMAL_2015: Map<String, String> = mapOf(
    "Exhaust Temperature B1S2" to "warm idle 80-300 °C, regen 500-750; 50 = wire open, 950 = short",
    "PM Accumulation Ratio" to "0-173 %; a regen may start above 105 %, is needed at 118 %",
)

fun toyotaNormal(name: String, model2015: Boolean) = (if (model2015) TOYOTA_NORMAL_2015[name] else null) ?: TOYOTA_NORMAL[name]
