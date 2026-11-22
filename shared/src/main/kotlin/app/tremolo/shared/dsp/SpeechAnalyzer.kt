package app.tremolo.shared.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of the "pa-ta-ka" task: how fast and how evenly syllables were repeated. */
data class SyllableResult(
    val syllables: Int,
    val ratePerSecond: Double,
    /** Spread of the gaps between syllables, as a percentage of the average gap. */
    val rhythmVariationPct: Double,
)

/** Result of the held "aaah" task. */
data class SustainedVoiceResult(
    val seconds: Double,
    /** Wavering of loudness at 2 to 12 Hz, as a percentage of the average loudness. */
    val loudnessTremorPct: Double,
    /** Frequency of the wavering, only meaningful when [loudnessTremorPct] is clearly raised. */
    val tremorHz: Double,
)

/**
 * Speech measures from a microphone recording. Works on the loudness envelope only, so it
 * never looks at what was said, and callers can throw the audio away afterwards.
 *
 * Like the tremor thresholds, the constants here are starting guesses, not validated values.
 */
object SpeechAnalyzer {
    /** Loudness envelope rate in Hz (10 ms frames). */
    const val ENVELOPE_HZ = 100

    /** RMS loudness per 10 ms frame. */
    fun envelope(samples: ShortArray, sampleRate: Int): DoubleArray {
        val frame = sampleRate / ENVELOPE_HZ
        if (frame == 0) return DoubleArray(0)
        return DoubleArray(samples.size / frame) { i ->
            var sum = 0.0
            for (j in i * frame until (i + 1) * frame) sum += samples[j].toDouble() * samples[j]
            sqrt(sum / frame)
        }
    }

    /** Quiet and loud reference levels, or null if the recording holds no clear voice. */
    private fun levels(envelope: DoubleArray): Pair<Double, Double>? {
        if (envelope.size < ENVELOPE_HZ) return null
        val sorted = envelope.sortedArray()
        val floor = sorted[sorted.size / 10]
        val loud = sorted[sorted.size * 95 / 100]
        // 16-bit audio: below about 200 the "loud" part is still room noise.
        if (loud < 200 || loud < 3 * floor) return null
        return floor to loud
    }

    private fun smooth(values: DoubleArray, halfWidth: Int): DoubleArray = DoubleArray(values.size) { i ->
        val from = maxOf(0, i - halfWidth)
        val to = minOf(values.size - 1, i + halfWidth)
        var sum = 0.0
        for (j in from..to) sum += values[j]
        sum / (to - from + 1)
    }

    /** Counts syllables as loudness peaks that stand clearly above the dips around them. */
    fun syllables(samples: ShortArray, sampleRate: Int): SyllableResult? {
        val env = smooth(envelope(samples, sampleRate), 1)
        val (floor, loud) = levels(env) ?: return null
        val prominence = 0.25 * (loud - floor)
        val minHeight = floor + 0.3 * (loud - floor)

        val peaks = ArrayList<Int>()
        var lookingForPeak = true
        var best = env[0]
        var bestAt = 0
        for (i in env.indices) {
            val v = env[i]
            if (lookingForPeak) {
                if (v > best) { best = v; bestAt = i }
                if (v < best - prominence) {
                    if (best >= minHeight) peaks += bestAt
                    lookingForPeak = false
                    best = v
                }
            } else {
                if (v < best) best = v
                if (v > best + prominence) {
                    lookingForPeak = true
                    best = v
                    bestAt = i
                }
            }
        }
        if (peaks.size < 4) return null
        // Time each syllable by its onset (the rise through half height), which is sharper
        // than the top of the peak and so gives a truer picture of the rhythm.
        val onsets = peaks.map { peak ->
            val half = (floor + env[peak]) / 2
            var i = peak
            while (i > 0 && env[i - 1] >= half) i--
            if (i == 0) 0.0 else i - (env[i] - half) / (env[i] - env[i - 1])
        }
        val gaps = onsets.zipWithNext { a, b -> (b - a) / ENVELOPE_HZ }
        val mean = gaps.average()
        val spread = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
        return SyllableResult(peaks.size, 1.0 / mean, 100.0 * spread / mean)
    }

    /** Measures the longest unbroken stretch of voice in the recording. */
    fun sustained(samples: ShortArray, sampleRate: Int): SustainedVoiceResult? {
        val env = envelope(samples, sampleRate)
        val (floor, loud) = levels(env) ?: return null
        val voiced = floor + 0.3 * (loud - floor)
        val gate = smooth(env, 5) // bridges gaps shorter than about 0.1 s

        var bestStart = 0
        var bestLength = 0
        var start = -1
        for (i in 0..gate.size) {
            val on = i < gate.size && gate[i] >= voiced
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                if (i - start > bestLength) { bestLength = i - start; bestStart = start }
                start = -1
            }
        }
        if (bestLength < 2 * ENVELOPE_HZ) return null // under two seconds of voice

        // Drop the onset and release, where loudness changes for reasons other than tremor.
        val trim = ENVELOPE_HZ / 4
        val run = env.copyOfRange(bestStart + trim, bestStart + bestLength - trim)
        val mean = run.average()
        // Roughly 2 to 12 Hz: the fast average minus the slow one.
        val fast = smooth(run, 4)
        val slow = smooth(run, 25)
        val edge = 25
        if (run.size <= 2 * edge + ENVELOPE_HZ) return null
        val wave = DoubleArray(run.size - 2 * edge) { fast[it + edge] - slow[it + edge] }
        val depth = sqrt(wave.sumOf { it * it } / wave.size) / mean

        var bestHz = 0.0
        var bestPower = -1.0
        var hz = 2.0
        while (hz <= 12.0) {
            var re = 0.0
            var im = 0.0
            for (i in wave.indices) {
                val angle = 2 * PI * hz * i / ENVELOPE_HZ
                re += wave[i] * cos(angle)
                im -= wave[i] * sin(angle)
            }
            val power = re * re + im * im
            if (power > bestPower) { bestPower = power; bestHz = hz }
            hz += 0.25
        }
        return SustainedVoiceResult(bestLength.toDouble() / ENVELOPE_HZ, 100.0 * depth, bestHz)
    }
}
