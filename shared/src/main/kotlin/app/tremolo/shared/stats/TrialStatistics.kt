package app.tremolo.shared.stats

import app.tremolo.shared.dsp.MovementContext
import app.tremolo.shared.dsp.WindowFeatures
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** One analysed capture window with the wall-clock time it was taken. */
data class Sample(val timeMillis: Long, val features: WindowFeatures)

/** A value with a 95% bootstrap confidence interval. */
data class Estimate(val value: Double, val low: Double, val high: Double)

data class GroupSummary(
    val count: Int,
    /** Average share of time with tremor, 0..100. */
    val tremorTimePct: Double,
    /** Median tremor-band rotation rate, deg/s. */
    val medianAmplitude: Double,
)

data class TrialSummary(
    /** All hidden captures in the trial. */
    val windowCount: Int,
    /** Captures not taken while walking or in large movement; the headline numbers use only these. */
    val usableCount: Int,
    val tremorTimePct: Estimate?,
    val windowsWithTremorPct: Double?,
    val medianAmplitude: Estimate?,
    val p90Amplitude: Double?,
    /** Median dominant frequency over the windows where tremor was detected. */
    val medianTremorFreqHz: Double?,
    val byContext: Map<MovementContext, GroupSummary>,
    val byHour: Map<Int, GroupSummary>,
    val byDay: Map<LocalDate, GroupSummary>,
    /** Gait measures from the captures taken while walking; null when there were none. */
    val walking: WalkingSummary? = null,
)

data class WalkingSummary(
    val count: Int,
    val medianCadenceSpm: Double,
    val medianArmSwingDegPerSec: Double,
)

enum class Verdict { A_BETTER, B_BETTER, NO_CLEAR_DIFFERENCE, NOT_ENOUGH_DATA }

data class MetricComparison(
    val verdict: Verdict,
    /** A minus B with its confidence interval; negative means A has less tremor. */
    val difference: Estimate?,
)

data class Comparison(val tremorTime: MetricComparison, val amplitude: MetricComparison)

object TrialStatistics {
    /** Below this many usable windows per trial a comparison is not attempted. */
    const val MIN_SAMPLES_TO_COMPARE = 30
    private const val RESAMPLES = 1000

    fun summarize(samples: List<Sample>, zone: ZoneId, seed: Int = 42): TrialSummary {
        val usable = samples.filter(::isUsable)
        val fractions = usable.map { it.features.tremorFraction * 100.0 }.toDoubleArray()
        val amplitudes = usable.map { it.features.tremorRmsDegPerSec }.toDoubleArray()
        val detected = usable.filter { it.features.tremorDetected }
        val random = Random(seed)

        return TrialSummary(
            windowCount = samples.size,
            usableCount = usable.size,
            tremorTimePct = bootstrap(fractions, random, ::mean),
            windowsWithTremorPct =
                if (usable.isEmpty()) null else 100.0 * detected.size / usable.size,
            medianAmplitude = bootstrap(amplitudes, random, ::median),
            p90Amplitude = if (amplitudes.isEmpty()) null else percentile(amplitudes, 0.9),
            medianTremorFreqHz =
                if (detected.isEmpty()) null
                else median(detected.map { it.features.dominantFreqHz }.toDoubleArray()),
            byContext = samples.groupBy { it.features.context }.mapValues { group(it.value) },
            byHour = usable.groupBy { localTime(it, zone).hour }
                .toSortedMap().mapValues { group(it.value) },
            byDay = usable.groupBy { localTime(it, zone).toLocalDate() }
                .toSortedMap().mapValues { group(it.value) },
            walking = samples.filter { it.features.context == MovementContext.WALKING && it.features.cadenceSpm > 0 }
                .takeIf { it.isNotEmpty() }
                ?.let { walks ->
                    WalkingSummary(
                        count = walks.size,
                        medianCadenceSpm = median(walks.map { it.features.cadenceSpm }.toDoubleArray()),
                        medianArmSwingDegPerSec =
                            median(walks.map { it.features.armSwingDegPerSec }.toDoubleArray()),
                    )
                },
        )
    }

    /** Compares two trials on tremor time and amplitude; lower is better for both. */
    fun compare(a: List<Sample>, b: List<Sample>, seed: Int = 42): Comparison {
        val ua = a.filter(::isUsable)
        val ub = b.filter(::isUsable)
        val random = Random(seed)
        return Comparison(
            tremorTime = compareMetric(
                ua.map { it.features.tremorFraction * 100.0 }.toDoubleArray(),
                ub.map { it.features.tremorFraction * 100.0 }.toDoubleArray(),
                random, ::mean,
            ),
            amplitude = compareMetric(
                ua.map { it.features.tremorRmsDegPerSec }.toDoubleArray(),
                ub.map { it.features.tremorRmsDegPerSec }.toDoubleArray(),
                random, ::median,
            ),
        )
    }

    /** Tremor cannot be judged reliably while walking or during large movement. */
    fun isUsable(sample: Sample): Boolean =
        sample.features.context != MovementContext.GROSS &&
            sample.features.context != MovementContext.WALKING

    private fun compareMetric(
        a: DoubleArray,
        b: DoubleArray,
        random: Random,
        statistic: (DoubleArray) -> Double,
    ): MetricComparison {
        if (a.size < MIN_SAMPLES_TO_COMPARE || b.size < MIN_SAMPLES_TO_COMPARE) {
            return MetricComparison(Verdict.NOT_ENOUGH_DATA, null)
        }
        val diffs = DoubleArray(RESAMPLES) {
            statistic(resample(a, random)) - statistic(resample(b, random))
        }
        val estimate = Estimate(
            value = statistic(a) - statistic(b),
            low = percentile(diffs, 0.025),
            high = percentile(diffs, 0.975),
        )
        val verdict = when {
            estimate.high < 0 -> Verdict.A_BETTER
            estimate.low > 0 -> Verdict.B_BETTER
            else -> Verdict.NO_CLEAR_DIFFERENCE
        }
        return MetricComparison(verdict, estimate)
    }

    fun group(samples: List<Sample>) = GroupSummary(
        count = samples.size,
        tremorTimePct = mean(samples.map { it.features.tremorFraction * 100.0 }.toDoubleArray()),
        medianAmplitude = median(samples.map { it.features.tremorRmsDegPerSec }.toDoubleArray()),
    )

    private fun localTime(sample: Sample, zone: ZoneId) =
        Instant.ofEpochMilli(sample.timeMillis).atZone(zone).toLocalDateTime()

    private fun bootstrap(
        values: DoubleArray,
        random: Random,
        statistic: (DoubleArray) -> Double,
    ): Estimate? {
        if (values.isEmpty()) return null
        val stats = DoubleArray(RESAMPLES) { statistic(resample(values, random)) }
        return Estimate(statistic(values), percentile(stats, 0.025), percentile(stats, 0.975))
    }

    private fun resample(values: DoubleArray, random: Random) =
        DoubleArray(values.size) { values[random.nextInt(values.size)] }

    /** Angle in degrees between two directions given as 3-vectors. */
    fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val na = kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = kotlin.math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na == 0.0 || nb == 0.0) return 180.0
        return Math.toDegrees(kotlin.math.acos((dot / (na * nb)).coerceIn(-1.0, 1.0)))
    }

    internal fun mean(values: DoubleArray): Double = if (values.isEmpty()) 0.0 else values.average()

    internal fun median(values: DoubleArray): Double = percentile(values, 0.5)

    /** Linear-interpolated percentile, [p] in 0..1. */
    internal fun percentile(values: DoubleArray, p: Double): Double {
        require(values.isNotEmpty())
        val sorted = values.sortedArray()
        val position = p * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower])
    }
}
