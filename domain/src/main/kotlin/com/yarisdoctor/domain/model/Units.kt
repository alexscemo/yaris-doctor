package com.yarisdoctor.domain.model

import java.util.Locale
import kotlin.math.abs

/** Which system every visible measurement follows. Stored data, the hub payload and exports stay metric. */
enum class UnitSystem { METRIC, IMPERIAL }

/**
 * The one unit system the UI shows. The ECU decodes, the database and the hub are metric, so values are
 * converted only when they are displayed. [text] rewrites the metric units inside rule descriptions, so
 * the Rules tab and stored warnings follow the same choice as the live values.
 */
class Units private constructor(val system: UnitSystem) {

    val metric: Boolean get() = system == UnitSystem.METRIC

    /** Metres per shown distance unit (1000 or a mile). */
    val distancePerUnit: Double = if (metric) 1000.0 else METRES_PER_MILE
    val distanceLabel: String = if (metric) "km" else "mi"
    private val distanceUnitsPerKm: Double = 1000.0 / distancePerUnit
    private val kmPerDistanceUnit: Double = distancePerUnit / 1000.0

    val speedLabel: String = if (metric) "km/h" else "mph"
    val temperatureLabel: String = if (metric) "°C" else "°F"
    val pressureLabel: String = if (metric) "kPa" else "psi"
    val flowLabel: String = if (metric) "g/s" else "lb/min"
    /** The DPF condition figure: pressure per 100 l/s of exhaust, or per 100 cfm. */
    val restrictionLabel: String = if (metric) "kPa per 100 l/s" else "psi per 100 cfm"

    fun distance(metres: Double): Double = metres / distancePerUnit
    fun distanceFromKm(km: Double): Double = km * distanceUnitsPerKm
    fun speed(kmh: Double): Double = kmh * distanceUnitsPerKm
    fun temperature(celsius: Double): Double = if (metric) celsius else celsius * 9 / 5 + 32
    /** A temperature difference (5 °C warmer = 9 °F warmer), not an absolute reading. */
    fun temperatureDelta(celsius: Double): Double = if (metric) celsius else celsius * 9 / 5
    fun pressure(kpa: Double): Double = if (metric) kpa else kpa * PSI_PER_KPA
    fun pressureFromBar(bar: Double): Double = pressure(bar * 100)
    fun pressureFromMpa(mpa: Double): Double = pressure(mpa * 1000)
    fun flow(gramsPerSecond: Double): Double = if (metric) gramsPerSecond else gramsPerSecond * POUNDS_PER_MINUTE_PER_GPS
    fun flowFromLitresPerSecond(litresPerSecond: Double): Double = if (metric) litresPerSecond else litresPerSecond * CFM_PER_LPS
    fun restriction(kpaPer100Lps: Double): Double = if (metric) kpaPer100Lps else kpaPer100Lps * PSI_PER_KPA / CFM_PER_LPS
    /** A DPF restriction rise per km shown per mile in imperial. */
    fun restrictionPerDistance(perKm: Double): Double = restriction(perKm) * kmPerDistanceUnit
    /** A soot rise per 100 km shown per 100 miles in imperial. */
    fun sootPer100Distance(per100Km: Double): Double = per100Km * kmPerDistanceUnit

    fun distanceText(metres: Double, decimals: Int = 0, grouped: Boolean = false): String =
        "${number(distance(metres), decimals, grouped)} $distanceLabel"

    fun distanceFromKmText(km: Double, decimals: Int = 0, grouped: Boolean = false): String =
        "${number(distanceFromKm(km), decimals, grouped)} $distanceLabel"

    fun speedText(kmh: Double, decimals: Int = 0): String = "${number(speed(kmh), decimals)} $speedLabel"

    fun temperatureText(celsius: Double, decimals: Int = 0): String = "${number(temperature(celsius), decimals)} $temperatureLabel"

    /** For the big tile and the notification, where the unit hugs the number ("600°C"). */
    fun temperatureNoSpace(celsius: Double, decimals: Int = 0): String = "${number(temperature(celsius), decimals)}$temperatureLabel"

    fun pressureText(kpa: Double, decimals: Int? = null): String {
        val v = pressure(kpa)
        val d = decimals ?: if (metric) kpaDecimals(v) else pressureDecimals(v)
        return "${number(v, d)} $pressureLabel"
    }

    fun flowText(gramsPerSecond: Double, decimals: Int? = null): String {
        val v = flow(gramsPerSecond)
        val d = decimals ?: if (metric) 0 else 1
        return "${number(v, d)} $flowLabel"
    }

    fun restrictionText(kpaPer100Lps: Double, decimals: Int = 1): String = "${number(restriction(kpaPer100Lps), decimals)} $restrictionLabel"

    // shortcut: imperial is US mpg and US gal/h, add a UK-gallon choice if a UK user asks
    /** Fuel use: "5.4 L/100 km", or "43.6 mpg" (higher is better there). */
    fun consumptionText(per100Km: Double): String =
        if (metric) "${number(per100Km, 1)} L/100 km" else "${number(MPG_US_PER_L100KM / per100Km, 1)} mpg"

    /** Fuel use while standing still: "0.6 L/h" or "0.15 gal/h". */
    fun fuelRateText(litresPerHour: Double): String =
        if (metric) "${number(litresPerHour, 1)} L/h" else "${number(litresPerHour / LITRES_PER_US_GALLON, 2)} gal/h"

    /** [value] in [unit]'s metric unit, converted for display, or null when the unit is already neutral. */
    fun displayValue(value: Double, unit: String): Double? = when (unit) {
        "kPa" -> pressure(value)
        "bar" -> pressureFromBar(value)
        "MPa" -> pressureFromMpa(value)
        "°C" -> temperature(value)
        "km/h" -> speed(value)
        "km", "Km" -> distanceFromKm(value)
        "g/s", "gm/s" -> flow(value)
        "l/s" -> flowFromLitresPerSecond(value)
        else -> null
    }

    fun displayUnit(unit: String): String? = when (unit) {
        "kPa", "bar", "MPa" -> pressureLabel
        "°C" -> temperatureLabel
        "km/h" -> speedLabel
        "km", "Km" -> distanceLabel
        "g/s", "gm/s" -> flowLabel
        "l/s" -> if (metric) "l/s" else "cfm"
        else -> null
    }

    /** How many decimals suit a converted value; source decimals are kept for metric. */
    fun displayDecimals(unit: String, displayed: Double, source: Int): Int = when {
        unit == "kPa" || unit == "bar" || unit == "MPa" -> if (metric) source else pressureDecimals(displayed)
        unit == "g/s" || unit == "gm/s" -> if (metric) source else 1
        (unit == "km" || unit == "Km") && !metric -> if (abs(displayed) < 10) 1 else 0
        else -> source
    }

    /** Metric pressure needs a little more room close to zero (0.3 kPa), imperial does too (0.04 psi). */
    fun pressureDecimals(psi: Double): Int = when {
        abs(psi) < 1 -> 2
        abs(psi) < 100 -> 1
        else -> 0
    }

    private fun kpaDecimals(kpa: Double): Int = when {
        abs(kpa) < 1 -> 2
        abs(kpa) < 10 -> 1
        else -> 0
    }

    /**
     * Rewrites the metric units inside a description ("24.5 kPa ... 30 km/h ... 5 °C") for imperial.
     * Metric text is returned unchanged. Temperatures after "rose", "by" or "±" are differences, so
     * they lose the 32 °F offset.
     */
    fun text(metricText: String): String {
        if (metric) return metricText
        return MEASURE.replace(metricText) { m ->
            val unit = m.groupValues[3].lowercase(Locale.ROOT)
            val delta = unit == "°c" && isDelta(metricText, m.range.first)
            val first = convertTextValue(m.groupValues[1].toDouble(), unit, delta)
            val second = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble()?.let { convertTextValue(it, unit, delta) }
            val decimals = textDecimals(unit, listOfNotNull(first, second))
            val label = when (unit) {
                "°c" -> temperatureLabel
                "km/h" -> speedLabel
                "km" -> distanceLabel
                "kpa", "bar", "mpa" -> pressureLabel
                "g/s" -> flowLabel
                "l/s" -> "cfm"
                else -> unit
            }
            val numbers = if (second == null) number(first, decimals) else "${number(first, decimals)}–${number(second, decimals)}"
            "$numbers $label"
        }
    }

    private fun convertTextValue(value: Double, unit: String, delta: Boolean): Double = when (unit) {
        "°c" -> if (delta) temperatureDelta(value) else temperature(value)
        "km/h" -> speed(value)
        "km" -> distanceFromKm(value)
        "kpa" -> pressure(value)
        "bar" -> pressureFromBar(value)
        "mpa" -> pressureFromMpa(value)
        "g/s" -> flow(value)
        "l/s" -> flowFromLitresPerSecond(value)
        else -> value
    }

    private fun textDecimals(unit: String, values: List<Double>): Int = when (unit) {
        "°c", "km/h" -> 0
        "km" -> if (values.any { abs(it) < 10 }) 1 else 0
        "kpa", "bar", "mpa" -> values.maxOfOrNull { pressureDecimals(it) } ?: 1
        "g/s", "l/s" -> 1
        else -> 0
    }

    /** "rose 5 °C" is a difference; "at 5 °C" is a reading. */
    private fun isDelta(text: String, at: Int): Boolean =
        DELTA_BEFORE.containsMatchIn(text.substring(maxOf(0, at - 32), at))

    private fun number(v: Double, decimals: Int, grouped: Boolean = false): String =
        String.format(Locale.ROOT, if (grouped) "%,.${decimals}f" else "%.${decimals}f", v)

    companion object {
        private const val PSI_PER_KPA = 0.14503773773020924
        private const val POUNDS_PER_MINUTE_PER_GPS = 0.13227735731092653
        private const val LITRES_PER_CUBIC_FOOT = 28.316846592
        private const val CFM_PER_LPS = 60.0 / LITRES_PER_CUBIC_FOOT
        private const val LITRES_PER_US_GALLON = 3.785411784
        /** mpg = this ÷ L/100 km. */
        private const val MPG_US_PER_L100KM = 100 * LITRES_PER_US_GALLON / (METRES_PER_MILE / 1000)

        /** Degree sign, units and the ranges between them; the lookarounds keep "kg/s" and "km/h" intact. */
        private val MEASURE = Regex(
            """(?<![\w/])(-?\d+(?:\.\d+)?)(?:\s*(?:-|–|to)\s*(-?\d+(?:\.\d+)?))?\s*(km/h|km|kPa|bar|MPa|g/s|l/s|°C)(?![\w/])""",
            RegexOption.IGNORE_CASE,
        )
        private val DELTA_BEFORE = Regex("""(?i)(?:\b(?:rose|rise|risen|fall|fell|fallen|drop|dropped|increase[ds]?|decrease[ds]?|by)\b|±)\s*$""")

        val METRIC = Units(UnitSystem.METRIC)
        val IMPERIAL = Units(UnitSystem.IMPERIAL)

        fun of(metric: Boolean): Units = if (metric) METRIC else IMPERIAL
        fun of(system: UnitSystem): Units = if (system == UnitSystem.METRIC) METRIC else IMPERIAL
    }
}
