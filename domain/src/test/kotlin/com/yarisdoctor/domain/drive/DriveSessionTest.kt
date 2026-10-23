package com.yarisdoctor.domain.drive

import com.yarisdoctor.domain.health.Finding
import com.yarisdoctor.domain.model.Regen
import com.yarisdoctor.domain.repo.DriveStore
import com.yarisdoctor.domain.repo.RegenLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DriveSessionTest {
    private var t = 0L
    private val log = RecorderTest.FakeLog()
    private val store = FakeStore(log)
    private val effects = FakeEffects()
    private val sleeps = mutableListOf<Long>()
    private val session = DriveSession(store, effects, model2015 = false, oilMetres = { null }, now = { t }, sleep = { sleeps += it; t += it })
    private val sim = Sim(0f, 0f, 0, 0.0, Random(1))

    /** Every request takes 100 ms on the fake clock; [script] says what the ECU answers at that moment (null = silent). */
    inner class ScriptLink(private val script: (Long) -> Map<String, String>?) : ObdLink {
        var inits = 0
        val sent = mutableListOf<String>()
        override fun init() { inits++ }
        override fun send(cmd: String, timeoutMs: Long): String? {
            t += 100
            sent += cmd
            return script(t)?.get(cmd.take(4)) // the Poller adds the response-count suffix: "21DD1"
        }
    }

    class FakeStore(private val log: RecorderTest.FakeLog) : DriveStore, RegenLog by log {
        var frames = 0
        val lids = HashSet<String>()
        val open = HashSet<String>()
        override fun frames(t: Long, replies: Map<String, String>) { frames++; lids += replies.keys }
        override fun hasFrame(lid: String) = lid in lids
        override fun syncAlerts(t: Long, active: List<Finding>): List<Finding> {
            open.retainAll(active.map { it.key }.toSet())
            return active.filter { open.add(it.key) }
        }
        override fun dropStopBlips() {}
        override fun recentRegens(n: Int) = log.regens.takeLast(n).map { Regen(it[0] as Long, it[3] as Long?, null, it[2] as Int?, it[5] as Int?) }
        override fun engineOffDp(limit: Int) = emptyList<Double>()
        override fun thinFrames(from: Long, to: Long) {}
    }

    class FakeEffects : DriveEffects {
        val events = mutableListOf<String>()
        override fun regen(start: Long, sootStart: Int?, soot: Int?, temp: Int?) { events += "regen" }
        override fun regenHidden() { events += "hidden" }
        override fun alertOpened(f: Finding) { events += "open ${f.key}" }
        override fun alertCleared(key: String) { events += "clear $key" }
        override fun driveEnded() { events += "ended" }
    }

    private fun car(soot: Int = 60, temp: Int = 300, regen: Boolean = false) = sim.frames(Sim.Reading(soot, temp, regen))

    @Test fun calibrationIdIsReadOnceADriveUntilStored() {
        val cal = "490401" + "3839363631304430".repeat(2)
        val none = ScriptLink { if (it < t0 + 20_000) car() else null }
        t0 = t
        session.drive(none) { true }
        assertEquals(2, none.sent.count { it == Poller.CAL_ID }) // unanswered: one try and its retry, not every cycle
        val answers = ScriptLink { if (it < t0 + 20_000) car() + (Poller.CAL_ID to cal) else null }
        t0 = t
        session.drive(answers) { true }
        assertEquals(1, answers.sent.count { it == Poller.CAL_ID })
        assertTrue(Poller.CAL_ID in store.lids)
        val later = ScriptLink { if (it < t0 + 20_000) car() + (Poller.CAL_ID to cal) else null }
        t0 = t
        session.drive(later) { true }
        assertFalse(Poller.CAL_ID in later.sent) // stored: never asked again
    }
    /** Start of the current drive in [calibrationIdIsReadOnceADriveUntilStored]. */
    private var t0 = 0L

    @Test fun aQuickProbeOfASilentEcuEndsAfterOneDeadCycle() {
        val link = ScriptLink { null }
        session.drive(link) { true }
        assertEquals(listOf(2000L), sleeps) // waits before giving up, but doesn't reset the dongle
        assertEquals(0, link.inits)
        assertEquals(0, store.frames)
        assertEquals(listOf("hidden"), effects.events)
    }

    @Test fun silenceMidDriveEndsAfterThreeDeadCyclesEachResettingTheDongle() {
        val link = ScriptLink { if (it < 30_000) car() else null }
        session.drive(link) { true }
        assertEquals(3, link.inits)
        assertTrue(sleeps.isEmpty())
        assertTrue(store.frames > 5)
    }

    @Test fun aRegenOpensAndClosesWithTheFlag() {
        val link = ScriptLink { when { it < 20_000 -> car(soot = 80, temp = 600, regen = true); it < 40_000 -> car(soot = 16); else -> null } } // even: the frame holds soot / 2
        session.drive(link) { true }
        val r = log.regens.single()
        assertEquals(80, r[2])
        assertEquals(16, r[5])
        assertFalse(session.inRegen)
        assertTrue("regen" in effects.events)
        assertEquals("hidden", effects.events.last())
    }

    @Test fun theRegenBitWithTheEngineStoppedIsNotARegen() {
        val link = ScriptLink { if (it < 20_000) car(regen = true) + ("210C" to "610C0000") else null }
        session.drive(link) { true }
        assertTrue(log.regens.isEmpty())
        assertFalse("regen" in effects.events)
    }

    @Test fun theRegenBitWithoutAnRpmReplyNeitherOpensNorClosesARegen() {
        session.drive(ScriptLink { if (it < 20_000) car(regen = true) - "210C" else null }) { true }
        assertTrue(log.regens.isEmpty()) // ignition off mid-cycle: 21DF answered, 210C didn't
        session.drive(ScriptLink { if (it < 40_000) car(regen = true).let { r -> if (it < 30_000) r else r - "210C" } else null }) { true }
        assertEquals(1, log.regens.size)
        assertTrue(session.inRegen) // a missed rpm read mid-regen keeps it open
    }

    @Test fun aRegenSurvivesAShortDropAndClosesAtItsLastReadingAfterALongOne() {
        session.drive(ScriptLink { if (it < 20_000) car(soot = 80, temp = 600, regen = true) else null }) { true }
        assertTrue(session.inRegen) // the link dropped mid-regen: still open
        t += 60_000
        session.idle()
        val back = t
        session.drive(ScriptLink { if (it < back + 20_000) car(soot = 50, temp = 610, regen = true) else null }) { true }
        assertEquals(1, log.regens.size) // the same burn, not a second row
        val lastReading = log.samples.last().first
        t += DriveSession.REGEN_GAP_MS + 1
        session.idle()
        assertFalse(session.inRegen)
        assertEquals(lastReading, log.regens.single()[3])
        assertNull(log.regens.single()[5]) // no soot reading at the cut
    }

    @Test fun aWarningNotifiesOncePerDriveAndClearsWhenTheDriveEnds() {
        // The captured idle frames hold one stored fault code, which the dtc rule reports every cycle
        val link = { start: Long -> ScriptLink { if (it < start + 30_000) car() else null } }
        session.drive(link(t)) { true }
        t += 30_000
        session.idle() // a short drop: the same drive
        session.drive(link(t)) { true }
        assertTrue(session.busy) // the ECU went quiet less than REGEN_GAP_MS ago: the drive may go on
        assertEquals(1, effects.events.count { it == "open dtc" })
        t += DriveSession.REGEN_GAP_MS + 1
        session.idle()
        assertTrue(effects.events.containsAll(listOf("ended", "clear dtc")))
        assertFalse(session.busy) // the drive is over: the service may pause
        session.drive(link(t)) { true }
        assertEquals(2, effects.events.count { it == "open dtc" }) // a new drive notifies afresh
    }
}
