package app.tremolo.shared

import app.tremolo.shared.protocol.CaptureSchedule
import app.tremolo.shared.protocol.TrialConfig
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureScheduleTest {
    private val zone = ZoneOffset.UTC

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 11, day, hour, minute).toInstant(zone).toEpochMilli()

    private val config = TrialConfig(trialId = "t", startMillis = at(1, 0), endMillis = at(8, 0))

    @Test
    fun `late checks do not reduce the number of checks`() {
        // The watch runs every check late by 75% of the wait it was given, as inexact alarms do.
        val allDay = config.copy(wakeStartMinute = 0, wakeEndMinute = 0)
        val random = Random(3)
        val start = at(2, 0)
        var now = start
        var planned = 0L
        var checks = 0
        while (now < at(3, 0)) {
            val next = CaptureSchedule.next(allDay, now, random, zone, planned)!!
            planned = next
            now = next + (next - now) * 3 / 4
            checks++
        }
        // 24 hours at one check per 15 minutes on average.
        assertTrue("made $checks checks", checks in 80..106)
    }

    @Test
    fun `after a long pause the missed checks are not rushed through`() {
        val now = at(2, 12)
        val next = CaptureSchedule.next(config, now, Random(1), zone, plannedMillis = now - 3 * 3_600_000L)!!
        assertTrue(next - now >= config.minIntervalSeconds * 1000L)
    }

    @Test
    fun `two checks are never closer than the shortest gap`() {
        val now = at(2, 12)
        repeat(50) { seed ->
            val planned = now - config.maxIntervalSeconds * 1000L
            val next = CaptureSchedule.next(config, now, Random(seed), zone, planned)!!
            assertTrue(next - now >= config.minIntervalSeconds * 1000L)
        }
    }

    @Test
    fun `daytime gap stays inside the configured range`() {
        val random = Random(7)
        val now = at(2, 12)
        repeat(200) {
            val gapSeconds = (CaptureSchedule.next(config, now, random, zone)!! - now) / 1000
            assertTrue(gapSeconds in config.minIntervalSeconds..config.maxIntervalSeconds)
        }
    }

    @Test
    fun `captures never land in sleeping hours`() {
        val random = Random(7)
        var now = at(2, 20)
        repeat(500) {
            now = CaptureSchedule.next(config, now, random, zone) ?: return
            assertTrue(CaptureSchedule.isAwake(config, now, zone))
        }
    }

    @Test
    fun `late evening rolls over to the next morning`() {
        val next = CaptureSchedule.next(config, at(2, 22, 59), Random(7), zone)!!
        assertTrue(next >= at(3, 7) && next < at(3, 7, 23))
    }

    @Test
    fun `nothing is scheduled past the end of the trial`() {
        assertNull(CaptureSchedule.next(config, at(7, 23, 59), Random(7), zone))
        assertNull(CaptureSchedule.next(config, at(9, 12), Random(7), zone))
    }

    @Test
    fun `wake range may cross midnight`() {
        val night = config.copy(wakeStartMinute = 20 * 60, wakeEndMinute = 4 * 60)
        assertTrue(CaptureSchedule.isAwake(night, at(2, 23), zone))
        assertTrue(CaptureSchedule.isAwake(night, at(2, 3), zone))
        assertTrue(!CaptureSchedule.isAwake(night, at(2, 12), zone))
    }
}
