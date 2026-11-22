package app.tremolo.shared.stats

import app.tremolo.shared.dsp.WindowFeatures

/**
 * Separates measurements taken during sleep from waking ones. Tremor stops in sleep, so
 * sleep measurements would otherwise count as "no tremor" and make the headline numbers
 * depend on how long the wearer slept rather than on the DBS setting.
 *
 * A single motionless measurement is not treated as sleep: an arm resting quietly while
 * awake is a genuine tremor-free moment and must stay in.
 */
object SleepClassifier {
    /** Below this combined rotation rate the arm is not moving at all. A starting guess. */
    const val MOTIONLESS_DEG_PER_SEC = 0.6

    /** Sleep is assumed when at least this many motionless measurements follow each other... */
    const val MIN_RUN = 3

    /** ...and together span at least this long. */
    const val MIN_RUN_MILLIS = 45 * 60_000L

    /** A longer hole in the data ends a run: nothing is known about what happened in it. */
    const val MAX_GAP_MILLIS = 2 * 3_600_000L

    fun isMotionless(features: WindowFeatures): Boolean =
        features.tremorRmsDegPerSec + features.lowFreqRmsDegPerSec < MOTIONLESS_DEG_PER_SEC

    class Split(val awake: List<Sample>, val asleep: List<Sample>)

    /**
     * [sleepSessions] are known sleep periods (start..end in epoch millis), for example from
     * the watch's own sleep tracking; every measurement inside one counts as sleep. Without
     * them, and for naps they miss, long motionless runs are used.
     */
    fun split(samples: List<Sample>, sleepSessions: List<LongRange> = emptyList()): Split {
        val sorted = samples.sortedBy { it.timeMillis }
        val asleep = BooleanArray(sorted.size) { i -> sleepSessions.any { sorted[i].timeMillis in it } }

        var runStart = -1
        fun closeRun(endExclusive: Int) {
            if (runStart < 0) return
            val count = endExclusive - runStart
            val span = sorted[endExclusive - 1].timeMillis - sorted[runStart].timeMillis
            if (count >= MIN_RUN && span >= MIN_RUN_MILLIS) {
                for (i in runStart until endExclusive) asleep[i] = true
            }
            runStart = -1
        }
        for (i in sorted.indices) {
            val still = isMotionless(sorted[i].features)
            val gapTooLong = i > 0 && sorted[i].timeMillis - sorted[i - 1].timeMillis > MAX_GAP_MILLIS
            if (!still || gapTooLong) closeRun(i)
            if (still && runStart < 0) runStart = i
        }
        closeRun(sorted.size)

        return Split(
            awake = sorted.filterIndexed { i, _ -> !asleep[i] },
            asleep = sorted.filterIndexed { i, _ -> asleep[i] },
        )
    }
}
