package app.tremolo.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tremolo.phone.BuildConfig
import app.tremolo.phone.Reminders
import app.tremolo.phone.TremoloApp
import app.tremolo.phone.data.Trial
import app.tremolo.phone.report.Fmt
import app.tremolo.phone.report.plannedChecks
import app.tremolo.phone.trialHealthWarning
import app.tremolo.shared.protocol.WindowKind
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(navigate: (String) -> Unit) {
    val app = app()
    val trials by app.dao.trials().collectAsStateWithLifecycle(emptyList())
    val settings by app.dao.settings().collectAsStateWithLifecycle(emptyList())
    val plans by app.dao.plans().collectAsStateWithLifecycle(emptyList())
    val now by tickingNow()
    val running = trials.firstOrNull { it.isRunning(now) }
    val finished = trials.filter { !it.isRunning(now) }
    val activePlan = plans.firstOrNull { !it.isFinished }
    var appointmentDay by remember { mutableStateOf(app.prefs.appointmentDay) }
    val daysToAppointment = appointmentDay - java.time.LocalDate.now().toEpochDay()

    fun planOf(trial: Trial) = plans.firstOrNull { it.id == trial.planId }
    fun isLocked(trial: Trial) = planOf(trial)?.isFinished == false
    fun labelOf(trial: Trial): String =
        if (isLocked(trial) && planOf(trial)?.blinded == true) "Setting ${trial.blindCode ?: "?"}"
        else settings.firstOrNull { it.id == trial.settingId }?.label ?: "Setting"

    // A plan whose last block has ended is closed here, which unlocks its results.
    LaunchedEffect(activePlan?.id, activePlan?.position, running?.id) { app.trials.settlePlan() }

    Screen(
        title = "Tremolo",
        actions = {
            TextButton(onClick = { navigate("debug") }) { Text("Calibrate") }
            TextButton(onClick = { navigate("settings") }) { Text("Settings") }
        },
    ) {
        if (running != null) {
            RunningTrialCard(running, labelOf(running), now, onProgress = { navigate("progress/${running.id}") })
        }
        if (activePlan != null) {
            PlanCard(activePlan, settings, blockRunning = running?.planId == activePlan.id)
        }
        if (running == null && activePlan == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No trial running", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Switch your DBS to the setting you want to test, then start a trial. " +
                            "The watch measures your tremor at moments you cannot predict.",
                    )
                    BigButton(onClick = { navigate("newTrial") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Start a trial")
                    }
                    BigOutlinedButton(onClick = { navigate("newPlan") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Plan several settings")
                    }
                }
            }
        }

        if (appointmentDay > 0 && daysToAppointment >= 0) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (daysToAppointment) {
                            0L -> "DBS appointment today"
                            1L -> "DBS appointment tomorrow"
                            else -> "DBS appointment in $daysToAppointment days"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(Fmt.date(appointmentDay), style = MaterialTheme.typography.bodySmall)
                    BigOutlinedButton(onClick = { navigate("compare") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reports to share")
                    }
                    TextButton(
                        onClick = {
                            app.prefs.appointmentDay = 0L
                            appointmentDay = 0L
                            Reminders.scheduleAppointment(app)
                        },
                        modifier = Modifier.heightIn(min = TouchTarget),
                    ) { Text("Remove appointment") }
                }
            }
        }

        // Everyday actions, in a grid of large targets.
        listOf(
            listOf("Log event" to "events", "Rate today" to "rating"),
            listOf("Medications" to "medications", "Tests" to "handTests"),
            listOf("Timeline" to "timeline", "Compare" to "compare"),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (title, route) ->
                    BigOutlinedButton(onClick = { navigate(route) }, modifier = Modifier.weight(1f)) {
                        Text(title)
                    }
                }
            }
        }

        TextButton(onClick = { navigate("guide") }, modifier = Modifier.heightIn(min = TouchTarget)) {
            Text("How Tremolo works")
        }

        if (finished.isNotEmpty()) {
            SectionTitle("Finished trials")
            finished.forEach { trial ->
                val locked = isLocked(trial)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget)
                        .clickable(enabled = !locked) { navigate("report/${trial.id}") },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(labelOf(trial), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${Fmt.dateTime(trial.startMillis)}  ·  " +
                                Fmt.duration(trial.endMillis - trial.startMillis),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (locked) {
                            Text("Locked until the plan is finished", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("View report", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningTrialCard(trial: Trial, label: String, now: Long, onProgress: () -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    var confirmEnd by remember { mutableStateOf(false) }
    var testDialog by remember { mutableStateOf<String?>(null) }
    val watchConnected by produceState<Boolean?>(null) {
        while (true) {
            value = app.wear.isWatchConnected()
            delay(10_000)
        }
    }
    val warning by produceState<String?>(null, trial.id, now) {
        value = trialHealthWarning(app, trial, now)
    }
    // Share of the planned checks the watch has made. Says nothing about what they found.
    val checksMade by produceState<String?>(null, trial.id, now) {
        val planned = plannedChecks(trial, now)
        val made = app.dao.windowCountSince(trial.id, trial.startMillis) + app.dao.skipCount(trial.id)
        value = when {
            planned < 8 -> null
            made >= 0.8 * planned -> "On track"
            else -> "${Fmt.pct(100.0 * made / planned)} of planned"
        }
    }

    fun startTest(kind: WindowKind, instruction: String) {
        scope.launch {
            val sent = app.wear.startActiveTest(kind, trial.id)
            testDialog = if (sent) {
                "$instruction\n\nThe watch buzzes once when recording starts (in about 5 seconds) " +
                    "and twice when it ends, 30 seconds later."
            } else {
                "The watch is not reachable right now. Check that it is on and nearby."
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trial running", style = MaterialTheme.typography.labelLarge)
            Text(label, style = MaterialTheme.typography.headlineSmall)
            // Blinded on purpose: no measurement times or results until the trial ends.
            if (trial.inWashout(now)) {
                LabelValue("Settling in", "measuring starts in ${Fmt.duration(trial.startMillis - now)}")
            }
            LabelValue("Time left", Fmt.duration(trial.plannedEndMillis - now))
            LabelValue("Ends", Fmt.dateTime(trial.plannedEndMillis))
            checksMade?.let { LabelValue("Checks by the watch", it) }
            LabelValue(
                "Watch",
                when (watchConnected) {
                    true -> "Connected"
                    false -> "Not reachable"
                    null -> "Checking…"
                },
            )
            warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Results stay hidden until the trial ends, so they cannot influence you.",
                style = MaterialTheme.typography.bodySmall,
            )
            BigOutlinedButton(onClick = onProgress, modifier = Modifier.fillMaxWidth()) {
                Text("See what has been collected")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigOutlinedButton(
                    onClick = { startTest(WindowKind.TEST_REST, "Sit down and rest your hand in your lap.") },
                    modifier = Modifier.weight(1f),
                ) { Text("Rest test") }
                BigOutlinedButton(
                    onClick = {
                        startTest(WindowKind.TEST_POSTURAL, "Hold both arms straight out in front of you.")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Posture test") }
            }
            TextButton(
                onClick = { confirmEnd = true },
                modifier = Modifier.heightIn(min = TouchTarget),
            ) { Text("End trial early") }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this trial now?") },
            text = { Text("Monitoring stops and the report is made from what was collected so far.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    scope.launch { app.trials.endEarly(trial) }
                }) { Text("End trial") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep running") } },
        )
    }

    testDialog?.let { message ->
        AlertDialog(
            onDismissRequest = { testDialog = null },
            title = { Text("Guided test") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { testDialog = null }) { Text("OK") } },
        )
    }
}
