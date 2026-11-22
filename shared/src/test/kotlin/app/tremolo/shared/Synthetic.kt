package app.tremolo.shared

import app.tremolo.shared.model.RawWindow
import app.tremolo.shared.model.SensorSeries
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** A sine component: amplitude in deg/s for the gyroscope, m/s^2 for the accelerometer. */
data class Tone(val freqHz: Double, val amplitude: Double)

object Synthetic {
    private const val DEG_TO_RAD = PI / 180.0

    /** A signal over time made of sine [tones], optionally only inside a time span. */
    fun tones(
        vararg tones: Tone,
        fromSec: Double = 0.0,
        toSec: Double = Double.MAX_VALUE,
    ): (Double) -> Double = { t ->
        if (t in fromSec..toSec) tones.sumOf { it.amplitude * sin(2 * PI * it.freqHz * t) } else 0.0
    }

    /**
     * Builds a window from a gyroscope signal (deg/s, on the x axis) and an accelerometer
     * signal (m/s^2, added to gravity on the z axis), with Gaussian noise on every axis.
     * Sample times get a little jitter, like a real sensor, to exercise the resampler.
     */
    fun window(
        gyro: (Double) -> Double = { 0.0 },
        accel: (Double) -> Double = { 0.0 },
        seconds: Double = 20.0,
        fs: Double = 50.0,
        gyroNoiseDegPerSec: Double = 0.2,
        seed: Int = 1,
    ): RawWindow {
        val noise = java.util.Random(seed.toLong())
        val jitter = Random(seed)
        val n = (seconds * fs).toInt()
        val t = LongArray(n)
        val gx = FloatArray(n)
        val gy = FloatArray(n)
        val gz = FloatArray(n)
        val ax = FloatArray(n)
        val ay = FloatArray(n)
        val az = FloatArray(n)
        for (i in 0 until n) {
            val time = i / fs + jitter.nextDouble(-0.001, 0.001)
            t[i] = (time * 1e9).toLong() + 5_000_000_000L
            gx[i] = ((gyro(time) + noise.nextGaussian() * gyroNoiseDegPerSec) * DEG_TO_RAD).toFloat()
            gy[i] = (noise.nextGaussian() * gyroNoiseDegPerSec * DEG_TO_RAD).toFloat()
            gz[i] = (noise.nextGaussian() * gyroNoiseDegPerSec * DEG_TO_RAD).toFloat()
            ax[i] = (noise.nextGaussian() * 0.01).toFloat()
            ay[i] = (noise.nextGaussian() * 0.01).toFloat()
            az[i] = (9.80665 + accel(time) + noise.nextGaussian() * 0.01).toFloat()
        }
        return RawWindow(SensorSeries(t, ax, ay, az), SensorSeries(t.copyOf(), gx, gy, gz))
    }
}
