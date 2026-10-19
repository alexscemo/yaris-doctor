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

    /** Same sequence as DPFViewer: myELM.begin(..., '6') then Toyota engine ECU header/filter. */
    fun init() {
        for (cmd in listOf("AT D", "AT Z", "AT E0", "AT S0", "AT AL", "AT TP A6", "AT SH 7E0", "AT CRA 7E8")) {
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

/**
 * Normalises a user-typed OBD request ("21 dd" → "21DD"): 2–4 hex bytes, so a typo or an "AT" command can't reach the
 * dongle. Blank → [default]; anything else invalid → null.
 */
fun pidCode(s: String, default: String): String? {
    val code = s.filterNot { it.isWhitespace() }.uppercase()
    if (code.isEmpty()) return default
    return code.takeIf { it.length % 2 == 0 && it.length in 4..8 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
}

/** Blank → [default]; otherwise a byte position 0–63 of the reply, or null if it isn't one. */
fun bytePos(s: String, default: Int): Int? = if (s.isBlank()) default else s.trim().toIntOrNull()?.takeIf { it in 0..63 }

// [byte] counts reply bytes from 0, including the echoed mode/PID (byte 0 = 0x61); 2 hex chars per byte.
// Defaults and scale factors are the ones found on the car with DPFViewer (BT.h), which used char offsets 38, 16 and 33.
private fun hexAt(p: String, byte: Int, chars: Int, skip: Int = 0): Int? =
    if (p.length >= byte * 2 + skip + chars) p.substring(byte * 2 + skip, byte * 2 + skip + chars).toIntOrNull(16) else null

fun sootOf(p: String, byte: Int = 19): Int? = hexAt(p, byte, 2)?.times(2)
fun tempOf(p: String, byte: Int = 8): Int? = hexAt(p, byte, 4)?.let { (it * 0.625).toInt() } // 2 bytes, big-endian
fun regenOf(p: String, byte: Int = 16): Boolean? = hexAt(p, byte, 1, skip = 1)?.let { it == 1 } // low nibble of the byte
