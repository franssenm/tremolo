package app.tremolo.shared.stats

/** The watch's charge at one moment, with the measuring pace in force then. */
class BatteryReading(val timeMillis: Long, val percent: Int, val plannedGapSeconds: Int)

/** How fast the watch battery ran down over [hours] of use off the charger. */
class BatteryUse(val percentPerHour: Double, val hours: Double) {
    /** Hours a full charge would last at this rate. */
    val hoursPerCharge: Double get() = if (percentPerHour <= 0) Double.POSITIVE_INFINITY else 100 / percentPerHour
}

/**
 * Works out battery use from the charge level the watch reports with each recording.
 * This is the whole watch (screen, other apps, Tremolo), which is what the wearer cares
 * about; comparing two measuring paces shows what the extra checks cost.
 */
object BatteryUsage {
    /** Readings further apart than this are not joined up: too much is unknown in between. */
    private const val MAX_GAP_MILLIS = 90 * 60_000L

    /** Less than this is too little to give a rate. */
    const val MIN_HOURS = 3.0

    /**
     * The rate over all stretches where the charge did not rise (so the watch was not on
     * the charger); null when those add up to less than [MIN_HOURS].
     */
    fun of(readings: List<BatteryReading>): BatteryUse? {
        var dropped = 0
        var millis = 0L
        readings.sortedBy { it.timeMillis }.zipWithNext { a, b ->
            val gap = b.timeMillis - a.timeMillis
            if (gap in 1..MAX_GAP_MILLIS && b.percent <= a.percent) {
                dropped += a.percent - b.percent
                millis += gap
            }
        }
        val hours = millis / 3_600_000.0
        return if (hours < MIN_HOURS) null else BatteryUse(dropped / hours, hours)
    }

    /** The rate for each measuring pace, keyed by its planned gap in seconds. */
    fun byPace(readings: List<BatteryReading>): Map<Int, BatteryUse> {
        val sorted = readings.sortedBy { it.timeMillis }
        // A stretch belongs to a pace only while both ends were recorded at that pace.
        val paces = sorted.map { it.plannedGapSeconds }.filter { it > 0 }.distinct()
        return buildMap {
            for (pace in paces) {
                var dropped = 0
                var millis = 0L
                sorted.zipWithNext { a, b ->
                    val gap = b.timeMillis - a.timeMillis
                    if (a.plannedGapSeconds == pace && b.plannedGapSeconds == pace &&
                        gap in 1..MAX_GAP_MILLIS && b.percent <= a.percent
                    ) {
                        dropped += a.percent - b.percent
                        millis += gap
                    }
                }
                val hours = millis / 3_600_000.0
                if (hours >= MIN_HOURS) put(pace, BatteryUse(dropped / hours, hours))
            }
        }
    }
}
