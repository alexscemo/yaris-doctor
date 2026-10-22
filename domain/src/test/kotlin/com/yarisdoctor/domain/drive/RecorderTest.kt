package com.yarisdoctor.domain.drive

import com.yarisdoctor.domain.repo.RegenLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RecorderTest {
    /** In-memory RegenLog: regen rows as [start, odoStart, sootStart, end, odoEnd, sootEnd], end fields null while open. */
    class FakeLog : RegenLog {
        val samples = mutableListOf<Pair<Long, Boolean>>()
        val regens = mutableListOf<Array<Any?>>()
        override fun sample(t: Long, soot: Int?, temp: Int?, regen: Boolean, odoM: Double) { samples += t to regen }
        override fun regenStart(t: Long, odoM: Double, soot: Int?): Long {
            regens += arrayOf(t, odoM, soot, null, null, null)
            return regens.lastIndex.toLong()
        }
        override fun regenEnd(id: Long, t: Long, odoM: Double, soot: Int?) {
            val r = regens[id.toInt()]
            r[3] = t; r[4] = odoM; r[5] = soot
        }
    }

    @Test fun aRegenOpensOnTheFlagAndClosesWhenItDrops() {
        val log = FakeLog()
        val rec = Recorder(log)
        rec.record(1_000, 80, 300, false, 0.0)
        rec.record(2_000, 82, 600, true, 100.0)
        assertTrue(rec.inRegen)
        assertEquals(2_000L, rec.regenStart)
        assertEquals(82, rec.regenSootStart)
        rec.record(3_000, 40, 620, true, 200.0)
        rec.record(4_000, 12, 500, false, 300.0)
        assertFalse(rec.inRegen)
        assertEquals(listOf<Any?>(2_000L, 100.0, 82, 4_000L, 300.0, 12), log.regens.single().toList())
        assertEquals(4, log.samples.size)
    }

    @Test fun aFailedReadKeepsTheLastState() {
        val log = FakeLog()
        val rec = Recorder(log)
        rec.record(1_000, 80, 600, true, 0.0)
        rec.record(2_000, null, null, null, 50.0) // like DPFViewer: no reply is not the end of a regen
        assertTrue(rec.inRegen)
        assertEquals(listOf(1_000L to true, 2_000L to true), log.samples)
        assertEquals(null, log.regens.single()[3])
    }

    @Test fun closeEndsAnOpenRegenWithNoSootAndIsANoOpOtherwise() {
        val log = FakeLog()
        val rec = Recorder(log)
        rec.close(500, 0.0) // nothing open: nothing written
        assertTrue(log.regens.isEmpty())
        rec.record(1_000, 80, 600, true, 0.0)
        rec.record(2_000, 70, 610, true, 80.0)
        rec.close(rec.lastT, 80.0) // link lost: the burn ended at the last reading
        assertFalse(rec.inRegen)
        assertEquals(listOf<Any?>(1_000L, 0.0, 80, 2_000L, 80.0, null), log.regens.single().toList())
    }

    @Test fun seededHistoryIsInTimeOrderWithEveryRegenClosed() {
        val log = FakeLog()
        val now = 20 * 86_400_000L
        Sim.seed(log, now, Random(1))
        assertTrue(log.regens.isNotEmpty())
        assertTrue(log.regens.all { it[3] != null }) // every drive ends with the engine off, so no regen stays open
        assertTrue(log.samples.all { it.first < now })
        assertEquals(log.samples.map { it.first }.sorted(), log.samples.map { it.first })
    }
}
