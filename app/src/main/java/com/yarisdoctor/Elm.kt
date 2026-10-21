package com.yarisdoctor

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** ELM327 over a Bluetooth SPP stream. Replies are cleaned exactly like ELMduino so DPFViewer's PID offsets still apply. */
class Elm(private val input: InputStream, private val output: OutputStream) {

    /** Returns the cleaned payload, or null on timeout / ELM error. Throws IOException when the link drops. */
    fun send(cmd: String, timeoutMs: Long = 2000): String? {
        while (input.available() > 0) input.read()
        output.write("$cmd\r".toByteArray())
        output.flush()
        val raw = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("dongle closed the link")
                if (c == '>'.code) return parse(raw.toString())
                raw.append(c.toChar())
            } else if (System.currentTimeMillis() > deadline) {
                return null
            } else {
                Thread.sleep(10)
            }
        }
    }

    /** Same sequence as DPFViewer: myELM.begin(..., '6') then Toyota engine ECU header/filter, plus adaptive timing for the full table. */
    fun init() {
        for (cmd in listOf("AT D", "AT Z", "AT E0", "AT S0", "AT AL", "AT TP A6", "AT SH 7E0", "AT CRA 7E8", "AT AT2")) {
            send(cmd, 3000)
            Thread.sleep(100)
        }
    }
}

private val ERRORS = listOf("UNABLETOCONNECT", "NODATA", "STOPPED", "ERROR")

/** Port of ELMduino get_response() + parseMultiLineResponse(). */
fun parse(raw: String): String? {
    val s = raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ':' || it == '.' || it == '\r' }
    if (ERRORS.any { it in s }) return null
    if (':' !in s) return s.replace("\r", "")
    // Multi-frame: first line is the byte count, data lines are "N:hex"
    var total = 0
    val out = StringBuilder()
    for (line in s.split('\r').filter { it.isNotEmpty() }) {
        if (total == 0) {
            if (line.length <= 3) total = line.toIntOrNull(16)?.times(2) ?: 0
        } else if (':' in line) {
            out.append(line.substringAfter(':').take(total - out.length))
        }
    }
    return out.toString()
}

/** The three DPF requests the service reads every cycle, as DPFViewer found them on the owner's Yaris. */
const val PID_SOOT = "21DD"
const val PID_TEMP = "21F1"
const val PID_REGEN = "21DF"

/** Byte [i] of a reply, counting the echoed `61 <lid>` as bytes 0-1 (2 hex chars per byte); null past the end. */
private fun byteAt(p: String, i: Int) = if (p.length >= i * 2 + 2) p.substring(i * 2, i * 2 + 2).toIntOrNull(16) else null

// Soot and temperature are DPFViewer's char offsets 38 and 16, confirmed by the Techstream capture (temp is Exhaust
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
 * The 21DF regen bit is also set as the engine stops with the ignition still on (seen on the car: first at 346 rpm while
 * spinning down, then at 0), so a regen only counts above [RUNNING_RPM]; idle is ~770. [rpmReply] is the 210C reply
 * (rpm = raw / 4); unknown rpm trusts the flag.
 */
fun engineStopped(rpmReply: String?): Boolean {
    val raw = rpmReply?.takeIf { it.length >= 8 }?.substring(4, 8)?.toIntOrNull(16) ?: return false
    return raw / 4 < RUNNING_RPM
}

const val RUNNING_RPM = 500

/** Regen flag: byte 16's low nibble is 1 during a regen (DPFViewer's char 33, confirmed on the car). */
fun regenOf(p: String): Boolean? = byteAt(p, 16)?.let { it and 15 == 1 }
