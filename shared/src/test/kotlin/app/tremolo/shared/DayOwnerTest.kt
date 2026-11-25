package app.tremolo.shared

import app.tremolo.shared.stats.DayOwner
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayOwnerTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 11, 24)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theDayOfASwitchGoesToTheTrialThatHadMostOfIt() {
        // One trial ends at 18:50 and the next begins: the day was mostly the first one's.
        val first = DayOwner.Span("first", at(day.minusDays(1), 18, 50), at(day, 18, 50))
        val second = DayOwner.Span("second", at(day, 18, 50), at(day.plusDays(1), 18, 50))
        assertEquals("first", DayOwner.owner(day, listOf(second, first), zone))
        assertEquals("second", DayOwner.owner(day.plusDays(1), listOf(second, first), zone))
    }

    @Test
    fun aMorningSwitchGivesTheDayToTheNewTrial() {
        val first = DayOwner.Span("first", at(day.minusDays(7), 9), at(day, 9))
        val second = DayOwner.Span("second", at(day, 9), at(day.plusDays(7), 9))
        assertEquals("second", DayOwner.owner(day, listOf(first, second), zone))
    }

    @Test
    fun aShortTrialOnItsOwnKeepsItsDay() {
        val only = DayOwner.Span("only", at(day, 14), at(day, 17))
        assertEquals("only", DayOwner.owner(day, listOf(only), zone))
    }

    @Test
    fun aDayNoTrialTouchedBelongsToNone() {
        val night = DayOwner.Span("night", at(day, 23), at(day.plusDays(1), 6))
        assertNull(DayOwner.owner(day, listOf(night), zone))
    }
}
