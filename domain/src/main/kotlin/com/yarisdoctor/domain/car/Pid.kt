package com.yarisdoctor.domain.car

/**
 * One live-data item: bits [bitStart]..[bitEnd] (MSB-first from payload byte 0, so multi-byte fields are big-endian) of the
 * reply to `21 <lid>`, whose payload (after `61 <lid>`) is [len] bytes. Decoded like Techstream's ValueConvert:
 * (trunc(raw × mul / div) + offset) / 10^decimals in integer maths, raw sign-extended first when [signed].
 * [min]/[max] are Techstream's display range, not a validity check: some signed items have min 0 yet go negative.
 */
class Pid(
    val name: String, val unit: String, val lid: String, val len: Int, val bitStart: Int, val bitEnd: Int, val signed: Boolean,
    val mul: Long, val div: Long, val offset: Long, val decimals: Int, val min: Double?, val max: Double?,
    val enum: List<String>?, val category: String,
) {
    /** Stable key for storage and the hub: names repeat across requests (Target Booster Pressure is in DD and DF). */
    val id get() = "$lid.$bitStart"

    /** [payload] = hex after `61 <lid>`; null when it is too short. Enum items return the raw state number. */
    fun decode(payload: String): Double? {
        if (payload.length < len * 2) return null
        var raw = 0L
        for (b in bitStart / 8..bitEnd / 8) raw = raw shl 8 or payload.substring(b * 2, b * 2 + 2).toLong(16)
        val bits = bitEnd - bitStart + 1
        raw = raw shr (7 - bitEnd % 8) and (1L shl bits) - 1
        if (signed && raw shr (bits - 1) and 1L == 1L) raw -= 1L shl bits
        // Long division truncates toward zero, like Techstream
        return (raw * mul / div + offset) / Math.pow(10.0, decimals.toDouble())
    }

    /** What the user sees: the enum label, or the number with Techstream's decimals and the unit. */
    fun format(v: Double): String {
        enum?.let { return it.getOrNull(v.toInt()) ?: "? (${v.toInt()})" }
        return "%.${decimals}f".format(v) + if (unit.isEmpty()) "" else " $unit"
    }
}

/** Payload length per request code ("21DD" → 18), for checking replies. */
val PID_LENGTHS: Map<String, Int> = PIDS.associate { "21${it.lid}" to it.len }
