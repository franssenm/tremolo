package app.tremolo.phone.report

import app.tremolo.phone.TremoloApp
import app.tremolo.phone.data.DailyRating
import app.tremolo.phone.data.DbsSetting
import app.tremolo.phone.data.EventLogEntry
import app.tremolo.phone.data.PhoneTest
import app.tremolo.phone.data.SampleWindow
import app.tremolo.phone.data.Trial
import app.tremolo.shared.dsp.MovementContext
import app.tremolo.shared.protocol.SkipReason
import app.tremolo.shared.protocol.WindowKind
import app.tremolo.shared.stats.Estimate
import app.tremolo.shared.stats.GroupSummary
import app.tremolo.phone.SleepImport
import app.tremolo.shared.stats.Sample
import app.tremolo.shared.stats.SleepClassifier
import app.tremolo.shared.stats.TrialStatistics
import app.tremolo.shared.stats.TrialSummary
import app.tremolo.shared.stats.DayOwner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How close (degrees) the wrist must be to the calibrated posture to count as "in it". */
private const val POSTURE_MATCH_DEG = 25.0

/** Share (0..100) of at-rest checks where the wrist was held in the calibrated dystonic posture. */
fun dystonicPosturePct(samples: List<Sample>, reference: DoubleArray?): Double? {
    if (reference == null) return null
    val atRest = samples.filter { it.features.context == MovementContext.REST }
        .map { doubleArrayOf(it.features.gravityX, it.features.gravityY, it.features.gravityZ) }
        .filter { v -> v.any { it != 0.0 } }
    if (atRest.isEmpty()) return null
    return 100.0 * atRest.count { TrialStatistics.angleDeg(it, reference) <= POSTURE_MATCH_DEG } / atRest.size
}

/**
 * Share (0..100) of the time at rest with fast movement that has no steady rhythm: jerks and
 * irregular shaking, which the tremor measure deliberately ignores because it looks for one
 * clear frequency. Dystonic movement is often of this kind. Experimental: fidgeting with
 * the hand at rest counts too.
 */
fun irregularRestPct(samples: List<Sample>): Double? {
    val atRest = samples.filter {
        it.features.context == MovementContext.REST && it.features.segmentClasses.isNotEmpty()
    }
    if (atRest.size < 5) return null
    val seconds = atRest.sumOf { it.features.segmentClasses.length }
    val irregular = atRest.sumOf { s -> s.features.segmentClasses.count { it == 'B' || it == 'I' } }
    return 100.0 * irregular / seconds
}

/** Everything shown in a trial report. */
class TrialReport(
    val trial: Trial,
    val setting: DbsSetting?,
    /** Name to show: the setting's name, or its code letter while a plan hides names. */
    val label: String,
    /** True while the trial belongs to an unfinished plan; results must not be shown. */
    val locked: Boolean,
    /** The hidden captures that could be analysed, taken while awake. */
    val samples: List<Sample>,
    /** Captures taken during sleep; kept out of every result and reported on their own. */
    val asleep: List<Sample> = emptyList(),
    val summary: TrialSummary,
    val hiddenWindows: List<SampleWindow>,
    val unanalysedCount: Int,
    val skips: Map<SkipReason, Int>,
    val tests: List<SampleWindow>,
    val phoneTests: List<PhoneTest>,
    /**
     * The daily ratings that count for this trial. The day of a switch is touched by two
     * trials; its rating goes only to the one that covered most of the daytime.
     */
    val ratings: List<DailyRating>,
    val events: List<EventLogEntry>,
    val dystonicPosturePct: Double?,
    /** Every day in the trial that has a rating, also those counted for a neighbouring trial. */
    val ratedDays: Set<LocalDate> = emptySet(),
    /** The kind of day (DayKind) of each date the user gave one for. */
    val dayKinds: Map<LocalDate, String> = emptyMap(),
) {
    val enoughData: Boolean
        get() = summary.usableCount >= TrialStatistics.MIN_SAMPLES_TO_COMPARE

    fun headline(): List<Pair<String, String>> =
        resultRows(summary, dystonicPosturePct, irregularRestPct(samples))

    fun coverage(): List<Pair<String, String>> = listOf(
        "Hidden checks used" to summary.usableCount.toString(),
        "Left out: asleep" to asleep.size.toString(),
        "Left out: walking" to (summary.byContext[MovementContext.WALKING]?.count ?: 0).toString(),
        "Left out: other large movement" to
            (summary.byContext[MovementContext.GROSS]?.count ?: 0).toString(),
        "Skipped: watch off the wrist" to (skips[SkipReason.OFF_WRIST] ?: 0).toString(),
        "Skipped: watch charging" to (skips[SkipReason.CHARGING] ?: 0).toString(),
        "Could not be analysed" to unanalysedCount.toString(),
    )
}

/** How far a trial's measurements can be trusted, and why. */
class DataQuality(
    /** "Good", "Fair" or "Thin". */
    val verdict: String,
    val rows: List<Pair<String, String>>,
    /** Plain-language reasons the verdict is not "Good"; empty when it is. */
    val concerns: List<String>,
)

/** How many hidden checks the watch was meant to have made in [trial] by [untilMillis]. */
fun plannedChecks(trial: Trial, untilMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Double {
    val end = minOf(trial.endMillis, untilMillis)
    fun awake(millis: Long): Boolean {
        if (trial.wakeStartMinute == trial.wakeEndMinute) return true
        val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        val minute = time.hour * 60 + time.minute
        return if (trial.wakeStartMinute < trial.wakeEndMinute) {
            minute in trial.wakeStartMinute until trial.wakeEndMinute
        } else {
            minute >= trial.wakeStartMinute || minute < trial.wakeEndMinute
        }
    }
    val step = 5 * 60_000L
    var measuringMillis = 0L
    var t = trial.startMillis
    while (t < end) {
        if (awake(t)) measuringMillis += step
        t += step
    }
    val measuringHours = measuringMillis / 3_600_000.0
    val meanGapSeconds = (trial.minIntervalSeconds + trial.maxIntervalSeconds) / 2.0
    return measuringHours * 3600 / meanGapSeconds
}

fun dataQuality(report: TrialReport, zone: ZoneId = ZoneId.systemDefault()): DataQuality {
    val trial = report.trial
    val planned = plannedChecks(trial, System.currentTimeMillis(), zone)
    val measuringHours = planned * (trial.minIntervalSeconds + trial.maxIntervalSeconds) / 2.0 / 3600
    val skipped = report.skips.values.sum()
    val attempts = report.hiddenWindows.size + skipped
    val madePct = if (planned < 1) 100.0 else 100.0 * attempts / planned
    val usable = report.samples.filter(TrialStatistics::isUsable)
    val motionless = usable.count { SleepClassifier.isMotionless(it.features) }
    val motionlessPct = if (usable.isEmpty()) 0.0 else 100.0 * motionless / usable.size
    val perDay = usable.groupingBy { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }.eachCount()
    val hoursSeen = usable.map { Instant.ofEpochMilli(it.timeMillis).atZone(zone).hour }.toSet().size

    val concerns = buildList {
        if (usable.size < TrialStatistics.MIN_SAMPLES_TO_COMPARE) {
            add("Only ${usable.size} usable measurements; at least ${TrialStatistics.MIN_SAMPLES_TO_COMPARE} are needed to compare settings.")
        }
        if (planned >= 8 && madePct < 60) {
            add(
                "The watch made only ${Fmt.pct(madePct)} of the planned checks. Watches delay " +
                    "background work to save power, and a low battery or a watch out of reach of " +
                    "the phone adds to it. A longer trial makes up for this.",
            )
        }
        if (attempts >= 8 && skipped > 0.3 * attempts) {
            add("${Fmt.pct(100.0 * skipped / attempts)} of the checks were skipped because the watch was off the wrist or charging.")
        }
        if (usable.size >= 8 && motionlessPct > 40) {
            add(
                "${Fmt.pct(motionlessPct)} of the waking measurements found the arm completely " +
                    "motionless. Either the arm was resting very often, or the watch was lying " +
                    "somewhere instead of being worn; these count as \"no tremor\".",
            )
        }
        if (perDay.size < 2) {
            add("Covers less than two days, so day-to-day variation is not captured.")
        }
    }
    val verdict = when {
        concerns.isEmpty() -> "Good"
        concerns.size == 1 && usable.size >= TrialStatistics.MIN_SAMPLES_TO_COMPARE -> "Fair"
        else -> "Thin"
    }
    val rows = buildList {
        add("Hours the watch was set to measure" to Fmt.num(measuringHours, 0))
        add("Checks planned / made" to "${Fmt.num(planned, 0)} / $attempts (${Fmt.pct(madePct)})")
        if (measuringHours >= 1) add("Checks per hour" to Fmt.num(attempts / measuringHours))
        add("Days with measurements" to perDay.size.toString())
        if (perDay.isNotEmpty()) add("Fewest usable on one day" to perDay.values.min().toString())
        add("Hours of the day covered" to "$hoursSeen of 24")
        add("Awake with the arm completely motionless" to "$motionless (${Fmt.pct(motionlessPct)})")
        if (report.asleep.isNotEmpty()) {
            add(
                "Measured while asleep (left out)" to "${report.asleep.size}, tremor " +
                    Fmt.pct(report.asleep.map { it.features.tremorFraction * 100 }.average()) + " of the time",
            )
        }
    }
    return DataQuality(verdict, rows, concerns)
}

/** The headline result rows shared by trial reports, pooled results and the timeline. */
fun resultRows(
    summary: TrialSummary,
    posturePct: Double?,
    irregularPct: Double? = null,
): List<Pair<String, String>> = buildList {
    add("Time with tremor" to (summary.tremorTimePct?.let(Fmt::estimatePct) ?: "–"))
    add("Checks that found tremor" to (summary.windowsWithTremorPct?.let(Fmt::pct) ?: "–"))
    add("Typical tremor strength" to (summary.medianAmplitude?.let(Fmt::estimateAmplitude) ?: "–"))
    add("Strongest 10% of checks above" to (summary.p90Amplitude?.let { "${Fmt.num(it)} °/s" } ?: "–"))
    add("Tremor frequency" to (summary.medianTremorFreqHz?.let { "${Fmt.num(it)} Hz" } ?: "–"))
    posturePct?.let { add("At rest in dystonic posture (experimental)" to Fmt.pct(it)) }
    irregularPct?.let { add("Irregular movement at rest (experimental)" to Fmt.pct(it)) }
    summary.walking?.let {
        add("Walking pace (${it.count} checks)" to "${Fmt.num(it.medianCadenceSpm, 0)} steps/min")
        add("Arm swing while walking" to "${Fmt.num(it.medianArmSwingDegPerSec, 0)} °/s")
    }
}

suspend fun loadReport(
    app: TremoloApp,
    trialId: String,
    zone: ZoneId = ZoneId.systemDefault(),
): TrialReport? = withContext(Dispatchers.Default) {
    val dao = app.dao
    val trial = dao.trial(trialId) ?: return@withContext null
    val plan = trial.planId?.let { dao.plan(it) }
    val locked = plan != null && !plan.isFinished
    val setting = dao.setting(trial.settingId)
    val windows = dao.windowsFor(trialId)
    val hidden = windows.filter { it.kind == WindowKind.PASSIVE }
    val analysed = hidden.mapNotNull { w -> w.features?.let { Sample(w.startMillis, it) } }
    val split = SleepClassifier.split(analysed, SleepImport.sessions(app, trial.startMillis, trial.endMillis))
    val samples = split.awake
    val firstDay = Instant.ofEpochMilli(trial.startMillis).atZone(zone).toLocalDate().toEpochDay()
    val lastDay = Instant.ofEpochMilli(trial.endMillis).atZone(zone).toLocalDate().toEpochDay()
    val allRatings = dao.ratingsBetween(firstDay, lastDay)
    val spans = dao.trialList().map { DayOwner.Span(it.id, it.startMillis, it.endMillis) }
    TrialReport(
        trial = trial,
        setting = setting,
        label = when {
            locked && plan?.blinded == true -> "Setting ${trial.blindCode ?: "?"}"
            else -> setting?.label ?: "Unknown setting"
        },
        locked = locked,
        samples = samples,
        asleep = split.asleep,
        summary = TrialStatistics.summarize(samples, zone),
        hiddenWindows = hidden,
        unanalysedCount = hidden.size - analysed.size,
        skips = dao.skipsFor(trialId).groupingBy { it.reason }.eachCount(),
        tests = windows.filter {
            it.kind == WindowKind.TEST_REST || it.kind == WindowKind.TEST_POSTURAL
        },
        phoneTests = dao.phoneTestsBetween(trial.startMillis, trial.endMillis),
        ratings = allRatings.filter { DayOwner.owner(LocalDate.ofEpochDay(it.epochDay), spans, zone) == trial.id },
        ratedDays = allRatings.map { LocalDate.ofEpochDay(it.epochDay) }.toSet(),
        events = dao.eventsBetween(trial.startMillis, trial.endMillis),
        dystonicPosturePct = dystonicPosturePct(samples, app.prefs.dystonicPosture),
        dayKinds = dao.dayKindsBetween(firstDay, lastDay).associate { LocalDate.ofEpochDay(it.epochDay) to it.kind },
    )
}

/** All finished, unlocked trials of one DBS setting pooled together. */
class SettingResult(
    /** Unique per setting and wrist; use this, not [settingId], to tell results apart. */
    val key: Long,
    val settingId: Long,
    val label: String,
    val details: String,
    val trialCount: Int,
    val samples: List<Sample>,
    val summary: TrialSummary,
    val ratings: List<DailyRating>,
    val dystonicPosturePct: Double?,
    /** The individual trials behind the pooled numbers. */
    val reports: List<TrialReport> = emptyList(),
) {
    val dayKinds: Map<LocalDate, String> get() = reports.flatMap { it.dayKinds.entries }.associate { it.key to it.value }

    val enoughData: Boolean
        get() = summary.usableCount >= TrialStatistics.MIN_SAMPLES_TO_COMPARE

    fun ratingAverages(): String? {
        if (ratings.isEmpty()) return null
        fun avg(pick: (DailyRating) -> Int) = Fmt.num(ratings.map(pick).average())
        return "tremor ${avg { it.tremor }}, side effects ${avg { it.sideEffects }}, " +
            "mood ${avg { it.mood }} (${ratings.size} days)"
    }
}

/**
 * Every finished, unlocked trial on its own, newest first, in the same shape as a pooled
 * setting so the two can be compared the same way. Trials without usable measurements are
 * left out.
 */
suspend fun loadTrialResults(app: TremoloApp): List<SettingResult> = withContext(Dispatchers.Default) {
    val zone = ZoneId.systemDefault()
    app.dao.trialList()
        .filter { !it.isRunning() }
        .mapNotNull { loadReport(app, it.id, zone) }
        .filter { !it.locked && it.summary.usableCount > 0 }
        .sortedByDescending { it.trial.startMillis }
        .map { report ->
            SettingResult(
                key = report.trial.startMillis,
                settingId = report.trial.settingId,
                label = "${report.label}, ${Fmt.span(report.trial.startMillis, report.trial.endMillis)}",
                details = report.setting?.details().orEmpty(),
                trialCount = 1,
                samples = report.samples,
                summary = report.summary,
                ratings = report.ratings,
                dystonicPosturePct = report.dystonicPosturePct,
                reports = listOf(report),
            )
        }
}

/** Pools trials per setting, least tremor first. Running and plan-locked trials are left out. */
suspend fun loadSettingResults(app: TremoloApp): List<SettingResult> = withContext(Dispatchers.Default) {
    val zone = ZoneId.systemDefault()
    app.dao.trialList()
        .filter { !it.isRunning() }
        .mapNotNull { loadReport(app, it.id, zone) }
        .filter { !it.locked }
        // A tremor differs between arms, so trials with the watch on different wrists are
        // never pooled. Older trials without a recorded wrist count as the current one.
        .groupBy { it.trial.settingId to it.trial.wrist.ifEmpty { app.prefs.wrist } }
        .let { groups ->
            val severalWrists = groups.keys.map { it.second }.distinct().size > 1
            groups.map { (key, reports) -> Triple(key, reports, severalWrists) }
        }
        .map { (key, reports, severalWrists) ->
            val (settingId, wrist) = key
            val samples = reports.flatMap { it.samples }
            SettingResult(
                key = settingId * 2 + if (wrist == "right") 1 else 0,
                settingId = settingId,
                label = reports.first().label + if (severalWrists) " ($wrist wrist)" else "",
                details = reports.first().setting?.details().orEmpty(),
                trialCount = reports.size,
                samples = samples,
                summary = TrialStatistics.summarize(samples, zone),
                ratings = reports.flatMap { it.ratings }.distinctBy { it.epochDay },
                dystonicPosturePct = dystonicPosturePct(samples, app.prefs.dystonicPosture),
                reports = reports,
            )
        }
        .sortedBy { it.summary.tremorTimePct?.value ?: Double.MAX_VALUE }
}

/** One bar of a chart. A null [value] means nothing was measured, which is not zero. */
class ChartBar(
    /** Short text under the bar. */
    val label: String,
    val value: Double?,
    /** How many measurements the value rests on, when that is known. */
    val count: Int? = null,
    /** Longer name used when the bar is read out, e.g. "14:00 to 15:00". */
    val detail: String = label,
) {
    /** Too few measurements to lean on; drawn paler. */
    val isThin: Boolean get() = value != null && count != null && count < THIN_COUNT

    companion object {
        const val THIN_COUNT = 3
    }
}

/** The next clean number at or above [value], for the top of a chart's scale. */
fun niceCeiling(value: Double): Double {
    if (value <= 0) return 1.0
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(value)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= value * 0.999 }
}

/** Bars for a by-hour chart of time with tremor. */
fun hourBars(byHour: Map<Int, GroupSummary>): List<ChartBar> = (0..23).map { hour ->
    val group = byHour[hour]
    ChartBar(hour.toString(), group?.tremorTimePct, group?.count, "%02d:00 to %02d:00".format(hour, (hour + 1) % 24))
}

/** Bars for a by-day chart; [pick] chooses the measure. */
fun dayBars(
    days: List<LocalDate>,
    byDay: Map<LocalDate, GroupSummary>,
    pick: (GroupSummary) -> Double,
): List<ChartBar> = days.map { day ->
    val group = byDay[day]
    ChartBar("${day.dayOfMonth}/${day.monthValue}", group?.let(pick), group?.count, Fmt.date(day.toEpochDay()))
}

/** Tremor shortly after a kind of logged event, against all other times. */
class EventEffect(
    val type: String,
    val eventCount: Int,
    val afterCount: Int,
    val otherCount: Int,
    val afterPct: Double,
    val otherPct: Double,
)

class DoseBin(val label: String, val count: Int, val tremorPct: Double)

/** Tremor on days after shorter nights against days after longer nights. */
class SleepEffect(
    val medianHours: Double,
    val shortNights: Int,
    val longNights: Int,
    val shortPct: Double,
    val longPct: Double,
)

/** Simple, observational patterns between the event log and the measurements. */
object Insights {
    private const val HOUR = 3_600_000L
    private const val MIN_EVENTS = 3
    private const val MIN_SAMPLES = 8

    /** For each event type: tremor in the [hours] after it versus the rest of the time. */
    fun eventEffects(samples: List<Sample>, events: List<EventLogEntry>, hours: Int = 2): List<EventEffect> {
        val usable = samples.filter(TrialStatistics::isUsable)
        return events.groupBy { it.type }
            .filter { it.value.size >= MIN_EVENTS }
            .mapNotNull { (type, list) ->
                val times = list.map { it.timeMillis }
                val (after, other) = usable.partition { s ->
                    times.any { t -> s.timeMillis - t in 1..hours * HOUR }
                }
                if (after.size < MIN_SAMPLES || other.size < MIN_SAMPLES) return@mapNotNull null
                EventEffect(
                    type, list.size, after.size, other.size,
                    after.map { it.features.tremorFraction * 100 }.average(),
                    other.map { it.features.tremorFraction * 100 }.average(),
                )
            }
            .sortedByDescending { kotlin.math.abs(it.afterPct - it.otherPct) }
    }

    /**
     * Splits days by whether the night before was shorter or longer than the person's own
     * typical night. [hoursByDay] is keyed by the day the sleep ended on.
     */
    fun sleepEffect(samples: List<Sample>, hoursByDay: Map<LocalDate, Double>, zone: ZoneId): SleepEffect? {
        if (hoursByDay.size < 4) return null
        val median = hoursByDay.values.sorted().let { it[it.size / 2] }
        val short = ArrayList<Double>()
        val long = ArrayList<Double>()
        for (s in samples.filter(TrialStatistics::isUsable)) {
            val hours = hoursByDay[Instant.ofEpochMilli(s.timeMillis).atZone(zone).toLocalDate()] ?: continue
            (if (hours < median) short else long) += s.features.tremorFraction * 100
        }
        if (short.size < MIN_SAMPLES || long.size < MIN_SAMPLES) return null
        return SleepEffect(
            median, hoursByDay.values.count { it < median }, hoursByDay.values.count { it >= median },
            short.average(), long.average(),
        )
    }

    /** Tremor by time since the last logged medication dose (up to 12 hours). */
    fun sinceDose(samples: List<Sample>, events: List<EventLogEntry>): List<DoseBin> {
        val doses = events.filter { it.type == "Medication" || it.type == "Medication taken" }
            .map { it.timeMillis }.sorted()
        if (doses.size < MIN_EVENTS) return emptyList()
        val edges = listOf(0, 1, 2, 3, 4, 6, 12)
        val bins = List(edges.size - 1) { ArrayList<Double>() }
        for (s in samples.filter(TrialStatistics::isUsable)) {
            val last = doses.lastOrNull { it < s.timeMillis } ?: continue
            val hoursSince = (s.timeMillis - last).toDouble() / HOUR
            val index = (0 until edges.size - 1).firstOrNull { hoursSince >= edges[it] && hoursSince < edges[it + 1] }
                ?: continue
            bins[index] += s.features.tremorFraction * 100
        }
        val result = bins.mapIndexedNotNull { i, values ->
            if (values.size < 3) null else DoseBin("${edges[i]}–${edges[i + 1]} h", values.size, values.average())
        }
        return if (result.size >= 2) result else emptyList()
    }
}

object Fmt {
    private val dateTimeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val isoFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun dateTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateTimeFormat)

    fun iso(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(isoFormat)

    /**
     * The days a trial ran over, such as "25 Nov – 26 Nov", so a trial that began in the
     * evening is not named after that evening alone. Within one day the hours are given.
     */
    fun span(startMillis: Long, endMillis: Long): String {
        val zone = ZoneId.systemDefault()
        val from = Instant.ofEpochMilli(startMillis).atZone(zone)
        val to = Instant.ofEpochMilli(endMillis).atZone(zone)
        val day = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        return if (from.toLocalDate() == to.toLocalDate()) {
            "${from.format(day)}, ${from.format(time)}–${to.format(time)}"
        } else {
            "${from.format(day)} – ${to.format(day)}"
        }
    }

    fun date(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFormat)

    fun clock(minute: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", (minute / 60) % 24, minute % 60)

    fun num(value: Double, decimals: Int = 1): String =
        String.format(Locale.getDefault(), "%.${decimals}f", value)

    fun pct(value: Double): String = "${num(value, 0)}%"

    fun estimatePct(e: Estimate): String = "${pct(e.value)} (${num(e.low, 0)}–${num(e.high, 0)})"

    fun estimateAmplitude(e: Estimate): String =
        "${num(e.value)} °/s (${num(e.low)}–${num(e.high)})"

    fun duration(millis: Long): String {
        val minutes = (millis / 60_000).coerceAtLeast(0)
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        return when {
            days > 0 -> "$days d $hours h"
            hours > 0 -> "$hours h ${minutes % 60} min"
            else -> "$minutes min"
        }
    }

    fun testName(kind: WindowKind): String = when (kind) {
        WindowKind.TEST_REST -> "Rest test"
        WindowKind.TEST_POSTURAL -> "Posture test"
        WindowKind.PASSIVE -> "Hidden check"
        WindowKind.DEBUG -> "Debug capture"
    }

    fun context(context: MovementContext): String = when (context) {
        MovementContext.REST -> "Arm at rest"
        MovementContext.ACTIVE -> "Arm in use"
        MovementContext.GROSS -> "Large movement"
        MovementContext.WALKING -> "Walking"
    }
}
