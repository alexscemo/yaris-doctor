package com.yarisdoctor.domain.drive

/**
 * The instrument cluster (meter ECU, requests on 7C0, replies on 7C8), read between engine cycles: the fuel level, and
 * the one-shot odometer probe. Both move the dongle off the engine's header, so each puts it back before returning.
 */
class Meter(private val link: ObdLink) {

    /**
     * The meter's `21 29` reply ("6129" + one byte), or null. Techstream's database: "Fuel Input", byte × 0.5 L; the
     * meter lists LID 29 as supported. Confirmed on the car 29 Oct 2026: 6.5 L before a fill-up, 39 L after (34.7 L put in);
     * a full tank reads flat at ~39 L and the level swings ~3.5 L while driving. Stored raw (see [litres]).
     */
    fun fuel(): String? = try {
        if (link.send("AT SH 7C0") != "OK" || link.send("AT CRA 7C8") != "OK") null
        else link.send(FUEL_REQUEST, 1000)?.takeIf(::fuelOk)
    } finally {
        engine()
    }

    /**
     * Everything that might hold the odometer, as what was asked → the raw answer ("" = none). Techstream has no odometer
     * read for this car (RM_NOTES.md, 28 Oct): these are the untested guesses, plus a listen to the meter's CAN broadcast
     * (0x611 on other Toyotas; the airbag ECU logs an odometer, so the meter does send it). About 30 s; the link is reset after.
     */
    fun probe(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            // Engine: Distance from Injector Maintenance (ED), the LID Techstream's own mileage reader uses (28), OBD odometer (A6)
            for (c in listOf("2128", "21ED", "01A0", "01A6")) out["$PROBE:7E0:$c"] = link.send(c, 1000).orEmpty()
            link.send("AT SH 7C0"); link.send("AT CRA 7C8")
            // Meter LIDs it lists as supported but Techstream never names
            for (c in listOf("2102", "21E2")) out["$PROBE:7C0:$c"] = link.send(c, 1000).orEmpty()
            link.send("AT H1") // monitor lines carry their CAN id only with headers on
            link.send("AT CRA 611")
            out["$PROBE:611"] = distinct(link.monitor(LISTEN_611_MS))
            link.send("AT AR") // every id, briefly: a clone's buffer overflows fast on an unfiltered bus
            out["$PROBE:ALL"] = byId(link.monitor(LISTEN_ALL_MS))
        } finally {
            link.init()
        }
        return out
    }

    /** Back to the engine ECU; a full init when the dongle didn't take it. */
    private fun engine() {
        if (link.send("AT SH 7E0") != "OK" || link.send("AT CRA 7E8") != "OK") link.init()
    }

    companion object {
        /** The frame lid the fuel reply is stored under: longer than 2 characters, so it never clashes with an engine lid or syncs. */
        const val FUEL = "M29"
        const val FUEL_REQUEST = "2129"
        const val FUEL_MS = 60_000L
        /** Prefix of the probe's frame lids. */
        const val PROBE = "ODO"
        const val LISTEN_611_MS = 20_000L
        const val LISTEN_ALL_MS = 5_000L

        fun fuelOk(r: String?) = r != null && r.length == 6 && r.startsWith("6129") && r.all { it in '0'..'9' || it in 'A'..'F' }

        /** Litres from the stored payload byte (hex after `61 29`). Confirmed on the car, 29 Oct 2026. */
        fun litres(hex: String): Double? = hex.take(2).toIntOrNull(16)?.let { it * 0.5 }

        /** The 0x611 lines in order with repeats dropped: the odometer changes rarely, so this stays short. */
        fun distinct(lines: List<String>) = lines.distinct().take(200).joinToString(" ")

        /** Per CAN id (the first 3 characters with headers on): how many lines and the last one. */
        fun byId(lines: List<String>): String = lines.groupBy { it.take(3) }.toSortedMap()
            .map { (id, l) -> "$id×${l.size}:${l.last()}" }.joinToString(" ")
    }
}
