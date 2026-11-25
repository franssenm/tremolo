package app.tremolo.shared

import app.tremolo.shared.dsp.MovementContext
import app.tremolo.shared.dsp.WindowFeatures
import app.tremolo.shared.stats.Sample
import app.tremolo.shared.stats.SleepClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepClassifierTest {
    private val minute = 60_000L

    private fun sample(atMinute: Int, movement: Double) = Sample(
        atMinute * minute,
        WindowFeatures(
            durationSeconds = 20.0,
            dominantFreqHz = 5.0,
            tremorRmsDegPerSec = movement / 2,
            peakRatio = 0.1,
            lowFreqRmsDegPerSec = movement / 2,
            accelTremorRmsMg = 0.0,
            tremorFraction = 0.0,
            tremorRmsWhenPresent = 0.0,
            context = MovementContext.REST,
            tremorDetected = false,
        ),
    )

    @Test
    fun aNightOfMotionlessChecksIsSleep() {
        val evening = listOf(sample(0, 8.0), sample(20, 3.0))
        val night = (1..12).map { sample(60 + it * 30, 0.3) }
        val morning = listOf(sample(480, 5.0))
        val split = SleepClassifier.split(evening + night + morning)
        assertEquals(12, split.asleep.size)
        assertEquals(3, split.awake.size)
    }

    @Test
    fun oneQuietMomentWhileAwakeStaysIn() {
        val samples = listOf(sample(0, 6.0), sample(15, 0.3), sample(30, 4.0), sample(45, 0.2), sample(60, 7.0))
        assertEquals(0, SleepClassifier.split(samples).asleep.size)
    }

    @Test
    fun shortMotionlessRunIsNotSleep() {
        // Three still checks, but within half an hour: sitting quietly, not sleeping.
        val samples = listOf(sample(0, 6.0), sample(10, 0.3), sample(20, 0.3), sample(30, 0.3), sample(40, 6.0))
        assertEquals(0, SleepClassifier.split(samples).asleep.size)
    }

    @Test
    fun aLongHoleInTheDataBreaksARun() {
        // Two still checks, five hours without data (watch charging), then two more.
        val samples = listOf(sample(0, 0.3), sample(30, 0.3), sample(330, 0.3), sample(360, 0.3))
        assertEquals(0, SleepClassifier.split(samples).asleep.size)
    }

    @Test
    fun knownSleepSessionsCountEvenWhenMoving() {
        val samples = listOf(sample(0, 5.0), sample(60, 2.0), sample(90, 0.3), sample(200, 5.0))
        val split = SleepClassifier.split(samples, listOf(50 * minute..100 * minute))
        assertEquals(2, split.asleep.size)
        assertEquals(2, split.awake.size)
    }

    @Test
    fun turningOverDoesNotBreakTheNight() {
        // Slight drift all night, with two single moments of movement.
        val night = (0..39).map { sample(it * 10, if (it == 12 || it == 27) 60.0 else 0.4 + (it % 4) * 0.25) }
        val split = SleepClassifier.split(listOf(sample(-20, 30.0), sample(-10, 25.0)) + night + listOf(sample(410, 40.0), sample(420, 50.0)))
        assertEquals(40, split.asleep.size)
        assertEquals(4, split.awake.size)
    }

    @Test
    fun sittingQuietlyForHalfAnHourIsNotSleep() {
        val samples = listOf(sample(0, 30.0)) + (1..7).map { sample(it * 5, 1.0) } + listOf(sample(40, 30.0), sample(45, 20.0))
        assertEquals(0, SleepClassifier.split(samples).asleep.size)
    }

    @Test
    fun lyingAwakeWithSmallMovementsIsNotSleep() {
        // Still enough to look like rest, but never as still as sleep.
        val samples = (0..20).map { sample(it * 5, 2.5) }
        assertEquals(0, SleepClassifier.split(samples).asleep.size)
    }
}
