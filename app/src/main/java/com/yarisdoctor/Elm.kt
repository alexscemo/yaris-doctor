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

// Char offsets and scale factors are the ones found on the car with DPFViewer (BT.h).
fun sootOf(p: String): Int? = if (p.length >= 40) p.substring(38, 40).toIntOrNull(16)?.times(2) else null
fun regenOf(p: String): Boolean? = if (p.length >= 34) p.substring(33, 34).toIntOrNull(16)?.let { it == 1 } else null
fun tempOf(p: String): Int? = if (p.length >= 20) p.substring(16, 20).toIntOrNull(16)?.let { (it * 0.625).toInt() } else null
