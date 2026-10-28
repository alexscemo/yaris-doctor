package com.yarisdoctor.domain.drive

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
 * flaky dongle neither splits a regen nor re-notifies a warning. Not thread-safe: the service's worker thread drives
 * it, except [odoM], which the GPS callback writes.
 */
class DriveSession(
    private val store: DriveStore,
    private val effects: DriveEffects,
    private val model2015: Boolean,
    /** The maintenance_due warning, read from the logbook each time the history checks run. */
    private val maintenance: () -> Finding?,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    /** GPS distance on this phone, metres. */
    @Volatile var odoM = 0.0

    private val rec = Recorder(store)
    private var checker = Checker(model2015)
    private var trend = emptyList<Finding>() // regen-history findings, redone at start and after each regen
    private val notified = HashSet<String>()  // alert keys notified this drive: a value flapping around its limit notifies once
    private val showing = HashSet<String>()   // of those, the ones whose notification is still up
    private var lastSeen = 0L // last cycle the ECU answered

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
                store.frames(t, replies)
                val soot = replies[PID_SOOT]?.let(::sootOf)
                val temp = replies[PID_TEMP]?.let(::tempOf)
                // The bit set without an rpm reply in the same cycle is unknown: ignition off mid-cycle answers 21DF but not 210C (22 Oct, 17:53)
                val regen = replies[PID_REGEN]?.let(::regenOf)?.let { on -> if (!on) false else replies["210C"]?.let { !engineStopped(it) } }
                // The DPF log keeps its old pace (5 s, 1 s in regen); a regen start or end is written at once
                if (t - lastSample >= (if (rec.inRegen) 1000 else 5000) || (regen != null && regen != rec.inRegen)) {
                    publish(rec, soot, temp, regen)
                    lastSample = t
                }
                checks(t, checker.check(t, replies, rec.inRegen))
                // After the row writes, so a screen that reads the database sees this same cycle; every cycle, so the
                // Overview and All data follow the car rather than the log's 5 s pace
                effects.live(t, soot, temp, regen ?: rec.inRegen)
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
                store.frames(t, frames)
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

    private fun publish(rec: Recorder, soot: Int?, temp: Int?, regen: Boolean?) {
        val was = rec.inRegen
        rec.record(now(), soot, temp, regen, odoM)
        if (was && !rec.inRegen) {
            store.dropStopBlips() // a blip that got past engineStopped() must not count as a regen in the interval checks
            trend = history() // a regen just finished: interval and length are known
        }
        if (rec.inRegen) effects.regen(rec.regenStart, rec.regenSootStart, soot, temp) else effects.regenHidden()
    }

    /** Stores [live] plus the regen-history findings as alerts; each is notified when it opens and cleared when it recovers. */
    private fun checks(t: Long, live: List<Finding>) {
        val active = live + trend
        // A value back in range: its alert row is closed (kept as history), and the notification it raised goes away
        showing.removeAll { k -> active.none { it.key == k }.also { gone -> if (gone) effects.alertCleared(k) } }
        for (f in store.syncAlerts(t, active)) {
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
