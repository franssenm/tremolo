package app.tremolo.shared

import app.tremolo.shared.dsp.MovementContext
import app.tremolo.shared.dsp.WindowFeatures
import app.tremolo.shared.stats.Fairness
import app.tremolo.shared.stats.Sample
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FairnessTest {
    private val zone = ZoneOffset.UTC
    private val hourMillis = 3_600_000L

    private fun sample(day: Int, hour: Int, fraction: Double, context: MovementContext = MovementContext.REST) = Sample(
        day * 24 * hourMillis + hour * hourMillis,
        WindowFeatures(
            durationSeconds = 20.0,
            dominantFreqHz = 5.0,
            tremorRmsDegPerSec = 3.0,
            peakRatio = 0.8,
            lowFreqRmsDegPerSec = 1.0,
            accelTremorRmsMg = 1.0,
            tremorFraction = fraction,
            tremorRmsWhenPresent = 3.0,
            context = context,
            tremorDetected = fraction >= 0.3,
        ),
    )

    /** [perHour] measurements in each of [hours] on each of [days]; tremor depends only on the hour. */
    private fun trial(days: IntRange, hours: Map<Int, Int>, tremorAt: (Int) -> Double) = days.flatMap { day ->
        hours.flatMap { (hour, count) -> List(count) { sample(day, hour, tremorAt(hour)) } }
    }

    @Test
    fun `alike circumstances raise no concern`() {
        val hours = mapOf(9 to 5, 14 to 5, 20 to 5)
        val check = Fairness.check(
            trial(0..3, hours) { 0.5 }, trial(10..13, hours) { 0.2 }, "Program A", "Program B", zone,
        )
        assertTrue(check.concerns.toString(), check.fair)
        assertNull(check.matchedNote)
    }

    @Test
    fun `a difference that comes only from the time of day is called out`() {
        // Tremor is 60% in the evening and 10% in the morning with both settings.
        val tremor = { hour: Int -> if (hour >= 18) 0.6 else 0.1 }
        val a = trial(0..3, mapOf(9 to 3, 20 to 12), tremor)
        val b = trial(10..13, mapOf(9 to 12, 20 to 3), tremor)
        val check = Fairness.check(a, b, "Program A", "Program B", zone)
        assertEquals(check.concerns.toString(), 1, check.concerns.size)
        assertTrue(check.concerns[0], "Program A" in check.concerns[0] && "80%" in check.concerns[0])
        val note = check.matchedNote!!
        assertTrue(note, "shrinks from 30 to 0" in note)
    }

    @Test
    fun `a real difference survives matching`() {
        val a = trial(0..3, mapOf(9 to 3, 20 to 12)) { 0.6 }
        val b = trial(10..13, mapOf(9 to 12, 20 to 3)) { 0.2 }
        val note = Fairness.check(a, b, "Program A", "Program B", zone).matchedNote!!
        assertTrue(note, "stays about the same (40 percentage points)" in note)
    }

    @Test
    fun `too few days and unequal activity are reported`() {
        val a = List(40) { sample(0, 10, 0.3, MovementContext.REST) }
        val b = (0..3).flatMap { day -> List(10) { sample(day, 10, 0.3, MovementContext.ACTIVE) } }
        val concerns = Fairness.check(a, b, "Program A", "Program B", zone).concerns
        assertEquals(concerns.toString(), 2, concerns.size)
        assertTrue(concerns.any { "at rest in 100%" in it })
        assertTrue(concerns.any { "Program A was measured on only 1 day" in it })
    }
}
