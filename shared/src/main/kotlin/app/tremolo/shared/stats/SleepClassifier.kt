package app.tremolo.shared.stats

import app.tremolo.shared.dsp.WindowFeatures

/**
 * Separates measurements taken during sleep from waking ones. Tremor stops in sleep, so
 * sleep measurements would otherwise count as "no tremor" and make the headline numbers
 * depend on how long the wearer slept rather than on the DBS setting.
 *
 * A short quiet spell is not treated as sleep: an arm resting quietly while awake is a
 * genuine tremor-free moment and must stay in.
 */
object SleepClassifier {
    /** Below this combined rotation rate the arm is not moving at all. A starting guess. */
    const val MOTIONLESS_DEG_PER_SEC = 0.6

    /**
     * Below this combined rotation rate the arm is as still as in sleep. Sleeping arms drift
     * and twitch a little, so this is looser than [MOTIONLESS_DEG_PER_SEC]. In the nights
     * this was tuned on, sleeping checks stayed under it and daytime checks never did.
     */
    const val QUIET_DEG_PER_SEC = 1.5

    /** Sleep is assumed when a quiet stretch holds at least this many quiet measurements... */
    const val MIN_RUN = 5

    /** ...spans at least this long... */
    const val MIN_RUN_MILLIS = 60 * 60_000L

    /** ...and at least this share of its measurements is quiet. */
    const val MIN_QUIET_SHARE = 0.8

    /** A longer hole in the data ends a run: nothing is known about what happened in it. */
    const val MAX_GAP_MILLIS = 2 * 3_600_000L

    fun isMotionless(features: WindowFeatures): Boolean =
        features.tremorRmsDegPerSec + features.lowFreqRmsDegPerSec < MOTIONLESS_DEG_PER_SEC

    fun isQuiet(features: WindowFeatures): Boolean =
        features.tremorRmsDegPerSec + features.lowFreqRmsDegPerSec < QUIET_DEG_PER_SEC

    class Split(val awake: List<Sample>, val asleep: List<Sample>)

    /**
     * [sleepSessions] are known sleep periods (start..end in epoch millis), for example from
     * the watch's own sleep tracking; every measurement inside one counts as sleep. Without
     * them, and for naps they miss, long quiet stretches are used.
     *
     * A quiet stretch runs from one quiet measurement to another and may hold single
     * measurements with movement in between (turning over), but not two in a row. Those
     * single ones count as sleep too, as they would inside a tracked sleep session.
     */
    fun split(samples: List<Sample>, sleepSessions: List<LongRange> = emptyList()): Split {
        val sorted = samples.sortedBy { it.timeMillis }
        val asleep = BooleanArray(sorted.size) { i -> sleepSessions.any { sorted[i].timeMillis in it } }
        val quiet = BooleanArray(sorted.size) { isQuiet(sorted[it].features) }

        var i = 0
        while (i < sorted.size) {
            if (!quiet[i]) {
                i++
                continue
            }
            val start = i
            var last = i
            var j = i + 1
            while (j < sorted.size) {
                if (sorted[j].timeMillis - sorted[j - 1].timeMillis > MAX_GAP_MILLIS) break
                if (quiet[j]) last = j else if (j - last >= 2) break
                j++
            }
            val quietCount = (start..last).count { quiet[it] }
            val span = sorted[last].timeMillis - sorted[start].timeMillis
            if (quietCount >= MIN_RUN && span >= MIN_RUN_MILLIS && quietCount >= MIN_QUIET_SHARE * (last - start + 1)) {
                for (k in start..last) asleep[k] = true
            }
            i = last + 1
        }

        return Split(
            awake = sorted.filterIndexed { index, _ -> !asleep[index] },
            asleep = sorted.filterIndexed { index, _ -> asleep[index] },
        )
    }
}
