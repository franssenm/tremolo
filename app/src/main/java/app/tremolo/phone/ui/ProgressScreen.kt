package app.tremolo.phone.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import app.tremolo.phone.report.ChartBar
import app.tremolo.phone.report.Fmt
import app.tremolo.phone.report.TrialReport
import app.tremolo.phone.report.loadReport
import app.tremolo.phone.report.plannedChecks
import app.tremolo.shared.i18n.tr
import app.tremolo.shared.stats.TrialStatistics
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What one day of a trial has collected. Counts only: nothing about what was measured. */
private class DayProgress(
    val day: LocalDate,
    val checks: Int,
    val usable: Int,
    val rated: Boolean,
    val events: Int,
    val tests: Int,
)

private fun dayProgress(report: TrialReport, now: Long, zone: ZoneId): List<DayProgress> {
    fun dayOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val trial = report.trial
    val first = dayOf(trial.startMillis)
    val last = dayOf(minOf(now, trial.endMillis))
    if (last.isBefore(first)) return emptyList() // still settling in
    val checks = report.hiddenWindows.groupingBy { dayOf(it.startMillis) }.eachCount()
    val usable = report.samples.filter(TrialStatistics::isUsable).groupingBy { dayOf(it.timeMillis) }.eachCount()
    val rated = report.ratings.map { LocalDate.ofEpochDay(it.epochDay) }.toSet()
    val events = report.events.groupingBy { dayOf(it.timeMillis) }.eachCount()
    val tests = report.tests.groupingBy { dayOf(it.startMillis) }.eachCount()
    val phoneTests = report.phoneTests.groupingBy { dayOf(it.timeMillis) }.eachCount()
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { day ->
        DayProgress(
            day, checks[day] ?: 0, usable[day] ?: 0, day in rated, events[day] ?: 0,
            (tests[day] ?: 0) + (phoneTests[day] ?: 0),
        )
    }.toList()
}

/**
 * How a running trial is coming along: what has been collected and logged so far, day by
 * day. It deliberately shows amounts only. What the checks found stays hidden until the
 * trial ends, so that knowing the score cannot colour how the wearer feels or rates a day.
 */
@Composable
fun ProgressScreen(trialId: String, onBack: () -> Unit, navigate: (String) -> Unit) {
    val app = app()
    val now by tickingNow()
    // Reloaded every minute or so, as new checks arrive.
    val report by produceState<TrialReport?>(null, trialId, now / 60_000) { value = loadReport(app, trialId) }

    Screen(title = "Trial progress", onBack = onBack) {
        val loaded = report
        if (loaded == null) {
            CircularProgressIndicator()
            return@Screen
        }
        val trial = loaded.trial
        val zone = ZoneId.systemDefault()
        val days = dayProgress(loaded, now, zone)

        Text(loaded.label, style = MaterialTheme.typography.headlineSmall)
        Text(
            "This shows how much has been collected, not what it found. Tremor results stay " +
                "hidden until the trial ends, so they cannot influence you.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Time")
        val length = (trial.plannedEndMillis - trial.startMillis).coerceAtLeast(1)
        val done = (minOf(now, trial.endMillis) - trial.startMillis).coerceIn(0, length)
        LinearProgressIndicator(progress = { done.toFloat() / length }, modifier = Modifier.fillMaxWidth())
        val totalDays = Math.ceil(length / 86_400_000.0).toInt()
        if (trial.inWashout(now)) {
            LabelValue("Settling in", "measuring starts in ${Fmt.duration(trial.startMillis - now)}")
        } else if (totalDays > 1) {
            val dayNumber = (done / 86_400_000L + 1).toInt().coerceAtMost(totalDays)
            LabelValue("Day", "$dayNumber of $totalDays")
        }
        LabelValue("Started", Fmt.dateTime(trial.startMillis))
        if (trial.isRunning(now)) {
            LabelValue("Time left", Fmt.duration(trial.plannedEndMillis - now))
            LabelValue("Ends", Fmt.dateTime(trial.plannedEndMillis))
        } else {
            LabelValue("Ended", Fmt.dateTime(trial.endMillis))
        }

        SectionTitle("Collected by the watch")
        val planned = plannedChecks(trial, now, zone)
        val made = loaded.hiddenWindows.size + loaded.skips.values.sum()
        LabelValue("Checks made", "$made of about ${Fmt.num(planned, 0)} planned")
        loaded.coverage().forEach { (label, value) -> LabelValue(label, value) }
        val usable = loaded.summary.usableCount
        val needed = TrialStatistics.MIN_SAMPLES_TO_COMPARE
        Text(
            if (usable < needed) {
                "${needed - usable} more usable measurements are needed before this setting can be compared with another."
            } else {
                "Enough to compare this setting with another. More days still make the result firmer."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        if (days.isNotEmpty()) {
            SectionTitle("Checks per day")
            // A full day's worth, for trials long enough to have one.
            val perDay = if (length >= 86_400_000L) {
                plannedChecks(trial.copy(endedMillis = null), trial.startMillis + 86_400_000L, zone)
            } else {
                null
            }
            BarChart(
                bars = days.map {
                    ChartBar(
                        "${it.day.dayOfMonth}/${it.day.monthValue}", it.checks.toDouble(),
                        detail = Fmt.date(it.day.toEpochDay()),
                    )
                },
                unit = " " + tr("checks"),
                reference = perDay,
                referenceLabel = "plan for a full day",
            )
        }

        SectionTitle("Logged by you")
        val ratedDays = days.count { it.rated }
        LabelValue("Daily ratings", "$ratedDays of ${days.size} days")
        LabelValue("Events", loaded.events.size.toString())
        loaded.events.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }.forEach { (type, count) ->
            LabelValue("    " + tr(type), count.toString())
        }
        LabelValue("Guided tests on the watch", loaded.tests.size.toString())
        LabelValue("Hand and speech tests", loaded.phoneTests.size.toString())
        val today = LocalDate.now(zone)
        if (trial.isRunning(now) && days.any { it.day == today && !it.rated }) {
            BigOutlinedButton(onClick = { navigate("rating") }, modifier = Modifier.fillMaxWidth()) {
                Text("Rate today")
            }
        }

        if (days.isNotEmpty()) {
            SectionTitle("Day by day")
            days.asReversed().forEach { day ->
                val parts = listOf(
                    tr("${day.checks} checks"),
                    tr("${day.usable} usable"),
                    tr(if (day.rated) "rated" else "not rated"),
                ) + listOfNotNull(
                    tr("${day.events} events").takeIf { day.events > 0 },
                    tr("${day.tests} tests").takeIf { day.tests > 0 },
                )
                Text(Fmt.date(day.day.toEpochDay()), style = MaterialTheme.typography.titleSmall)
                Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
