package app.tremolo.shared.dsp

import app.tremolo.shared.model.RawWindow
import app.tremolo.shared.model.SensorSeries
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
enum class MovementContext {
    /** Arm essentially still: tremor seen here is rest or postural tremor. */
    REST,

    /** Ordinary arm use (eating, gesturing, handling things). */
    ACTIVE,

    /** Large vigorous movement; tremor measures are unreliable. */
    GROSS,

    /** Walking, recognised from the step rhythm; tremor measures are unreliable. */
    WALKING,
}

/** What a 4-second stretch of a capture looks like. [code] is used in compact debug strings. */
enum class SegmentClass(val code: Char) {
    /** Too little tremor-band movement to matter. */
    QUIET('Q'),

    /** One clear, sustained frequency in the tremor band. */
    TREMOR('T'),

    /** Step rhythm present in the accelerometer. */
    WALKING('W'),

    /** Tremor-band peak is a harmonic of a stronger slow rhythm (waving, stirring, brushing). */
    RHYTHMIC('H'),

    /** No peak of its own: just the upper tail of ordinary slow movement reaching into the band. */
    SLOW('S'),

    /** Energy smeared across the band with no clear frequency (typing, handling objects). */
    BROADBAND('B'),

    /** Spiky signal from taps or bumps. */
    IMPACT('I'),

    /** A clear rhythm, but outside this person's own calibrated tremor frequencies. */
    OTHER_RHYTHM('O'),
}

/** Tunable thresholds. Defaults are starting points to be tuned on labelled recordings. */
data class AnalyzerParams(
    val sampleRateHz: Double = 50.0,
    val segmentSeconds: Double = 4.0,
    val hopSeconds: Double = 1.0,
    val tremorLoHz: Double = 3.0,
    val tremorHiHz: Double = 12.0,
    val lowLoHz: Double = 0.3,
    val lowHiHz: Double = 3.0,
    val totalLoHz: Double = 0.5,
    val totalHiHz: Double = 20.0,
    /** Half-width of the band around the spectral peak that counts as "the peak". */
    val peakHalfWidthHz: Double = 1.5,
    /** Tremor only counts above this tremor-band rotation rate. */
    val minTremorRmsDegPerSec: Double = 2.0,
    /** Share of tremor-band power that must sit around the peak; below this it is broadband. */
    val minConcentration: Double = 0.5,
    /** Peak-to-RMS ratio of the band-passed signal above which a segment is an impact. */
    val maxCrestFactor: Double = 4.0,
    /** Tremor must last at least this long without a break; shorter bursts are ignored. */
    val minBurstSeconds: Double = 3.0,
    /** Time resolution used when measuring how long tremor lasted. */
    val binSeconds: Double = 0.5,
    /** A slow rhythm this many times stronger than the tremor peak may explain it as a harmonic. */
    val harmonicLowDominance: Double = 4.0,
    val cadenceLoHz: Double = 1.3,
    val cadenceHiHz: Double = 2.6,
    /** Minimum step-rhythm acceleration (m/s^2 RMS) to call a segment walking. */
    val minCadenceAccel: Double = 1.0,
    /** A window is labelled walking when at least this share of its segments are. */
    val walkingWindowFraction: Double = 0.5,
    val restMaxLowRmsDegPerSec: Double = 10.0,
    val grossMinLowRmsDegPerSec: Double = 60.0,
    /**
     * This person's own tremor frequency range, learned from labelled recordings. When set,
     * rhythms outside it are not counted as tremor.
     */
    val personalLoHz: Double? = null,
    val personalHiHz: Double? = null,
)

/** Everything computed from one capture window. Rotation rates are in degrees per second. */
@Serializable
data class WindowFeatures(
    val durationSeconds: Double,
    /** Strongest frequency inside the tremor band (of the tremor segments, when there are any). */
    val dominantFreqHz: Double,
    /** RMS rotation rate in the tremor band over the whole window, tremor or not. */
    val tremorRmsDegPerSec: Double,
    /** Share of 0.5-20 Hz power concentrated around the tremor peak (0..1); high = rhythmic. */
    val peakRatio: Double,
    /** RMS rotation rate of slow voluntary movement (0.3-3 Hz). */
    val lowFreqRmsDegPerSec: Double,
    /** RMS linear acceleration in the tremor band, in milli-g. */
    val accelTremorRmsMg: Double,
    /** Share of the window's time classified as sustained tremor (0..1). */
    val tremorFraction: Double,
    /** RMS tremor-band rotation rate during the tremor time only; 0 when there is none. */
    val tremorRmsWhenPresent: Double,
    val context: MovementContext,
    val tremorDetected: Boolean,
    /** Share of the window's segments recognised as walking (0..1). */
    val walkingFraction: Double = 0.0,
    /** One [SegmentClass.code] per one-second step through the window, for debugging. */
    val segmentClasses: String = "",
    /** Average direction of gravity in the watch's frame (unit vector): how the wrist was held. */
    val gravityX: Double = 0.0,
    val gravityY: Double = 0.0,
    val gravityZ: Double = 0.0,
    /** Steps per minute while walking; 0 when not walking. */
    val cadenceSpm: Double = 0.0,
    /** RMS arm-swing rotation rate while walking; 0 when not walking. */
    val armSwingDegPerSec: Double = 0.0,
)

object TremorAnalyzer {
    /** Bump whenever the analysis changes, so stored captures are re-analysed. */
    const val VERSION = 4

    private const val RAD_TO_DEG = 180.0 / PI
    private const val G = 9.80665

    /** Returns null when the window holds too little data to analyse. */
    fun analyze(window: RawWindow, params: AnalyzerParams = AnalyzerParams()): WindowFeatures? {
        val fs = params.sampleRateHz
        val segLen = (params.segmentSeconds * fs).toInt()
        val hop = (params.hopSeconds * fs).toInt().coerceAtLeast(1)
        val gyro = resample(window.gyro, fs) ?: return null
        val n = gyro[0].size
        if (n < segLen) return null
        val accel = resample(window.accel, fs)?.takeIf { it[0].size >= segLen }

        // Tremor-band rotation speed over time, for amplitude, timing and spikiness.
        val band = bandPassMagnitude(gyro, fs, params.tremorLoHz, params.tremorHiHz)

        val gyroPsds = ArrayList<Psd>()
        val accelPsds = ArrayList<Psd?>()
        val classes = ArrayList<SegmentClass>()
        var start = 0
        while (start + segLen <= n) {
            val gyroPsd = Spectrum.segmentPsd(gyro, start, segLen, fs)
            val accelPsd = accel
                ?.takeIf { start + segLen <= it[0].size }
                ?.let { Spectrum.segmentPsd(it, start, segLen, fs) }
            gyroPsds += gyroPsd
            accelPsds += accelPsd
            classes += classify(gyroPsd, accelPsd, band, start, segLen, params)
            start += hop
        }

        // Walk through the window in short time bins. A bin is tremor when the tremor-band
        // movement is strong enough there AND the surrounding segment looks like tremor.
        val binLen = (params.binSeconds * fs).toInt().coerceAtLeast(1)
        val binCount = n / binLen
        val minRms = params.minTremorRmsDegPerSec / RAD_TO_DEG
        val binPower = DoubleArray(binCount)
        val candidate = BooleanArray(binCount)
        for (b in 0 until binCount) {
            var sum = 0.0
            for (i in b * binLen until (b + 1) * binLen) sum += band[i] * band[i]
            binPower[b] = sum / binLen
            val centre = b * binLen + binLen / 2
            val segment = ((centre - segLen / 2).toDouble() / hop).roundToInt()
                .coerceIn(0, classes.size - 1)
            candidate[b] = sqrt(binPower[b]) >= minRms && classes[segment] == SegmentClass.TREMOR
        }
        val tremorBins = sustainedRuns(candidate, (params.minBurstSeconds / params.binSeconds).roundToInt())
        var tremorBinCount = 0
        var tremorPowerSum = 0.0
        for (b in 0 until binCount) {
            if (tremorBins[b]) {
                tremorBinCount++
                tremorPowerSum += binPower[b]
            }
        }

        val mean = Psd.average(gyroPsds)
        val lowRms = sqrt(mean.bandPower(params.lowLoHz, params.lowHiHz)) * RAD_TO_DEG
        val walkingFraction = classes.count { it == SegmentClass.WALKING }.toDouble() / classes.size
        val context = when {
            walkingFraction >= params.walkingWindowFraction -> MovementContext.WALKING
            lowRms >= params.grossMinLowRmsDegPerSec -> MovementContext.GROSS
            lowRms >= params.restMaxLowRmsDegPerSec -> MovementContext.ACTIVE
            else -> MovementContext.REST
        }

        // Report the frequency of the tremor itself when there is some, not of other movement.
        val tremorPsds = gyroPsds.filterIndexed { i, _ -> classes[i] == SegmentClass.TREMOR }
        val freqSource = if (tremorBinCount > 0 && tremorPsds.isNotEmpty()) Psd.average(tremorPsds) else mean
        val peak = freqSource.peakBin(params.tremorLoHz, params.tremorHiHz)

        val allAccel = accelPsds.filterNotNull()
        // Gait measures, from the segments recognised as walking.
        val walkingAccel = accelPsds.filterIndexed { i, psd -> psd != null && classes[i] == SegmentClass.WALKING }
            .filterNotNull()
        val walkingGyro = gyroPsds.filterIndexed { i, _ -> classes[i] == SegmentClass.WALKING }
        val cadence = if (walkingAccel.isEmpty()) 0.0 else {
            val psd = Psd.average(walkingAccel)
            psd.frequencyOf(psd.peakBin(params.cadenceLoHz, params.cadenceHiHz)) * 60.0
        }
        val armSwing = if (walkingGyro.isEmpty()) 0.0
        else sqrt(Psd.average(walkingGyro).bandPower(params.lowLoHz, params.lowHiHz)) * RAD_TO_DEG
        val gravity = accel?.let { unitMean(it) } ?: doubleArrayOf(0.0, 0.0, 0.0)

        return WindowFeatures(
            durationSeconds = n / fs,
            dominantFreqHz = if (peak >= 0) freqSource.frequencyOf(peak) else 0.0,
            tremorRmsDegPerSec = sqrt(mean.bandPower(params.tremorLoHz, params.tremorHiHz)) * RAD_TO_DEG,
            peakRatio = peakShare(mean, params, params.totalLoHz, params.totalHiHz),
            lowFreqRmsDegPerSec = lowRms,
            accelTremorRmsMg =
                if (allAccel.isEmpty()) 0.0
                else sqrt(Psd.average(allAccel).bandPower(params.tremorLoHz, params.tremorHiHz)) / G * 1000.0,
            tremorFraction = if (binCount > 0) tremorBinCount.toDouble() / binCount else 0.0,
            tremorRmsWhenPresent =
                if (tremorBinCount > 0) sqrt(tremorPowerSum / tremorBinCount) * RAD_TO_DEG else 0.0,
            context = context,
            tremorDetected = tremorBinCount > 0,
            walkingFraction = walkingFraction,
            segmentClasses = String(CharArray(classes.size) { classes[it].code }),
            gravityX = gravity[0],
            gravityY = gravity[1],
            gravityZ = gravity[2],
            cadenceSpm = cadence,
            armSwingDegPerSec = armSwing,
        )
    }

    /** Averaged gyroscope spectrum of a window in (deg/s)^2 per Hz, for plotting. */
    fun spectrum(window: RawWindow, params: AnalyzerParams = AnalyzerParams()): Psd? {
        val fs = params.sampleRateHz
        val segLen = (params.segmentSeconds * fs).toInt()
        val gyro = resample(window.gyro, fs) ?: return null
        if (gyro[0].size < segLen) return null
        val psds = ArrayList<Psd>()
        var start = 0
        while (start + segLen <= gyro[0].size) {
            psds += Spectrum.segmentPsd(gyro, start, segLen, fs)
            start += segLen / 2
        }
        val mean = Psd.average(psds)
        return Psd(mean.df, DoubleArray(mean.power.size) { mean.power[it] * RAD_TO_DEG * RAD_TO_DEG })
    }

    private fun classify(
        gyroPsd: Psd,
        accelPsd: Psd?,
        band: DoubleArray,
        start: Int,
        length: Int,
        p: AnalyzerParams,
    ): SegmentClass {
        val minPower = square(p.minTremorRmsDegPerSec / RAD_TO_DEG)
        return when {
            accelPsd != null && isWalking(accelPsd, p) -> SegmentClass.WALKING
            gyroPsd.bandPower(p.tremorLoHz, p.tremorHiHz) < minPower -> SegmentClass.QUIET
            isTailOfSlowMovement(gyroPsd, p) -> SegmentClass.SLOW
            isHarmonicOfSlowRhythm(gyroPsd, p) -> SegmentClass.RHYTHMIC
            peakShare(gyroPsd, p, p.tremorLoHz, p.tremorHiHz) < p.minConcentration -> SegmentClass.BROADBAND
            crestFactor(band, start, length) > p.maxCrestFactor -> SegmentClass.IMPACT
            isOutsidePersonalRange(gyroPsd, p) -> SegmentClass.OTHER_RHYTHM
            else -> SegmentClass.TREMOR
        }
    }

    private fun isOutsidePersonalRange(psd: Psd, p: AnalyzerParams): Boolean {
        val lo = p.personalLoHz ?: return false
        val hi = p.personalHiHz ?: return false
        val peak = psd.peakBin(p.tremorLoHz, p.tremorHiHz)
        return peak >= 0 && psd.frequencyOf(peak) !in lo..hi
    }

    /** Mean of each axis, scaled to unit length; zeros if the mean is zero. */
    private fun unitMean(axes: List<DoubleArray>): DoubleArray {
        val mean = DoubleArray(3) { axes[it].average() }
        val norm = sqrt(mean[0] * mean[0] + mean[1] * mean[1] + mean[2] * mean[2])
        return if (norm > 0) DoubleArray(3) { mean[it] / norm } else mean
    }

    /** Steps show up as a strong accelerometer line at the step rate that dominates everything else. */
    private fun isWalking(accelPsd: Psd, p: AnalyzerParams): Boolean {
        if (sqrt(accelPsd.bandPower(p.cadenceLoHz, p.cadenceHiHz)) < p.minCadenceAccel) return false
        val strongest = accelPsd.frequencyOf(accelPsd.peakBin(0.8, p.tremorHiHz))
        return strongest in p.cadenceLoHz..p.cadenceHiHz
    }

    /**
     * Voluntary movement is strongest at low frequencies and fades upwards, so its tail can
     * reach into the tremor band without forming a peak there. Real tremor stands above what
     * lies just below the band.
     */
    private fun isTailOfSlowMovement(psd: Psd, p: AnalyzerParams): Boolean {
        val peak = psd.peakBin(p.tremorLoHz, p.tremorHiHz)
        if (peak < 0) return false
        val justBelow = psd.bandPower(p.tremorLoHz - 1.0, p.tremorLoHz - psd.df) / (1.0 - psd.df)
        return justBelow >= psd.power[peak]
    }

    /**
     * True when the tremor-band peak sits on a multiple of a much stronger slow rhythm and is
     * no stronger than the harmonic below it, i.e. it is part of that movement, not tremor.
     */
    private fun isHarmonicOfSlowRhythm(psd: Psd, p: AnalyzerParams): Boolean {
        val lowBin = psd.peakBin(0.8, p.tremorLoHz - psd.df)
        val peakBin = psd.peakBin(p.tremorLoHz, p.tremorHiHz)
        if (lowBin < 0 || peakBin < 0) return false
        val lowFreq = psd.frequencyOf(lowBin)
        val peakFreq = psd.frequencyOf(peakBin)
        fun powerAt(freq: Double) = psd.bandPower(freq - 0.4, freq + 0.4)

        val peakPower = powerAt(peakFreq)
        if (powerAt(lowFreq) < p.harmonicLowDominance * peakPower) return false
        val multiple = (peakFreq / lowFreq).roundToInt()
        if (multiple < 2) return false
        // The error in the slow frequency is multiplied along with it.
        if (abs(peakFreq - multiple * lowFreq) > 0.2 + 0.1 * multiple) return false
        // Harmonics fade as they go up; a peak well above the one below it is its own thing.
        if (multiple >= 3 && peakPower > 2.0 * powerAt((multiple - 1) * lowFreq)) return false
        return true
    }

    /** Share of the power in [loHz, hiHz] that lies around the tremor-band peak. */
    private fun peakShare(psd: Psd, p: AnalyzerParams, loHz: Double, hiHz: Double): Double {
        val peak = psd.peakBin(p.tremorLoHz, p.tremorHiHz)
        if (peak < 0) return 0.0
        val total = psd.bandPower(loHz, hiHz)
        if (total <= 0.0) return 0.0
        val f = psd.frequencyOf(peak)
        val around = psd.bandPower(
            maxOf(f - p.peakHalfWidthHz, loHz), minOf(f + p.peakHalfWidthHz, hiHz),
        )
        return (around / total).coerceIn(0.0, 1.0)
    }

    private fun crestFactor(band: DoubleArray, start: Int, length: Int): Double {
        var max = 0.0
        var sum = 0.0
        for (i in start until start + length) {
            if (band[i] > max) max = band[i]
            sum += band[i] * band[i]
        }
        val rms = sqrt(sum / length)
        return if (rms > 0) max / rms else 0.0
    }

    /**
     * Keeps only the true entries that belong to a run of at least [minBins], where a run may
     * contain single-bin gaps. Short isolated bursts (a handshake, a gesture) are dropped.
     */
    private fun sustainedRuns(candidate: BooleanArray, minBins: Int): BooleanArray {
        val kept = BooleanArray(candidate.size)
        var i = 0
        while (i < candidate.size) {
            if (!candidate[i]) {
                i++
                continue
            }
            var end = i
            var j = i + 1
            while (j < candidate.size) {
                val bridgesSingleGap = j - end <= 1 && j + 1 < candidate.size && candidate[j + 1]
                if (candidate[j]) end = j else if (!bridgesSingleGap) break
                j++
            }
            if (end - i + 1 >= minBins) {
                for (k in i..end) kept[k] = candidate[k]
            }
            i = end + 1
        }
        return kept
    }

    /**
     * Magnitude over time of the 3-axis signal after keeping only [loHz, hiHz], done by
     * zeroing the other frequencies of the whole window.
     */
    private fun bandPassMagnitude(axes: List<DoubleArray>, fs: Double, loHz: Double, hiHz: Double): DoubleArray {
        val n = axes[0].size
        val nfft = Fft.nextPowerOfTwo(n)
        val magnitudeSquared = DoubleArray(n)
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)
        for (axis in axes) {
            re.fill(0.0)
            im.fill(0.0)
            Spectrum.detrendInto(axis, 0, n, re)
            Fft.transform(re, im)
            for (k in 0 until nfft) {
                val freq = minOf(k, nfft - k) * fs / nfft
                // Soft band edges: a hard cut-off would ring for seconds around any sudden
                // movement and make a short burst look long.
                val gain = when {
                    freq < loHz - BAND_EDGE_HZ || freq > hiHz + BAND_EDGE_HZ -> 0.0
                    freq < loHz -> raisedCosine((freq - (loHz - BAND_EDGE_HZ)) / BAND_EDGE_HZ)
                    freq > hiHz -> raisedCosine(((hiHz + BAND_EDGE_HZ) - freq) / BAND_EDGE_HZ)
                    else -> 1.0
                }
                re[k] *= gain
                im[k] *= -gain // conjugate, so a forward transform performs the inverse
            }
            Fft.transform(re, im)
            for (i in 0 until n) {
                val value = re[i] / nfft
                magnitudeSquared[i] += value * value
            }
        }
        return DoubleArray(n) { sqrt(magnitudeSquared[it]) }
    }

    /** Linear interpolation of an unevenly sampled series onto a uniform grid at [fs] Hz. */
    internal fun resample(series: SensorSeries, fs: Double): List<DoubleArray>? {
        val n = series.size
        if (n < 2) return null
        val t0 = series.timestampsNanos[0]
        val durationSec = (series.timestampsNanos[n - 1] - t0) / 1e9
        if (durationSec <= 0) return null
        val outSize = (durationSec * fs).toInt() + 1
        val x = DoubleArray(outSize)
        val y = DoubleArray(outSize)
        val z = DoubleArray(outSize)
        var j = 0
        for (i in 0 until outSize) {
            val t = i / fs
            while (j < n - 2 && (series.timestampsNanos[j + 1] - t0) / 1e9 < t) j++
            val ta = (series.timestampsNanos[j] - t0) / 1e9
            val tb = (series.timestampsNanos[j + 1] - t0) / 1e9
            val w = if (tb > ta) ((t - ta) / (tb - ta)).coerceIn(0.0, 1.0) else 0.0
            x[i] = series.x[j] + w * (series.x[j + 1] - series.x[j])
            y[i] = series.y[j] + w * (series.y[j + 1] - series.y[j])
            z[i] = series.z[j] + w * (series.z[j + 1] - series.z[j])
        }
        return listOf(x, y, z)
    }

    /** Smooth 0..1 ramp for x in 0..1. */
    private fun raisedCosine(x: Double) = 0.5 - 0.5 * kotlin.math.cos(PI * x.coerceIn(0.0, 1.0))

    private const val BAND_EDGE_HZ = 1.0

    private fun square(v: Double) = v * v
}
