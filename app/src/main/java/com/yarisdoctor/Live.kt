package com.yarisdoctor

import com.yarisdoctor.domain.model.Sample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The newest reading straight off the dongle, for the screens. The service pushes one per poll cycle, so [MainActivity]
 * redraws as fast as the car answers (a cycle takes about 1-2 s) instead of waiting out its fallback tick.
 *
 * Nothing here is stored or synced: the log's `sample` row keeps its own pace (5 s, 1 s in a regen), so a screen that
 * follows every reading costs the database nothing. [Reading.debug] rides along because Debug mode swaps the database:
 * a simulated reading must never be shown over the real log.
 */
object Live {
    /** A reading is "live" on the screens for this long; older and they say "last reading" instead. */
    const val LIVE_MS = 30_000L

    /** A cycle that missed a code keeps the last value: after this gap the old one is not the car's any more. */
    private const val CARRY_MS = 10_000L

    class Reading(val sample: Sample, val debug: Boolean)

    private val _reading = MutableStateFlow<Reading?>(null)
    val reading: StateFlow<Reading?> = _reading.asStateFlow()

    /**
     * One cycle's reading: [soot] and [temp] are null when their reply was missed (the Poller retries, then moves on),
     * and [t] is when the cycle began. Both carry over from the reading just before it, so a single missed 21DD doesn't
     * blank the card for a second.
     */
    fun push(t: Long, soot: Int?, temp: Int?, regen: Boolean, debug: Boolean) {
        val prev = _reading.value?.sample?.takeIf { t - it.t <= CARRY_MS }
        _reading.value = Reading(Sample(t, soot ?: prev?.soot, temp ?: prev?.temp, regen), debug)
    }
}
