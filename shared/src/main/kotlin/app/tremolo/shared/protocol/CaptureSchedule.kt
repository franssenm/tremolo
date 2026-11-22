package app.tremolo.shared.protocol

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** Decides when the watch takes its next hidden capture. */
object CaptureSchedule {

    /**
     * The next capture time after [nowMillis]: a random gap inside the configured range,
     * pushed to a random point shortly after the next wake-up time if it would land in the
     * sleeping hours. Returns null when the trial would be over by then.
     *
     * Watches often run a check later than asked, to save power. [plannedMillis] is when the
     * check that just ran was meant to happen; the gap is counted from there, not from the
     * late moment it really ran, so delays do not pile up and the number of checks in a day
     * stays close to what was planned. Two checks are still never closer together than the
     * shortest configured gap: back-to-back checks would say the same thing twice and cost
     * battery. After a long pause (watch off, charging) it starts afresh
     * rather than rushing through the checks that were missed.
     */
    fun next(
        config: TrialConfig,
        nowMillis: Long,
        random: Random,
        zone: ZoneId,
        plannedMillis: Long = 0L,
    ): Long? {
        val min = config.minIntervalSeconds.coerceAtLeast(1)
        val max = config.maxIntervalSeconds.coerceAtLeast(min)
        val late = nowMillis - plannedMillis
        val from = if (plannedMillis > 0 && late in 0..max * 1000L) plannedMillis else nowMillis
        val base = maxOf(from, config.startMillis)
        var candidate = maxOf(
            base + random.nextInt(min, max + 1) * 1000L,
            nowMillis + min * 1000L,
        )
        if (!isAwake(config, candidate, zone)) {
            candidate = nextWakeStart(config, candidate, zone) + random.nextInt(max) * 1000L
        }
        return if (candidate >= config.endMillis) null else candidate
    }

    fun isAwake(config: TrialConfig, millis: Long, zone: ZoneId): Boolean {
        val start = config.wakeStartMinute
        val end = config.wakeEndMinute
        if (start == end) return true
        val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        val minute = time.hour * 60 + time.minute
        return if (start < end) minute in start until end else minute >= start || minute < end
    }

    private fun nextWakeStart(config: TrialConfig, millis: Long, zone: ZoneId): Long {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        var wake = at.toLocalDate()
            .atTime(config.wakeStartMinute / 60, config.wakeStartMinute % 60)
            .atZone(zone)
        if (!wake.isAfter(at)) wake = wake.plusDays(1)
        return wake.toInstant().toEpochMilli()
    }
}
