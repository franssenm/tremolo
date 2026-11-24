package app.tremolo.shared.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What kind of day a measurement was taken on. A day at a desk at home, a day among
 * colleagues and a day off can differ more in tremor than two stimulation settings do, so
 * the user can say which it was and results are shown per kind.
 */
object DayKind {
    const val HOME = "HOME"
    const val WORK = "WORK"
    const val OFF = "OFF"

    val ALL = listOf(HOME, WORK, OFF)

    fun title(kind: String): String = when (kind) {
        HOME -> "Working at home"
        WORK -> "Working away from home"
        OFF -> "Day off"
        else -> kind
    }

    /** Fits the sentence "… were taken <phrase>". */
    internal fun phrase(kind: String): String = when (kind) {
        HOME -> "on days working at home"
        WORK -> "on days working away from home"
        else -> "on days off"
    }

    internal fun of(sample: Sample, kinds: Map<LocalDate, String>, zone: ZoneId): String? =
        kinds[Instant.ofEpochMilli(sample.timeMillis).atZone(zone).toLocalDate()]

    /** One line of the "by kind of day" breakdown. */
    data class Group(val kind: String, val days: Int, val summary: GroupSummary)

    /** Usable measurements grouped by the kind of day they fall on; days without a kind are left out. */
    fun breakdown(samples: List<Sample>, kinds: Map<LocalDate, String>, zone: ZoneId): List<Group> {
        val usable = samples.filter(TrialStatistics::isUsable)
        return ALL.mapNotNull { kind ->
            val mine = usable.filter { of(it, kinds, zone) == kind }
            if (mine.isEmpty()) return@mapNotNull null
            val days = mine.map { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }.distinct().size
            Group(kind, days, TrialStatistics.group(mine))
        }
    }
}
