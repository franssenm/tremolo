package app.tremolo.shared.stats

import app.tremolo.shared.dsp.MovementContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Whether two sets of measurements were taken under conditions alike enough to compare. */
data class FairnessCheck(
    /** Plain-language reasons a difference may come from the circumstances, not the setting. */
    val concerns: List<String>,
    /**
     * What happens to the difference in time with tremor when both settings are compared
     * only within the same time of day and activity. Null when there was nothing to correct
     * for, or too little data to say.
     */
    val matchedNote: String? = null,
) {
    val fair: Boolean get() = concerns.isEmpty()
}

/**
 * Tremor changes with the time of day and with what the arm is doing. If one setting happened
 * to be measured mostly in quiet evenings and the other on busy mornings, the settings can
 * look different without being different. This looks for such imbalances.
 */
object Fairness {
    /** Shares that differ by at least this many percentage points count as an imbalance. */
    const val SHARE_GAP = 20.0

    /** Fewer days than this cannot show how much one day differs from the next. */
    const val MIN_DAYS = 3

    /** A group (time of day and activity) needs this many measurements on both sides to be matched. */
    const val MIN_PER_GROUP = 5

    private val DAY_PARTS = listOf("at night", "in the morning", "in the afternoon", "in the evening")

    fun check(a: List<Sample>, b: List<Sample>, labelA: String, labelB: String, zone: ZoneId): FairnessCheck {
        val ua = a.filter(TrialStatistics::isUsable)
        val ub = b.filter(TrialStatistics::isUsable)
        if (ua.isEmpty() || ub.isEmpty()) return FairnessCheck(emptyList())

        fun hour(sample: Sample) = Instant.ofEpochMilli(sample.timeMillis).atZone(zone).hour
        fun dayPart(sample: Sample) = hour(sample) / 6
        fun days(samples: List<Sample>) =
            samples.map { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }.distinct().size
        fun share(samples: List<Sample>, test: (Sample) -> Boolean) = 100.0 * samples.count(test) / samples.size

        val concerns = ArrayList<String>()
        var imbalanced = false

        // The part of the day where the two differ most.
        val worstPart = DAY_PARTS.indices.maxByOrNull { part ->
            abs(share(ua) { dayPart(it) == part } - share(ub) { dayPart(it) == part })
        }!!
        val partA = share(ua) { dayPart(it) == worstPart }
        val partB = share(ub) { dayPart(it) == worstPart }
        if (abs(partA - partB) >= SHARE_GAP) {
            imbalanced = true
            val (more, moreShare, less, lessShare) =
                if (partA > partB) Four(labelA, partA, labelB, partB) else Four(labelB, partB, labelA, partA)
            concerns += "The time of day differs: ${moreShare.roundToInt()}% of the measurements of $more were " +
                "taken ${DAY_PARTS[worstPart]}, against ${lessShare.roundToInt()}% for $less."
        }

        val restA = share(ua) { it.features.context == MovementContext.REST }
        val restB = share(ub) { it.features.context == MovementContext.REST }
        if (abs(restA - restB) >= SHARE_GAP) {
            imbalanced = true
            val (more, moreShare, less, lessShare) =
                if (restA > restB) Four(labelA, restA, labelB, restB) else Four(labelB, restB, labelA, restA)
            concerns += "The activity differs: with $more the arm was at rest in ${moreShare.roundToInt()}% of " +
                "the measurements, against ${lessShare.roundToInt()}% with $less."
        }

        listOf(labelA to days(ua), labelB to days(ub)).forEach { (label, count) ->
            if (count < MIN_DAYS) {
                concerns += if (count == 1) {
                    "$label was measured on only 1 day, so an unusual day can explain a difference."
                } else {
                    "$label was measured on only $count days, so an unusual day can explain a difference."
                }
            }
        }

        val note = if (imbalanced && minOf(ua.size, ub.size) >= TrialStatistics.MIN_SAMPLES_TO_COMPARE) {
            matchedNote(ua, ub) { dayPart(it) * 2 + if (it.features.context == MovementContext.REST) 0 else 1 }
        } else {
            null
        }
        return FairnessCheck(concerns, note)
    }

    /**
     * Compares the two only inside groups both have enough of, weighting each group the same
     * for both, and says whether the difference in time with tremor survives that.
     */
    private fun matchedNote(a: List<Sample>, b: List<Sample>, group: (Sample) -> Int): String {
        fun tremorPct(samples: List<Sample>) = samples.map { it.features.tremorFraction * 100.0 }.average()
        val groupsA = a.groupBy(group)
        val groupsB = b.groupBy(group)
        var weightSum = 0.0
        var weighted = 0.0
        for ((key, inA) in groupsA) {
            val inB = groupsB[key] ?: continue
            if (inA.size < MIN_PER_GROUP || inB.size < MIN_PER_GROUP) continue
            val weight = (inA.size.toDouble() / a.size + inB.size.toDouble() / b.size) / 2
            weightSum += weight
            weighted += weight * (tremorPct(inA) - tremorPct(inB))
        }
        if (weightSum < 0.5) {
            return "The circumstances overlap too little to correct for this, so treat the comparison with caution."
        }
        val raw = tremorPct(a) - tremorPct(b)
        val matched = weighted / weightSum
        val rawPoints = abs(raw).roundToInt()
        val matchedPoints = abs(matched).roundToInt()
        return when {
            rawPoints == 0 && matchedPoints == 0 ->
                "Compared only within the same time of day and activity, there is still no difference in time with tremor."
            raw * matched < 0 && matchedPoints >= 1 ->
                "Compared only within the same time of day and activity, the difference in time with tremor " +
                    "turns around. The circumstances may explain the result."
            abs(matched) < 0.5 * abs(raw) ->
                "Compared only within the same time of day and activity, the difference in time with tremor " +
                    "shrinks from $rawPoints to $matchedPoints percentage points. The circumstances may explain much of it."
            else ->
                "Compared only within the same time of day and activity, the difference in time with tremor " +
                    "stays about the same ($matchedPoints percentage points), so the circumstances probably do not explain it."
        }
    }

    private data class Four(val more: String, val moreShare: Double, val less: String, val lessShare: Double)
}
