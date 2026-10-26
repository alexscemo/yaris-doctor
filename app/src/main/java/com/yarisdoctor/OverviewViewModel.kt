package com.yarisdoctor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.yarisdoctor.domain.health.DpfCondition
import com.yarisdoctor.domain.model.Alert
import com.yarisdoctor.domain.model.FLOOR_AFTER_MS
import com.yarisdoctor.domain.model.Regen
import com.yarisdoctor.domain.model.Sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Everything the Overview shows, read in one go off the main thread. */
class OverviewState(
    val now: Long,
    val last: Sample?,
    val regens: List<Regen>,
    val metresSinceRegen: Double?,
    val sootSeries: List<Pair<Long, Float>>,
    /** Open alerts, then recently ended ones (the Overview shows the open ones). */
    val alerts: List<Alert>,
    /** The same instance until it is reloaded, so the screen redraws its chart only then. */
    val dpf: DpfCard,
    /** The newest regens with floor and peak, newest first; the same instance until the list changes. */
    val history: List<Regen>,
)

/** The DPF condition card: conditions with a base, the newest ash record read and where it changed. */
class DpfCard(val conditions: List<DpfCondition>, val ash: Pair<Long, String>?, val ashChanges: List<Pair<Long, String>>)

/**
 * The reads that only change with the regen log (or, for the soot trend, another phone's rows): kept between the 5 s
 * refreshes so the Overview doesn't rebuild its whole history every time.
 */
private class RegenCache(val regens: List<Regen>, val metresSinceRegen: Double?, val sootSeries: List<Pair<Long, Float>>)

/** Loads the Overview's data and keeps the costly parts between refreshes (and across a rotation). */
class OverviewViewModel(app: Application) : AndroidViewModel(app) {
    private val lock = Mutex() // the 5 s refresh and a tab switch can ask at once; the caches are shared
    private var dpf: DpfCard? = null
    private var dpfKey = ""
    private var dpfAt = 0L
    private var history: List<Regen>? = null
    private var historyKey = ""
    private var regensCache: RegenCache? = null
    private var regensKey = ""
    private var regensAt = 0L

    suspend fun load(): OverviewState = withContext(Dispatchers.IO) {
        lock.withLock {
            val db = getApplication<Application>().container.db()
            val now = System.currentTimeMillis()
            // The regen list, the distance since the last one and the soot trend change slowly: once a minute, or at once
            // when a regen is added or closed. Debug mode is in the key: turning it on or off swaps the database
            val rk = "${Prefs.debug} ${db.regenSignature()}"
            val rc = regensCache?.takeIf { rk == regensKey && now - regensAt <= 60_000 }
                ?: RegenCache(db.regens(), db.metresSinceRegen(), db.sootSeries(now - 14 * DAY_MS))
                    .also { regensCache = it; regensKey = rk; regensAt = now }
            val regens = rc.regens
            // Joins frames per regen, so only when a regen is added or closed, or once a minute for the latest one's rate
            val dk = "${Prefs.debug} ${regens.size} ${regens.lastOrNull()?.end}"
            val card = dpf?.takeIf { dk == dpfKey && now - dpfAt <= 60_000 }
                ?: DpfCard(db.dpfConditions().filter { it.base != null }.takeLast(20), db.ashRecord(), db.ashHistory())
                    .also { dpf = it; dpfKey = dk; dpfAt = now }
            // Signs cost a few queries per regen: redone when the list changes or the newest regen's floor becomes readable
            val hk = "${Prefs.debug} ${regens.size} ${regens.lastOrNull()?.end} ${regens.lastOrNull()?.end?.let { now - it > 2 * FLOOR_AFTER_MS }}"
            val signs = history?.takeIf { hk == historyKey }
                ?: db.withSigns(regens.takeLast(50)).reversed().also { history = it; historyKey = hk }
            OverviewState(now, db.lastSample(), regens, rc.metresSinceRegen, rc.sootSeries, db.alerts(now), card, signs)
        }
    }

    companion object {
        private const val DAY_MS = 86_400_000L
    }
}
