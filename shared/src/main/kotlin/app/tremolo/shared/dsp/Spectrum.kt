package app.tremolo.shared.dsp

import kotlin.math.PI
import kotlin.math.cos

/**
 * One-sided power spectral density. [power] is in (signal units)^2 per Hz; bin k sits at k * [df] Hz.
 * Integrating over a band therefore gives the signal variance contributed by that band.
 */
class Psd(val df: Double, val power: DoubleArray) {

    fun bandPower(loHz: Double, hiHz: Double): Double {
        var sum = 0.0
        for (k in binRange(loHz, hiHz)) sum += power[k]
        return sum * df
    }

    /** Index of the strongest bin inside the band, or -1 if the band is empty. */
    fun peakBin(loHz: Double, hiHz: Double): Int {
        var best = -1
        var bestPower = -1.0
        for (k in binRange(loHz, hiHz)) {
            if (power[k] > bestPower) {
                bestPower = power[k]
                best = k
            }
        }
        return best
    }

    fun frequencyOf(bin: Int): Double = bin * df

    private fun binRange(loHz: Double, hiHz: Double): IntRange {
        val lo = kotlin.math.ceil(loHz / df).toInt().coerceAtLeast(0)
        val hi = kotlin.math.floor(hiHz / df).toInt().coerceAtMost(power.size - 1)
        return lo..hi
    }

    companion object {
        /** Averages several PSDs that share the same resolution. */
        fun average(psds: List<Psd>): Psd {
            require(psds.isNotEmpty())
            val out = DoubleArray(psds[0].power.size)
            for (p in psds) for (k in out.indices) out[k] += p.power[k]
            for (k in out.indices) out[k] /= psds.size
            return Psd(psds[0].df, out)
        }
    }
}

internal object Spectrum {

    /**
     * PSD of one segment, summed over all [axes], so band power is the variance of the
     * 3-D vector signal. Each axis is linearly detrended (removes gravity and slow drift)
     * and Hann-windowed before the FFT.
     */
    fun segmentPsd(axes: List<DoubleArray>, start: Int, length: Int, fs: Double): Psd {
        val nfft = Fft.nextPowerOfTwo(length)
        val window = DoubleArray(length) { 0.5 - 0.5 * cos(2.0 * PI * it / (length - 1)) }
        val windowEnergy = window.sumOf { it * it }
        val bins = nfft / 2 + 1
        val power = DoubleArray(bins)
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)
        val scale = 2.0 / (fs * windowEnergy)

        for (axis in axes) {
            re.fill(0.0)
            im.fill(0.0)
            detrendInto(axis, start, length, re)
            for (i in 0 until length) re[i] *= window[i]
            Fft.transform(re, im)
            for (k in 0 until bins) power[k] += (re[k] * re[k] + im[k] * im[k]) * scale
        }
        return Psd(fs / nfft, power)
    }

    /** Copies src[start, start+length) into dst with the least-squares line removed. */
    fun detrendInto(src: DoubleArray, start: Int, length: Int, dst: DoubleArray) {
        val meanX = (length - 1) / 2.0
        var meanY = 0.0
        for (i in 0 until length) meanY += src[start + i]
        meanY /= length
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until length) {
            val dx = i - meanX
            sxy += dx * (src[start + i] - meanY)
            sxx += dx * dx
        }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        for (i in 0 until length) dst[i] = src[start + i] - meanY - slope * (i - meanX)
    }
}
