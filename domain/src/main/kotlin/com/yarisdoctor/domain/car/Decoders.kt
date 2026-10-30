package com.yarisdoctor.domain.car

/** The three DPF requests the service reads every cycle, as DPFViewer found them on the owner's Yaris. */
const val PID_SOOT = "21DD"
const val PID_TEMP = "21F1"
const val PID_REGEN = "21AD"

/** Byte [i] of a reply, counting the echoed `61 <lid>` as bytes 0-1 (2 hex chars per byte); null past the end. */
private fun byteAt(p: String, i: Int) = if (p.length >= i * 2 + 2) p.substring(i * 2, i * 2 + 2).toIntOrNull(16) else null

// Soot and temperature are DPFViewer's char offsets 38 and 16, confirmed on the car (temp is Exhaust
// Temperature B1S2). Results are truncated like DPFViewer's int casts.
/** Soot load, %: byte 19 × 2. */
fun sootOf(p: String): Int? = byteAt(p, 19)?.let { it * 2 }
/** DPF temperature, °C: bytes 8-9 × 0.625. */
fun tempOf(p: String): Int? {
    val a = byteAt(p, 8) ?: return null
    val b = byteAt(p, 9) ?: return null
    return ((a * 256 + b) * 0.625).toInt()
}
/**
 * A regen only counts above [RUNNING_RPM]; idle is ~770. [rpmReply] is the 210C reply (rpm = raw / 4); unknown rpm is not
 * a stop.
 */
fun engineStopped(rpmReply: String?): Boolean {
    val raw = rpmReply?.takeIf { it.length >= 8 }?.substring(4, 8)?.toIntOrNull(16) ?: return false
    return raw / 4 < RUNNING_RPM
}

const val RUNNING_RPM = 500

/**
 * After Injection Period (bytes 8-9 of the 21AD reply) above 0: Toyota's own regen sign. On the car's log (21-29 Oct, 38
 * trips, 11 burns) it was above 0 only during burns. It drops to 0 for up to ~70 s mid-burn, so see [AFTER_INJECTION_HOLD_MS].
 */
fun regenOf(p: String): Boolean? {
    val a = byteAt(p, 8) ?: return null
    val b = byteAt(p, 9) ?: return null
    return a * 256 + b > 0
}

/** A burn is still on this long after the last after injection: it pauses on overrun and at idle. */
const val AFTER_INJECTION_HOLD_MS = 90_000L
