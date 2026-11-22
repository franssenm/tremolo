package app.tremolo.shared

import app.tremolo.shared.stats.BatteryReading
import app.tremolo.shared.stats.BatteryUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryUseTest {
    private fun reading(minute: Int, percent: Int, pace: Int = 450) =
        BatteryReading(minute * 60_000L, percent, pace)

    @Test
    fun `steady drain gives percent per hour`() {
        // 2% an hour for 6 hours.
        val use = BatteryUsage.of((0..36).map { reading(it * 10, 90 - it / 3) })!!
        assertEquals(2.0, use.percentPerHour, 0.01)
        assertEquals(6.0, use.hours, 0.01)
        assertEquals(50.0, use.hoursPerCharge, 0.5)
    }

    @Test
    fun `time on the charger and long silences are left out`() {
        val draining = (0..24).map { reading(it * 10, 80 - it / 2) }          // 4 h, 3% an hour
        val charging = (1..6).map { reading(240 + it * 10, 68 + it * 5) }     // charge rising
        val afterSilence = listOf(reading(900, 40))                           // 10 h later
        val use = BatteryUsage.of(draining + charging + afterSilence)!!
        assertEquals(3.0, use.percentPerHour, 0.01)
        assertEquals(4.0, use.hours, 0.01)
    }

    @Test
    fun `too little data gives no rate`() {
        assertNull(BatteryUsage.of((0..6).map { reading(it * 10, 90 - it) }))
        assertNull(BatteryUsage.of(emptyList()))
    }

    @Test
    fun `each pace gets its own rate`() {
        val light = (0..24).map { reading(it * 10, 100 - it / 6, pace = 900) }         // 1% an hour
        val detailed = (0..24).map { reading(300 + it * 10, 90 - it / 2, pace = 240) } // 3% an hour
        val use = BatteryUsage.byPace(light + detailed)
        assertEquals(1.0, use.getValue(900).percentPerHour, 0.01)
        assertEquals(3.0, use.getValue(240).percentPerHour, 0.01)
    }
}
