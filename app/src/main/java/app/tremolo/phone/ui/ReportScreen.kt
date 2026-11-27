package app.tremolo.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tremolo.phone.report.Fmt
import app.tremolo.shared.i18n.tr
import app.tremolo.phone.report.dataQuality
import app.tremolo.phone.report.irregularRestPct
import app.tremolo.phone.report.dayBars
import app.tremolo.phone.report.hourBars
import app.tremolo.phone.report.ReportExporter
import app.tremolo.phone.report.SettingResult
import app.tremolo.phone.report.TrialReport
import app.tremolo.phone.report.loadReport
import app.tremolo.phone.report.loadSettingResults
import app.tremolo.phone.report.resultRows
import app.tremolo.shared.stats.Comparison
import app.tremolo.shared.stats.DayKind
import app.tremolo.phone.report.loadTrialResults
import app.tremolo.shared.stats.Fairness
import java.time.ZoneId
import app.tremolo.shared.stats.MetricComparison
import app.tremolo.shared.stats.TrialStatistics
import app.tremolo.shared.stats.Verdict
import kotlinx.coroutines.launch

@Composable
fun ReportScreen(trialId: String, onBack: () -> Unit) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded by produceState<Pair<Boolean, TrialReport?>>(false to null, trialId) {
        value = true to loadReport(app, trialId)
    }
    val report = loaded.second
    var confirmDelete by remember { mutableStateOf(false) }

    Screen(title = "Trial report", onBack = onBack) {
        when {
            !loaded.first -> CircularProgressIndicator()
            report == null -> Text("This trial could not be found.")
            report.trial.isRunning() ->
                // Blinding: a running trial never shows results, even via a direct link.
                Text("This trial is still running. The report unlocks when it ends.")
            report.locked ->
                Text(
                    "This trial is part of a plan that is still running. Its results unlock when " +
                        "the whole plan is finished, so earlier blocks cannot influence later ones.",
                )
            else -> {
                ReportBody(report)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(
                        onClick = { scope.launch { ReportExporter.sharePdf(context, report) } },
                        modifier = Modifier.weight(1f),
                    ) { Text("Share PDF") }
                    BigOutlinedButton(
                        onClick = { scope.launch { ReportExporter.shareCsv(context, report) } },
                        modifier = Modifier.weight(1f),
                    ) { Text("Export CSV") }
                }
                Text(ReportExporter.DISCLAIMER, style = MaterialTheme.typography.bodySmall)
            }
        }
        // A locked trial is part of a running plan and cannot be taken out of it.
        if (report != null && !report.trial.isRunning() && !report.locked) {
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
            ) { Text("Delete this trial", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmDelete && report != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this trial?") },
            text = {
                Text(
                    "The trial and all its measurements are removed for good, and " +
                        "it no longer counts in comparisons. Your daily ratings and events are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        app.trials.delete(report.trial)
                        onBack()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun NotEnoughDataCard(usableCount: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Only $usableCount usable measurements were collected. Treat these numbers as a " +
                "rough impression; at least ${TrialStatistics.MIN_SAMPLES_TO_COMPARE} are needed " +
                "for a comparison.",
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ReportBody(report: TrialReport) {
    Text(report.label, style = MaterialTheme.typography.headlineSmall)
    report.setting?.details()?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "${Fmt.dateTime(report.trial.startMillis)} to ${Fmt.dateTime(report.trial.endMillis)}",
        style = MaterialTheme.typography.bodySmall,
    )

    if (!report.enoughData) NotEnoughDataCard(report.summary.usableCount)

    SectionTitle("Results")
    report.headline().forEach { (label, value) -> LabelValue(label, value) }

    SectionTitle("Measurements")
    report.coverage().forEach { (label, value) -> LabelValue(label, value) }

    val quality = remember(report) { dataQuality(report) }
    SectionTitle("Data quality: ${quality.verdict}")
    quality.rows.forEach { (label, value) -> LabelValue(label, value) }
    quality.concerns.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    if (quality.concerns.isEmpty()) {
        Text("Enough measurements, spread well over the trial.", style = MaterialTheme.typography.bodySmall)
    }

    if (report.summary.byContext.isNotEmpty()) {
        SectionTitle("By situation")
        report.summary.byContext.toSortedMap().forEach { (context, group) ->
            LabelValue(
                "${Fmt.context(context)} (${group.count})",
                "${Fmt.pct(group.tremorTimePct)} of time, ${Fmt.num(group.medianAmplitude)} °/s",
            )
        }
    }

    val byKind = remember(report) { DayKind.breakdown(report.samples, report.dayKinds, ZoneId.systemDefault()) }
    if (byKind.isNotEmpty()) {
        SectionTitle("By kind of day")
        byKind.forEach { group ->
            LabelValue(
                tr(DayKind.title(group.kind)) + " (${group.summary.count})",
                "${Fmt.pct(group.summary.tremorTimePct)} of time, ${Fmt.num(group.summary.medianAmplitude)} °/s",
            )
        }
    }

    if (report.summary.byHour.isNotEmpty()) {
        SectionTitle("Time with tremor by hour of day")
        BarChart(hourBars(report.summary.byHour), unit = "%")
    }
    if (report.summary.byDay.size > 1) {
        SectionTitle("Time with tremor by day")
        BarChart(
            dayBars(report.summary.byDay.keys.sorted(), report.summary.byDay) { it.tremorTimePct }, unit = "%",
            reference = report.summary.tremorTimePct?.value, referenceLabel = "whole trial",
        )
    }

    if (report.tests.isNotEmpty()) {
        SectionTitle("Guided tests")
        report.tests.forEach { test ->
            val f = test.features
            LabelValue(
                "${Fmt.testName(test.kind)}\n${Fmt.dateTime(test.startMillis)}",
                if (f == null) "Not analysable"
                else "${Fmt.num(f.tremorRmsDegPerSec)} °/s at ${Fmt.num(f.dominantFreqHz)} Hz, " +
                    "${Fmt.pct(f.tremorFraction * 100)} of time",
            )
        }
    }

    if (report.phoneTests.isNotEmpty()) {
        SectionTitle("Hand tests on the phone")
        report.phoneTests.forEach { test ->
            LabelValue("${test.kind}\n${Fmt.dateTime(test.timeMillis)}", test.detail)
        }
    }

    if (report.ratings.isNotEmpty()) {
        SectionTitle("Your daily ratings (0 = none, 10 = worst)")
        report.ratings.forEach { r ->
            LabelValue(
                Fmt.date(r.epochDay),
                "Tremor ${r.tremor}, side effects ${r.sideEffects}, mood ${r.mood}" +
                    Fmt.symptoms(report.symptomScores[r.epochDay]) +
                    if (r.note.isBlank()) "" else "\n${r.note}",
            )
        }
    }

    if (report.events.isNotEmpty()) {
        SectionTitle("Logged events")
        report.events.forEach { e ->
            LabelValue(Fmt.dateTime(e.timeMillis), if (e.note.isBlank()) e.type else "${e.type}: ${e.note}")
        }
    }
    HorizontalDivider()
}

/** Compares DBS settings, pooling every finished trial of the same setting. */
@Composable
fun CompareScreen(onBack: () -> Unit) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val results by produceState<List<SettingResult>?>(null) { value = loadSettingResults(app) }
    val trialResults by produceState<List<SettingResult>?>(null) { value = loadTrialResults(app) }
    // Null until the user chooses: then single trials are shown when there is only one setting.
    var chosenByTrial by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var idA by rememberSaveable { mutableStateOf<Long?>(null) }
    var idB by rememberSaveable { mutableStateOf<Long?>(null) }

    Screen(title = "Compare settings", onBack = onBack) {
        val settings = results
        val singles = trialResults
        if (settings == null || singles == null) {
            CircularProgressIndicator()
            return@Screen
        }
        val byTrial = chosenByTrial ?: (settings.size < 2 && singles.size >= 2)
        if (singles.size >= 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !byTrial,
                    onClick = { chosenByTrial = false },
                    label = { Text("Settings") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = byTrial,
                    onClick = { chosenByTrial = true },
                    label = { Text("Single trials") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        val list = if (byTrial) singles else settings
        if (list.size < 2) {
            Text(
                "Finish trials of at least two different settings to compare them. " +
                    "Trials inside a running plan stay locked until the plan ends.",
            )
            if (settings.isNotEmpty()) {
                BigOutlinedButton(
                    onClick = { scope.launch { ReportExporter.shareProgrammerReport(context, app, settings) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Report for my DBS programmer") }
            }
            return@Screen
        }
        val a = list.firstOrNull { it.key == idA } ?: list[0]
        val b = list.firstOrNull { it.key == idB && it.key != a.key }
            ?: list.first { it.key != a.key }

        Text(
            if (byTrial) {
                "Each finished trial is listed on its own, newest first. Pick two to compare, for " +
                    "example two trials of the same setting on different kinds of days."
            } else {
                "Settings are listed from least to most time with tremor. All finished trials of " +
                    "the same setting are combined. Pick two to compare."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        list.forEach { result ->
            val id = result.key
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(result.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(if (result.trialCount == 1) "1 trial" else "${result.trialCount} trials") + "  ·  " +
                            tr(if (result.summary.usableCount == 1) "1 measurement" else "${result.summary.usableCount} measurements"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    resultRows(
                        result.summary, result.dystonicPosturePct, irregularRestPct(result.samples),
                    ).forEach { (label, value) ->
                        LabelValue(label, value)
                    }
                    result.ratingAverages()?.let { LabelValue("Your ratings (average)", it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = a.key == id,
                            onClick = {
                                if (b.key == id) idB = a.key
                                idA = id
                            },
                            label = { Text("Compare as A") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                        FilterChip(
                            selected = b.key == id,
                            onClick = {
                                if (a.key == id) idA = b.key
                                idB = id
                            },
                            label = { Text("Compare as B") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }

        val comparison = remember(a.key, b.key) { TrialStatistics.compare(a.samples, b.samples) }
        ComparisonCard(a, b, comparison)
        // The shared reports are always about settings, whichever list is showing.
        BigButton(
            onClick = { scope.launch { ReportExporter.shareClinicianSummary(context, settings) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Share summary for my clinician") }
        BigOutlinedButton(
            onClick = { scope.launch { ReportExporter.shareProgrammerReport(context, app, settings) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Report for my DBS programmer") }
        Text(
            "The summary is a short overview in plain language. The programmer report is the " +
                "technical version: stimulation parameters, tables with confidence intervals, " +
                "side effects, function tests and the method.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(ReportExporter.DISCLAIMER, style = MaterialTheme.typography.bodySmall)
    }
}

/** Plain-language verdict for one measure; also used in the clinician summary. */
fun describeComparison(metric: MetricComparison, labelA: String, labelB: String, what: String, unit: String): String =
    when (metric.verdict) {
        Verdict.NOT_ENOUGH_DATA ->
            "Not enough measurements to compare $what (each setting needs at least " +
                "${TrialStatistics.MIN_SAMPLES_TO_COMPARE})."
        Verdict.NO_CLEAR_DIFFERENCE -> "No clear difference in $what."
        Verdict.A_BETTER, Verdict.B_BETTER -> {
            val better = if (metric.verdict == Verdict.A_BETTER) labelA else labelB
            val d = metric.difference!!
            val low = minOf(kotlin.math.abs(d.low), kotlin.math.abs(d.high))
            val high = maxOf(kotlin.math.abs(d.low), kotlin.math.abs(d.high))
            "$better had less $what: ${Fmt.num(kotlin.math.abs(d.value))}$unit lower " +
                "(likely between ${Fmt.num(low)} and ${Fmt.num(high)}$unit)."
        }
    }

@Composable
private fun ComparisonCard(a: SettingResult, b: SettingResult, comparison: Comparison) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A: ${a.label}  vs  B: ${b.label}", style = MaterialTheme.typography.titleMedium)
            Text(describeComparison(comparison.tremorTime, a.label, b.label, "time with tremor", " percentage points"))
            Text(describeComparison(comparison.amplitude, a.label, b.label, "tremor strength", " °/s"))
            val fairness = remember(a.key, b.key) {
                Fairness.check(a.samples, b.samples, a.label, b.label, ZoneId.systemDefault(), a.dayKinds, b.dayKinds)
            }
            Text("Is this a fair comparison?", style = MaterialTheme.typography.titleSmall)
            if (fairness.fair) {
                Text(
                    "Yes, as far as the app can tell: both settings were measured at similar times " +
                        "of day, with similar activity, over enough days.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                fairness.concerns.forEach { Text("•  " + tr(it), style = MaterialTheme.typography.bodySmall) }
                fairness.matchedNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text(
                "This compares only the watch measurements. Side effects, speech, mood and how " +
                    "you felt matter too; check your ratings above.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
