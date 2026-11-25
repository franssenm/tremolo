package app.tremolo.shared.stats

import java.time.LocalDate
import java.time.ZoneId

/**
 * Decides which trial a day's rating belongs to. A rating describes a whole day, but trials
 * start and end at any hour, so the day of a switch is touched by two trials. The rating
 * goes to the one that covered most of the daytime, and to no other.
 */
object DayOwner {
    /** The hours a rating of "today" is mostly about. */
    const val DAY_START_HOUR = 8
    const val DAY_END_HOUR = 22

    class Span(val id: String, val startMillis: Long, val endMillis: Long)

    /** Milliseconds of [day]'s daytime that [span] covers. */
    fun daytimeCovered(day: LocalDate, span: Span, zone: ZoneId): Long {
        val from = day.atTime(DAY_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        val to = day.atTime(DAY_END_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        return (minOf(to, span.endMillis) - maxOf(from, span.startMillis)).coerceAtLeast(0)
    }

    /**
     * The id of the trial that covered most of [day]'s daytime, or null when none touched it.
     * When two cover it equally, the earlier one has it.
     */
    fun owner(day: LocalDate, spans: List<Span>, zone: ZoneId): String? =
        spans.sortedBy { it.startMillis }
            .map { it to daytimeCovered(day, it, zone) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first?.id
}
