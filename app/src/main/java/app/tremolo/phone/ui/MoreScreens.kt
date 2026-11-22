package app.tremolo.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tremolo.phone.Reminders
import app.tremolo.phone.data.Medication
import app.tremolo.phone.report.DoseBin
import app.tremolo.phone.report.EventEffect
import app.tremolo.phone.report.Fmt
import app.tremolo.phone.SleepImport
import app.tremolo.phone.report.ChartBar
import app.tremolo.phone.report.Insights
import app.tremolo.phone.report.dayBars
import app.tremolo.phone.report.hourBars
import app.tremolo.phone.report.SleepEffect
import app.tremolo.phone.report.dystonicPosturePct
import app.tremolo.phone.report.resultRows
import app.tremolo.shared.stats.Sample
import app.tremolo.shared.stats.SleepClassifier
import app.tremolo.shared.stats.TrialStatistics
import app.tremolo.shared.stats.TrialSummary
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Medication list with reminder times and a one-tap "Taken now". */
@Composable
fun MedicationsScreen(onBack: () -> Unit) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val medications by app.dao.medications().collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<Medication?>(null) }
    var status by remember { mutableStateOf("") }

    val current = editing
    if (current != null) {
        MedicationEditor(
            medication = current,
            onCancel = { editing = null },
            onSave = { edited ->
                scope.launch {
                    val id = app.dao.saveMedication(edited)
                    Reminders.scheduleMedication(context, edited.copy(id = id))
                    editing = null
                }
            },
            onDelete = {
                scope.launch {
                    Reminders.cancelMedication(context, current.id)
                    app.dao.deleteMedication(current)
                    editing = null
                }
            },
        )
        return
    }

    Screen(title = "Medications and reminders", onBack = onBack) {
        Text(
            "Taken doses are added to your event log and shown in trial reports. " +
                "Reminders also appear on your watch, where you can tap Taken.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.primary)
        medications.forEach { medication ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(medication.title(), style = MaterialTheme.typography.titleMedium)
                    val times = medication.timeList()
                    Text(
                        when {
                            times.isEmpty() -> "No set times"
                            medication.remind -> "Reminders at " + times.joinToString(", ") { Fmt.clock(it) }
                            else -> "Taken at " + times.joinToString(", ") { Fmt.clock(it) } + " (reminders off)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton(
                            onClick = {
                                scope.launch {
                                    Reminders.logTaken(app, medication)
                                    status = "Logged ${medication.title()} at ${Fmt.dateTime(System.currentTimeMillis())}"
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (medication.isReminder) "Done now" else "Taken now") }
                        BigOutlinedButton(onClick = { editing = medication }, modifier = Modifier.weight(1f)) {
                            Text("Edit")
                        }
                    }
                }
            }
        }
        BigOutlinedButton(
            onClick = { editing = Medication(name = "") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add a medication or reminder") }
    }
}

@Composable
private fun MedicationEditor(
    medication: Medication,
    onCancel: () -> Unit,
    onSave: (Medication) -> Unit,
    onDelete: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(medication.name) }
    var dose by rememberSaveable { mutableStateOf(medication.dose) }
    var remind by rememberSaveable { mutableStateOf(medication.remind) }
    var isReminder by rememberSaveable { mutableStateOf(medication.isReminder) }
    var times by remember { mutableStateOf(medication.timeList()) }

    Screen(title = if (medication.id == 0L) "Add medication" else "Edit medication", onBack = onCancel) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = dose,
            onValueChange = { dose = it },
            label = { Text("Dose, e.g. 100 mg (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget)
                .clickable { isReminder = !isReminder },
        ) {
            Text("This is not a medication (e.g. recharge my DBS)", modifier = Modifier.weight(1f))
            Switch(checked = isReminder, onCheckedChange = { isReminder = it })
        }

        SectionTitle("Times of day")
        times.forEachIndexed { index, minute ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TimeField("Dose ${index + 1}", minute) { changed ->
                        times = times.toMutableList().also { it[index] = changed }
                    }
                }
                TextButton(
                    onClick = { times = times.filterIndexed { i, _ -> i != index } },
                    modifier = Modifier.heightIn(min = TouchTarget),
                ) { Text("Remove") }
            }
        }
        if (times.size < Reminders.MAX_TIMES_PER_MEDICATION) {
            BigOutlinedButton(onClick = { times = times + (times.lastOrNull()?.plus(240)?.rem(1440) ?: 8 * 60) }) {
                Text("Add a time")
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget)
                .clickable { remind = !remind },
        ) {
            Text("Remind me at these times", modifier = Modifier.weight(1f))
            Switch(checked = remind, onCheckedChange = { remind = it })
        }
        Text(
            "Reminders can arrive a few minutes late when the phone is idle.",
            style = MaterialTheme.typography.bodySmall,
        )

        BigButton(
            enabled = name.isNotBlank(),
            onClick = {
                onSave(
                    medication.copy(
                        name = name.trim(),
                        dose = dose.trim(),
                        times = times.distinct().sorted().joinToString(","),
                        remind = remind,
                        kind = if (isReminder) "reminder" else "medication",
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (medication.id != 0L) {
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text("Delete this medication", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private class TimelineData(
    val summary: TrialSummary,
    val days: List<LocalDate>,
    /** Start of the running trial or plan, if any; nothing from then on is shown. */
    val hiddenSince: Long?,
    val posturePct: Double?,
    val effects: List<EventEffect>,
    val doseCurve: List<DoseBin>,
    /** Hours slept, by the day the night ended on; empty when sleep is not linked. */
    val sleep: Map<LocalDate, Double>,
    val sleepEffect: SleepEffect?,
    /** Measurements taken during sleep, which are left out of everything shown. */
    val asleepCount: Int,
)

/** Day-by-day tremor over recent weeks, from every hidden check inside and outside trials. */
@Composable
fun TimelineScreen(onBack: () -> Unit) {
    val app = app()
    var dayCount by rememberSaveable { mutableStateOf(14) }
    val data by produceState<TimelineData?>(null, dayCount) {
        value = withContext(Dispatchers.Default) {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val days = (dayCount - 1 downTo 0).map { today.minusDays(it.toLong()) }
            val from = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
            // Blinding: while a trial or plan runs, its measurements stay out of sight here too.
            val hiddenSince = app.trials.hiddenSince()
            val until = (hiddenSince ?: Long.MAX_VALUE) - 1
            val split = SleepClassifier.split(
                app.dao.hiddenWindowsBetween(from, until)
                    .mapNotNull { w -> w.features?.let { Sample(w.startMillis, it) } },
                SleepImport.sessions(app, from, until),
            )
            val samples = split.awake
            val events = app.dao.eventsBetween(from, until)
            val sleep = SleepImport.hoursByWakeDay(app, from, minOf(until, System.currentTimeMillis()), zone)
            TimelineData(
                summary = TrialStatistics.summarize(samples, zone),
                days = days,
                hiddenSince = hiddenSince,
                posturePct = dystonicPosturePct(samples, app.prefs.dystonicPosture),
                effects = Insights.eventEffects(samples, events),
                doseCurve = Insights.sinceDose(samples, events),
                sleep = sleep,
                asleepCount = split.asleep.size,
                sleepEffect = Insights.sleepEffect(samples, sleep, zone),
            )
        }
    }

    Screen(title = "Tremor timeline", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 30).forEach { option ->
                FilterChip(
                    selected = dayCount == option,
                    onClick = { dayCount = option },
                    label = { Text("$option days") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        val loaded = data
        if (loaded == null) {
            CircularProgressIndicator()
            return@Screen
        }
        if (loaded.hiddenSince != null) {
            Text(
                "A trial or plan is running, so everything since ${Fmt.dateTime(loaded.hiddenSince)} " +
                    "stays hidden until it ends.",
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val summary = loaded.summary
        if (summary.usableCount == 0) {
            Text(
                "No measurements in this period yet. They come from trials, and from everyday " +
                    "monitoring if you switch it on in Settings.",
            )
            return@Screen
        }

        LabelValue("Measurements used", summary.usableCount.toString())
        if (loaded.asleepCount > 0) LabelValue("Left out: asleep", loaded.asleepCount.toString())
        resultRows(summary, loaded.posturePct).forEach { (label, value) -> LabelValue(label, value) }

        SectionTitle("Time with tremor by day")
        BarChart(
            dayBars(loaded.days, summary.byDay) { it.tremorTimePct }, unit = "%",
            reference = summary.tremorTimePct?.value, referenceLabel = "whole period",
        )

        SectionTitle("Tremor strength by day")
        BarChart(
            dayBars(loaded.days, summary.byDay) { it.medianAmplitude }, unit = " °/s",
            reference = summary.medianAmplitude?.value, referenceLabel = "whole period",
        )

        SectionTitle("Typical day: time with tremor by hour")
        BarChart(hourBars(summary.byHour), unit = "%")

        if (loaded.sleep.isNotEmpty()) {
            SectionTitle("Sleep the night before")
            BarChart(
                loaded.days.map {
                    ChartBar("${it.dayOfMonth}/${it.monthValue}", loaded.sleep[it], detail = Fmt.date(it.toEpochDay()))
                },
                unit = " h",
            )
        }
        loaded.sleepEffect?.let { effect ->
            LabelValue(
                "After nights shorter than ${Fmt.num(effect.medianHours)} h (${effect.shortNights} nights)",
                "${Fmt.pct(effect.shortPct)} of time with tremor",
            )
            LabelValue(
                "After longer nights (${effect.longNights} nights)",
                "${Fmt.pct(effect.longPct)} of time with tremor",
            )
        }
        if (loaded.doseCurve.isNotEmpty()) {
            SectionTitle("Time with tremor by hours since a medication dose")
            BarChart(
                loaded.doseCurve.map { ChartBar(it.label, it.tremorPct, it.count, "${it.label} after a dose") },
                unit = "%",
            )
        }
        if (loaded.effects.isNotEmpty()) {
            SectionTitle("Patterns in your log")
            loaded.effects.forEach { effect ->
                LabelValue(
                    "In the 2 hours after \"${effect.type}\" (${effect.eventCount} times)",
                    "${Fmt.pct(effect.afterPct)} of time with tremor, against ${Fmt.pct(effect.otherPct)} otherwise",
                )
            }
            Text(
                "These are coincidences in your own data, not proof of cause. They need at least " +
                    "three logged events and eight measurements on each side to show up.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()
        Text(
            "Days with few measurements are unreliable; a day with a dot had none. " +
                "Measurements taken while walking or in large movement are left out.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
