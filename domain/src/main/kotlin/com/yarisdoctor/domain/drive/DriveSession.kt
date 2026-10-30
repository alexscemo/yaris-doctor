package com.yarisdoctor.domain.drive

import com.yarisdoctor.domain.car.AFTER_INJECTION_HOLD_MS
import com.yarisdoctor.domain.car.PID_REGEN
import com.yarisdoctor.domain.car.PID_SOOT
import com.yarisdoctor.domain.car.PID_TEMP
import com.yarisdoctor.domain.car.engineStopped
import com.yarisdoctor.domain.car.regenOf
import com.yarisdoctor.domain.car.sootOf
import com.yarisdoctor.domain.car.tempOf
import com.yarisdoctor.domain.health.Checker
import com.yarisdoctor.domain.health.Finding
import com.yarisdoctor.domain.health.sensorZero
import com.yarisdoctor.domain.health.shortTrips
import com.yarisdoctor.domain.health.trends
import com.yarisdoctor.domain.model.TRIP_GAP_MS
import com.yarisdoctor.domain.repo.DriveStore

/** The dongle as a drive sees it: Elm on the real Bluetooth link, a script in tests. */
interface ObdLink {
    /** The ELM setup sequence; re-run mid-drive after a dead cycle, which unwedges some clones. */
    fun init()

    /** The cleaned reply, or null on timeout / ELM error. Throws IOException when the link drops. */
    fun send(cmd: String, timeoutMs: Long = 2000): String?

    /** ELM `AT MA` for [ms]: the raw bus lines it printed, then stopped and back at the prompt. Throws IOException when the link drops. */
    fun monitor(ms: Long): List<String> = emptyList()
}

/** The meter reads only the real drive makes; tests and Debug mode leave them out. */
interface MeterHooks {
    /** Armed in Settings: run the odometer probe once, in the next drive. */
    fun probeArmed(): Boolean

    /** The probe ran at [t]: store [results] and disarm. */
    fun probed(t: Long, results: Map<String, String>)
}

/** What the drive shows outside the database: the service's notifications, its edge light and the screens' live reading. */
interface DriveEffects {
    /**
     * Every cycle's reading, at the dongle's own pace, before the log takes it: the screens show it at once instead of
     * waiting for a `sample` row. [soot] and [temp] are null when that reply was missed; [regen] carries the last known
     * state across a cycle without a `210C` reply.
     */
    fun live(t: Long, soot: Int?, temp: Int?, regen: Boolean)

    /** A regen is running: its start, the soot it started from, and the latest soot and DPF temperature. */
    fun regen(start: Long, sootStart: Int?, soot: Int?, temp: Int?)

    /** No regen, or the link is gone: the regen notification and edge light go away. */
    fun regenHidden()

    fun alertOpened(f: Finding)

    /** A value back in range: the notification [key] raised goes away. */
    fun alertCleared(key: String)

    /** The ECU has been silent for [REGEN_GAP_MS]: the drive is over. */
    fun driveEnded()
}

/**
 * Everything one service instance knows about the car between and during dongle sessions: the regen being recorded,
 * the live health checks and which alerts were notified. It outlives a Bluetooth drop shorter than [REGEN_GAP_MS], so a
 * flaky dongle neither splits a regen nor re-notifies a warning. Not thread-safe: the service's worker thread drives it.
 */
class DriveSession(
    private val store: DriveStore,
    private val effects: DriveEffects,
    private val model2015: Boolean,
    /** The maintenance_due warning, read from the logbook each time the history checks run. */
    private val maintenance: () -> Finding?,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    /** Fuel level every [Meter.FUEL_MS] and the odometer probe; null = engine only. */
    private val meterHooks: MeterHooks? = null,
) {
    /**
     * The car's odometer, metres: the cluster's whole-mile reading ([Meter.odometer]) plus the vehicle speed since
     * ([Odometer]). The service starts it from the newest logged reading.
     */
    @Volatile var odoM = 0.0
    /** The cluster was read in the drive that is open: the next read narrows the mile rather than starting fresh. */
    private var odoInDrive = false

    private val rec = Recorder(store)
    private var checker = Checker(model2015)
    private var trend = emptyList<Finding>() // regen-history findings, redone at start and after each regen
    private val notified = HashSet<String>()  // alert keys notified this drive: a value flapping around its limit notifies once
    private val showing = HashSet<String>()   // of those, the ones whose notification is still up
    private var lastSeen = 0L // last cycle the ECU answered
    private var holdUntil = Long.MIN_VALUE
    private var injectedAt = 0L // last cycle with after injection, and the odometer and soot then
    private var injectedOdoM = 0.0
    private var injectedSoot: Int? = null

    val inRegen get() = rec.inRegen

    /** A regen or a drive is still open: the link may come back within [REGEN_GAP_MS] and must find the logger running. */
    val busy get() = rec.inRegen || lastSeen > 0

    /** At service start: live alerts left open by a killed process end now; regen-history ones open if they apply. */
    fun start() {
        trend = history()
        checks(now(), emptyList())
    }

    /** Between dongle sessions: closes a regen or a drive whose link has been gone for longer than [REGEN_GAP_MS]. */
    fun idle() {
        // Link lost mid-regen and not back in time: the burn ended at the last reading
        if (rec.inRegen && now() - rec.lastT > REGEN_GAP_MS) {
            rec.close(rec.lastT, odoM)
            store.dropStopBlips()
            trend = history()
            checks(now(), emptyList())
        }
        // No ECU data for REGEN_GAP_MS: the drive is over, so its live alerts end and the next drive notifies afresh
        if (lastSeen > 0 && now() - lastSeen > REGEN_GAP_MS) {
            lastSeen = 0
            odoInDrive = false
            checker = Checker(model2015)
            effects.driveEnded()
            trend = history() // the engine-stop readings of this drive feed the DPF sensor check
            checks(now(), emptyList()) // closes the drive's live alerts and cancels their notifications
            notified.clear()
        }
    }

    /**
     * One connected dongle session: polls back to back until the ECU goes quiet (ignition off with the dongle still
     * powered) or [running] turns false. The regen and the live alerts stay open: [idle] closes them unless the link
     * comes back in time.
     */
    fun drive(link: ObdLink, running: () -> Boolean) {
        val poller = Poller { link.send(it) }
        val meter = Meter(link)
        var fuelAt = Long.MIN_VALUE / 2
        var fuelMisses = 0
        var odoAt = Long.MIN_VALUE / 2 // so the first cycle reads it
        var odoMisses = 0
        var lastKmh = 0.0
        var lastKmhT = 0L
        var fails = 0
        var seen = false
        var lastSample = 0L
        try {
            // Give up after 1 dead cycle on a probe, 3 mid-drive
            while (running() && fails < (if (seen) 3 else 1)) {
                val t = now()
                val replies = poller.cycle(t)
                if (replies.isEmpty()) {
                    fails++
                    // A wedged clone often recovers after a reset; the dead-cycle count still ends the session if not
                    if (seen) link.init() else sleep(2000)
                    continue
                }
                fails = 0
                seen = true
                lastSeen = t
                // Distance between cluster reads: the ECU's vehicle speed, added up cycle by cycle
                replies["210D"]?.substring(4, 6)?.toIntOrNull(16)?.toDouble()?.let { kmh ->
                    if (lastKmhT > 0) odoM = Odometer.advance(odoM, lastKmh, kmh, t - lastKmhT)
                    lastKmh = kmh
                    lastKmhT = t
                }
                store.frames(t, replies, odoM)
                val soot = replies[PID_SOOT]?.let(::sootOf)
                val temp = replies[PID_TEMP]?.let(::tempOf)
                val regen = burning(t, replies, soot)
                // The DPF log keeps its old pace (5 s, 1 s in regen); a regen start or end is written at once
                if (t - lastSample >= (if (rec.inRegen) 1000 else 5000) || (regen != null && regen != rec.inRegen)) {
                    publish(rec, soot, temp, regen, injectedAt, injectedOdoM, injectedSoot)
                    lastSample = t
                }
                checks(t, checker.check(t, replies, rec.inRegen))
                // After the row writes, so a screen that reads the database sees this same cycle; every cycle, so the
                // Overview and All data follow the car rather than the log's 5 s pace
                effects.live(t, soot, temp, regen ?: rec.inRegen)
                // Not during a regen: its 1 s log must not stall for the meter (the probe takes ~30 s)
                if (meterHooks != null && !rec.inRegen) {
                    // A meter that never answers while the engine does doesn't have it: stop asking for this session
                    if (fuelMisses < 3 && t - fuelAt >= Meter.FUEL_MS) {
                        fuelAt = t
                        val r = meter.fuel()
                        if (r == null) fuelMisses++ else { fuelMisses = 0; store.frames(t, mapOf(Meter.FUEL to r), odoM) }
                    }
                    // The dash odometer: at the start of the drive, then every few minutes to keep the speed sum honest
                    if (odoMisses < 3 && t - odoAt >= Meter.ODO_MS) {
                        odoAt = t
                        val miles = meter.odometer()
                        if (miles == null) odoMisses++ else {
                            odoMisses = 0
                            val before = odoM
                            odoM = Odometer.snap(before, miles, firstOfDrive = !odoInDrive)
                            odoInDrive = true
                            store.odometerRead(t, miles, before, odoM)
                        }
                    }
                    if (meterHooks.probeArmed()) meterHooks.probed(t, meter.probe())
                }
                // No pause: the next cycle starts at once, so the pace is set by how fast this dongle answers, regen or not
            }
        } finally {
            effects.regenHidden()
        }
    }

    /** Debug mode: [sim]'s readings once a second until [running] turns false, with their own regen and checks. */
    fun simulate(sim: Sim, running: () -> Boolean) {
        val rec = Recorder(store) // its own, never kept open across sessions like the real one
        var thinnedTo = 0L
        val checker = Checker(model2015)
        notified.clear()
        try {
            while (running()) {
                val r = sim.next()
                odoM += sim.metresPerTick
                publish(rec, r.soot, r.temp, r.regen)
                val t = now()
                val frames = sim.frames(r)
                store.frames(t, frames + (Meter.FUEL to sim.fuel()), odoM) // the checker gets only the engine's
                checks(t, checker.check(t, frames, rec.inRegen))
                effects.live(t, r.soot, r.temp, r.regen)
                // The debug Db lives in RAM: keep 30 min at full rate, older frames one per minute, or a day of it runs out of memory
                if (t - 30 * 60_000L - thinnedTo >= 60_000L) {
                    store.thinFrames(thinnedTo, t - 30 * 60_000L)
                    thinnedTo = t - 30 * 60_000L
                }
                sleep(1000)
            }
        } finally {
            rec.close(now(), odoM)
            effects.regenHidden()
            checks(now(), emptyList())
        }
    }

    /**
     * Whether a burn is on, or null when this cycle can't tell: ignition off mid-cycle answers some requests but not 210C
     * (22 Oct, 17:53). The burn's real end is the last after injection, kept for when [AFTER_INJECTION_HOLD_MS] has passed.
     */
    private fun burning(t: Long, replies: Map<String, String>, soot: Int?): Boolean? {
        val injecting = replies[PID_REGEN]?.let(::regenOf) ?: return null
        val rpm = replies["210C"] ?: return null
        if (engineStopped(rpm)) {
            holdUntil = Long.MIN_VALUE // a quick restart must not find the hold still running
            return false
        }
        if (injecting) {
            injectedAt = t
            injectedOdoM = odoM
            injectedSoot = soot ?: injectedSoot
            holdUntil = t + AFTER_INJECTION_HOLD_MS
        }
        return t <= holdUntil
    }

    private fun publish(rec: Recorder, soot: Int?, temp: Int?, regen: Boolean?, endT: Long = now(), endOdoM: Double = odoM, endSoot: Int? = soot) {
        val was = rec.inRegen
        rec.record(now(), soot, temp, regen, odoM, endT, endOdoM, endSoot)
        if (was && !rec.inRegen) {
            store.dropStopBlips() // a blip must not count as a regen in the interval checks
            trend = history() // a regen just finished: interval and length are known
        }
        if (rec.inRegen) effects.regen(rec.regenStart, rec.regenSootStart, soot, temp) else effects.regenHidden()
    }

    /** Stores [live] plus the regen-history findings as alerts; each is notified when it opens and cleared when it recovers. */
    private fun checks(t: Long, live: List<Finding>) {
        val active = live + trend
        // A value back in range: its alert row is closed (kept as history), and the notification it raised goes away
        showing.removeAll { k -> active.none { it.key == k }.also { gone -> if (gone) effects.alertCleared(k) } }
        for (f in store.syncAlerts(t, active, odoM)) {
            if (!notified.add(f.key)) continue
            showing += f.key
            effects.alertOpened(f)
        }
    }

    /**
     * Regen-history checks, the DPF sensor's engine-off zero and the maintenance reminders; kept open while they apply, rechecked at
     * start and after drives and regens. Only the last regens get their floor and peak: trends() reads the newest.
     */
    private fun history(): List<Finding> {
        val regens = store.recentRegens(10)
        val finished = regens.filter { it.end != null }
        val next = forecast(store.lastSample()?.soot, finished.lastOrNull()?.sootEnd, store.metresSinceRegen(), finished.mapNotNull { it.sootStart }, model2015)
        return trends(regens, model2015) +
            listOfNotNull(sensorZero(store.engineOffDp()), maintenance(), shortTrips(next.kmToRegen, store.trips()))
    }

    companion object {
        /** A regen or a drive outlives a link drop shorter than this; a trip ends at the same silence. */
        const val REGEN_GAP_MS = TRIP_GAP_MS
    }
}
