package com.yarisdoctor

import com.yarisdoctor.domain.drive.ObdLink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** ELM327 over a Bluetooth SPP or [BleLink] stream. Replies are cleaned exactly like ELMduino so DPFViewer's PID offsets still apply. */
class Elm(input: InputStream, private val output: OutputStream) : ObdLink {
    /**
     * Bytes from the dongle as they arrive, -1 once the link drops. A thread blocked in read() wakes the moment data comes,
     * where polling available() and sleeping 10 ms added up to 10 ms per burst. It ends when the socket closes.
     */
    private val rx = LinkedBlockingQueue<Int>()

    init {
        Thread({
            try {
                while (true) {
                    val c = input.read()
                    rx.put(c)
                    if (c < 0) break
                }
            } catch (e: IOException) {
                rx.put(-1)
            }
        }, "elm-rx").apply { isDaemon = true; start() }
    }

    /** One command's replies: [firstMs] send → first byte, [tailMs] last data byte → prompt (the ELM waiting for more), sums. */
    class Timing { var n = 0; var ok = 0; var firstMs = 0L; var tailMs = 0L; var totalMs = 0L }

    /** Per command sent (AT commands left out), for [Db.linkStats]: where each request's time goes on this dongle. */
    val timings = HashMap<String, Timing>()

    /** Returns the cleaned payload, or null on timeout / ELM error. Throws IOException when the link drops. */
    override fun send(cmd: String, timeoutMs: Long): String? {
        rx.clear()
        output.write("$cmd\r".toByteArray())
        output.flush()
        val t0 = System.nanoTime()
        var first = 0L
        var last = 0L
        val raw = StringBuilder()
        while (true) {
            val wait = t0 + timeoutMs * 1_000_000 - System.nanoTime()
            val c = (if (wait > 0) rx.poll(wait, TimeUnit.NANOSECONDS) else null) ?: run {
                time(cmd, t0, first, last, System.nanoTime(), false)
                return null
            }
            if (c < 0) throw IOException("dongle closed the link")
            val now = System.nanoTime()
            if (first == 0L) first = now
            if (c == '>'.code) {
                val r = parse(raw.toString())
                time(cmd, t0, first, last, now, r != null)
                return r
            }
            if (c != '\r'.code && c != ' '.code) last = now
            raw.append(c.toChar())
        }
    }

    override fun monitor(ms: Long): List<String> {
        rx.clear()
        output.write("AT MA\r".toByteArray())
        output.flush()
        val raw = StringBuilder()
        // The ELM ends monitoring on its own only on an error (BUFFER FULL): then the prompt has already come
        if (!readTo(raw, System.nanoTime() + ms * 1_000_000)) {
            output.write("\r".toByteArray()) // any byte stops it
            output.flush()
            // Read up to its prompt here, or the next send() takes this one as its own and returns early
            readTo(raw, System.nanoTime() + 2_000_000_000L)
        }
        return raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Appends to [raw] until the `>` prompt (true) or [deadline] (false). */
    private fun readTo(raw: StringBuilder, deadline: Long): Boolean {
        while (true) {
            val wait = deadline - System.nanoTime()
            val c = (if (wait > 0) rx.poll(wait, TimeUnit.NANOSECONDS) else null) ?: return false
            if (c < 0) throw IOException("dongle closed the link")
            if (c == '>'.code) return true
            raw.append(c.toChar())
        }
    }

    private fun time(cmd: String, t0: Long, first: Long, last: Long, end: Long, ok: Boolean) {
        if (cmd.startsWith("AT")) return
        val t = timings.getOrPut(cmd) { Timing() }
        t.n++
        if (ok) t.ok++
        t.firstMs += ((if (first > 0) first else end) - t0) / 1_000_000
        t.tailMs += (end - (if (last > 0) last else end)) / 1_000_000
        t.totalMs += (end - t0) / 1_000_000
    }

    /** Same sequence as DPFViewer: myELM.begin(..., '6') then Toyota engine ECU header/filter, plus adaptive timing for the full table. */
    override fun init() {
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
