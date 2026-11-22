package app.tremolo.shared

import app.tremolo.shared.dsp.MovementContext
import app.tremolo.shared.dsp.WindowFeatures
import app.tremolo.shared.protocol.ProtocolJson
import app.tremolo.shared.protocol.TrialConfig
import app.tremolo.shared.stats.Sample
import app.tremolo.shared.stats.TrialStatistics
import app.tremolo.shared.stats.Verdict
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialStatisticsTest {
    private val hour = 3_600_000L

    private fun sample(
        timeMillis: Long,
        fraction: Double,
        amplitude: Double,
        context: MovementContext = MovementContext.REST,
    ) = Sample(
        timeMillis,
        WindowFeatures(
            durationSeconds = 20.0,
            dominantFreqHz = 5.0,
            tremorRmsDegPerSec = amplitude,
            peakRatio = 0.8,
            lowFreqRmsDegPerSec = 1.0,
            accelTremorRmsMg = 1.0,
            tremorFraction = fraction,
            tremorRmsWhenPresent = amplitude,
            context = context,
            tremorDetected = fraction >= 0.3,
        ),
    )

    private fun trial(n: Int, meanFraction: Double, meanAmplitude: Double, seed: Int): List<Sample> {
        val random = Random(seed)
        return List(n) {
            sample(
                timeMillis = it * hour / 4,
                fraction = (meanFraction + random.nextDouble(-0.1, 0.1)).coerceIn(0.0, 1.0),
                amplitude = meanAmplitude + random.nextDouble(-2.0, 2.0),
            )
        }
    }

    @Test
    fun `summary reports headline numbers and excludes walking and gross movement`() {
        val samples = listOf(
            sample(8 * hour, 1.0, 20.0),
            sample(9 * hour, 0.0, 2.0),
            sample(33 * hour, 0.5, 10.0),
            sample(34 * hour, 1.0, 90.0, MovementContext.GROSS),
            sample(35 * hour, 1.0, 90.0, MovementContext.WALKING),
        )
        val s = TrialStatistics.summarize(samples, ZoneOffset.UTC)
        assertEquals(5, s.windowCount)
        assertEquals(3, s.usableCount)
        assertEquals(50.0, s.tremorTimePct!!.value, 1e-9)
        assertEquals(10.0, s.medianAmplitude!!.value, 1e-9)
        assertEquals(100.0 * 2 / 3, s.windowsWithTremorPct!!, 1e-9)
        assertEquals(5.0, s.medianTremorFreqHz!!, 1e-9)
        assertEquals(setOf(8, 9), s.byHour.keys)
        assertEquals(2, s.byDay.size)
        assertEquals(1, s.byContext.getValue(MovementContext.GROSS).count)
        val estimate = s.tremorTimePct!!
        assertTrue(estimate.low <= 50.0 && estimate.high >= 50.0)
    }

    @Test
    fun `empty trial gives an empty summary`() {
        val s = TrialStatistics.summarize(emptyList(), ZoneOffset.UTC)
        assertEquals(0, s.windowCount)
        assertNull(s.tremorTimePct)
        assertNull(s.medianAmplitude)
    }

    @Test
    fun `clearly lower tremor is called better`() {
        val a = trial(60, meanFraction = 0.2, meanAmplitude = 8.0, seed = 1)
        val b = trial(60, meanFraction = 0.6, meanAmplitude = 20.0, seed = 2)
        val c = TrialStatistics.compare(a, b)
        assertEquals(Verdict.A_BETTER, c.tremorTime.verdict)
        assertEquals(Verdict.A_BETTER, c.amplitude.verdict)
        assertTrue(c.tremorTime.difference!!.value < 0)
        assertEquals(Verdict.B_BETTER, TrialStatistics.compare(b, a).tremorTime.verdict)
    }

    @Test
    fun `similar trials show no clear difference`() {
        val a = trial(60, meanFraction = 0.4, meanAmplitude = 12.0, seed = 3)
        val b = trial(60, meanFraction = 0.4, meanAmplitude = 12.0, seed = 4)
        val c = TrialStatistics.compare(a, b)
        assertEquals(Verdict.NO_CLEAR_DIFFERENCE, c.tremorTime.verdict)
        assertEquals(Verdict.NO_CLEAR_DIFFERENCE, c.amplitude.verdict)
    }

    @Test
    fun `too few samples refuses to compare`() {
        val a = trial(10, 0.2, 8.0, 1)
        val b = trial(60, 0.6, 20.0, 2)
        assertEquals(Verdict.NOT_ENOUGH_DATA, TrialStatistics.compare(a, b).tremorTime.verdict)
    }

    @Test
    fun `trial config round trips through json`() {
        val config = TrialConfig(trialId = "t1", startMillis = 1, endMillis = 2, windowSeconds = 15)
        val json = ProtocolJson.encodeToString(TrialConfig.serializer(), config)
        assertEquals(config, ProtocolJson.decodeFromString(TrialConfig.serializer(), json))
    }
}
