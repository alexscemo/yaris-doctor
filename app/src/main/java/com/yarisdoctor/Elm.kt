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
private val READ_SERVICES = setOf("21", "22", "01")

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
 * Normalises a user-typed OBD request ("21 dd" → "21DD"): 2–4 hex bytes starting with a read service (21 Toyota/KWP,
 * 22 UDS, 01 OBD), so a typo, an "AT" command or an ECU-changing service (11 reset, 30/31 actuators, 3B write) can't
 * reach the car; it is sent every cycle while driving. Blank → [default]; anything else invalid → null.
 */
fun pidCode(s: String, default: String): String? {
    val code = s.filterNot { it.isWhitespace() }.uppercase()
    if (code.isEmpty()) return default
    return code.takeIf {
        it.length % 2 == 0 && it.length in 4..8 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } && it.take(2) in READ_SERVICES
    }
}

/** Blank → [default]; otherwise a byte position 0–63 of the reply, or null if it isn't one. */
fun bytePos(s: String, default: Int): Int? = if (s.isBlank()) default else s.trim().toIntOrNull()?.takeIf { it in 0..63 }

/**
 * Torque-style formula over reply bytes: `A` is the byte at the reading's position, `B` the next one, then `C`, `D`.
 * Supports numbers, + - * / & (bitwise AND, lowest precedence) and brackets. Evaluates to null when a byte is past the
 * end of the reply or the result isn't finite (e.g. division by zero).
 */
class Formula private constructor(private val expr: Expr) {
    /** [byte] counts reply bytes from 0, including the echoed mode/PID (byte 0 = 0x61); 2 hex chars per byte. */
    fun eval(payload: String, byte: Int): Double? = expr { k ->
        val at = (byte + k) * 2
        if (payload.length >= at + 2) payload.substring(at, at + 2).toIntOrNull(16) else null
    }?.takeIf { it.isFinite() }

    companion object {
        /** null when [text] isn't a valid formula. */
        fun parse(text: String): Formula? =
            try { Formula(Parser(text.filterNot { it.isWhitespace() }.uppercase()).all()) } catch (e: IllegalArgumentException) { null }
    }
}

/** Blank → [default]; otherwise the formula as typed, or null if it doesn't parse. */
fun formulaText(s: String, default: String): String? = if (s.isBlank()) default else s.trim().takeIf { Formula.parse(it) != null }

private typealias Expr = ((Int) -> Int?) -> Double?

private class Parser(private val s: String) {
    private var i = 0

    fun all(): Expr = and().also { require(i == s.length) }

    private fun and(): Expr {
        var l = sum()
        while (eat('&')) l = op(l, sum()) { x, y -> (x.toLong() and y.toLong()).toDouble() }
        return l
    }

    private fun sum(): Expr {
        var l = product()
        while (true) l = when {
            eat('+') -> op(l, product()) { x, y -> x + y }
            eat('-') -> op(l, product()) { x, y -> x - y }
            else -> return l
        }
    }

    private fun product(): Expr {
        var l = unary()
        while (true) l = when {
            eat('*') -> op(l, unary()) { x, y -> x * y }
            eat('/') -> op(l, unary()) { x, y -> x / y }
            else -> return l
        }
    }

    private fun unary(): Expr = if (eat('-')) unary().let { e -> { v -> e(v)?.unaryMinus() } } else atom()

    private fun atom(): Expr {
        if (eat('(')) return and().also { require(eat(')')) }
        val c = s.getOrNull(i) ?: throw IllegalArgumentException("formula ends early")
        if (c in 'A'..'D') {
            i++
            return { v -> v(c - 'A')?.toDouble() }
        }
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        val n = s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("unexpected '$c'")
        return { _ -> n }
    }

    private fun eat(c: Char) = (s.getOrNull(i) == c).also { if (it) i++ }

    private fun op(a: Expr, b: Expr, f: (Double, Double) -> Double): Expr = { v ->
        val x = a(v)
        val y = b(v)
        if (x == null || y == null) null else f(x, y)
    }
}

// Defaults (Prefs.BYTE_DEFAULTS / FORMULA_DEFAULTS): soot and temperature are DPFViewer's char offsets 38 and 16, confirmed
// by the Techstream capture (temp is Exhaust Temperature B1S2); regen is the capture's DPF status byte. Results are
// truncated like DPFViewer's int casts; the regen flag is on when its formula gives 1.
private val SOOT = Formula.parse(Prefs.FORMULA_DEFAULTS.getValue(Prefs.PID_SOOT))!!
private val TEMP = Formula.parse(Prefs.FORMULA_DEFAULTS.getValue(Prefs.PID_TEMP))!!
private val REGEN = Formula.parse(Prefs.FORMULA_DEFAULTS.getValue(Prefs.PID_REGEN))!!

fun sootOf(p: String, byte: Int = 19, f: Formula = SOOT): Int? = f.eval(p, byte)?.toInt()
fun tempOf(p: String, byte: Int = 8, f: Formula = TEMP): Int? = f.eval(p, byte)?.toInt()
fun regenOf(p: String, byte: Int = 11, f: Formula = REGEN): Boolean? = f.eval(p, byte)?.let { it == 1.0 }
