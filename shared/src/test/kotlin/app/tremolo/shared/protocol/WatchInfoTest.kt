package app.tremolo.shared.protocol

import app.tremolo.shared.model.RawWindow
import app.tremolo.shared.model.SensorSeries
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchInfoTest {
    private fun series(seconds: Int, hz: Int, offset: Float, value: (Double, Random) -> Float): SensorSeries {
        val random = Random(3)
        val n = seconds * hz
        return SensorSeries(
            LongArray(n) { it * 1_000_000_000L / hz },
            FloatArray(n) { offset + value(it.toDouble() / hz, random) },
            FloatArray(n) { value(it.toDouble() / hz, random) },
            FloatArray(n) { value(it.toDouble() / hz, random) },
        )
    }

    @Test
    fun measuresDeliveredRate() {
        assertEquals(50, Stillness.rateHz(series(20, 50, 0f) { _, _ -> 0f }))
        assertEquals(25, Stillness.rateHz(series(20, 25, 0f) { _, _ -> 0f }))
    }

    @Test
    fun watchOnATableLooksUnworn() {
        // Sensor noise only: about 0.05 deg/s on the gyroscope.
        val noise = { scale: Float -> { _: Double, r: Random -> (r.nextFloat() - 0.5f) * scale } }
        val window = RawWindow(series(20, 50, 9.81f, noise(0.01f)), series(20, 50, 0f, noise(0.002f)))
        assertTrue(Stillness.looksUnworn(window))
    }

    @Test
    fun restingWristDoesNotLookUnworn() {
        // A slow 0.6 deg/s sway from breathing on top of the same noise.
        val gyro = series(20, 50, 0f) { t, r ->
            (0.0105 * sin(2 * PI * 0.25 * t)).toFloat() + (r.nextFloat() - 0.5f) * 0.002f
        }
        val accel = series(20, 50, 9.81f) { _, r -> (r.nextFloat() - 0.5f) * 0.01f }
        assertFalse(Stillness.looksUnworn(RawWindow(accel, gyro)))
    }

    @Test
    fun problemsNameWhatIsWrong() {
        assertTrue(WatchInfo(measuredGyroHz = 50).problems().isEmpty())
        assertFalse(WatchInfo(hasGyroscope = false).usable)
        assertFalse(WatchInfo(measuredGyroHz = 20).usable)
        assertTrue(WatchInfo(measuredGyroHz = 40).usable)
        assertEquals(1, WatchInfo(hasOffBodySensor = false, measuredGyroHz = 50).problems().size)
    }
}
