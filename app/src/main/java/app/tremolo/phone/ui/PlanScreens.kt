@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.tremolo.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tremolo.phone.data.DbsSetting
import app.tremolo.phone.data.Plan
import app.tremolo.phone.report.Fmt
import androidx.compose.ui.platform.LocalContext
import app.tremolo.phone.Reminders
import java.time.LocalDate
import app.tremolo.shared.i18n.tr
import kotlinx.coroutines.launch

/** Sets up a crossover plan: several settings, random order, repeated. */
@Composable
fun NewPlanScreen(onBack: () -> Unit, onEditSetting: (Long) -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    val settings by app.dao.settings().collectAsStateWithLifecycle(emptyList())
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var repeats by rememberSaveable { mutableStateOf(2) }
    var amount by rememberSaveable { mutableStateOf(app.prefs.defaultTrialDays().toString()) }
    var inDays by rememberSaveable { mutableStateOf(true) }
    var washoutHours by rememberSaveable { mutableStateOf(app.prefs.defaultSettlingHours()) }
    var blinded by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var appointmentDays by rememberSaveable { mutableStateOf("") }
    var fitMessage by rememberSaveable { mutableStateOf("") }

    /** Chooses repeats and block length so the plan ends a day before the appointment. */
    fun fitToAppointment() {
        val days = appointmentDays.toIntOrNull()
        if (days == null || days < 2 || selected.size < 2) {
            fitMessage = "Pick at least two settings above and enter how many days are left."
            return
        }
        val available = (days - 1) * 24
        fun blockFor(times: Int) = available / (selected.size * times) - washoutHours
        val twice = blockFor(2)
        val times = if (twice >= 48) 2 else 1
        val block = blockFor(times)
        app.prefs.appointmentDay = LocalDate.now().plusDays(days.toLong()).toEpochDay()
        Reminders.scheduleAppointment(context)
        if (block < 12) {
            fitMessage = "There is too little time to test ${selected.size} settings with " +
                "$washoutHours h of settling-in each. Choose fewer settings or a shorter settling-in time."
            return
        }
        repeats = times
        if (block >= 48) {
            amount = (block / 24).toString()
            inDays = true
        } else {
            amount = block.toString()
            inDays = false
        }
        fitMessage = tr(
            "Fitted: each setting ${if (times == 2) "twice" else "once"}, " +
                "${if (inDays) "$amount days" else "$amount hours"} per block, finishing the day before.",
        ) + " " +
            (if (times == 1) tr("With more time each setting could be tested twice, which is more reliable.") + " " else "") +
            tr("You will get a reminder the evening before.")
    }

    val blockHours = amount.toIntOrNull()?.takeIf { it > 0 }?.let { it * if (inDays) 24 else 1 }
    val blockCount = selected.size * repeats

    Screen(title = "Plan several settings", onBack = onBack) {
        Text(
            "A plan tests each setting more than once, in a random order. Repeating is what " +
                "separates a real difference from a good or bad week. Results stay locked " +
                "until the whole plan is finished.",
        )

        SectionTitle("1. Settings to compare (pick at least two)")
        settings.forEach { setting ->
            val checked = setting.id in selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .clickable { selected = if (checked) selected - setting.id else selected + setting.id },
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(setting.label, style = MaterialTheme.typography.titleMedium)
                    setting.details().takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        BigOutlinedButton(onClick = { onEditSetting(0L) }) { Text("Add a setting") }

        SectionTitle("2. How many times each?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3).forEach { option ->
                FilterChip(
                    selected = repeats == option,
                    onClick = { repeats = option },
                    label = { Text(if (option == 1) "Once" else "$option times") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        SectionTitle("3. Length of each block")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = amount,
                onValueChange = { text -> amount = text.filter { it.isDigit() }.take(3) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp),
            )
            FilterChip(
                selected = !inDays, onClick = { inDays = false }, label = { Text("Hours") },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            FilterChip(
                selected = inDays, onClick = { inDays = true }, label = { Text("Days") },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }

        SectionTitle("4. Settling-in time after each switch")
        Text(
            "Nothing is measured during this time, so the previous setting's effect can fade. " +
                "Ask your clinician how long your settings take to settle.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 2, 24, 48, 72).forEach { option ->
                FilterChip(
                    selected = washoutHours == option,
                    onClick = { washoutHours = option },
                    label = { Text(if (option == 0) "None" else "$option h") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        SectionTitle("Fit to an appointment (optional)")
        Text(
            "If you see your DBS programmer soon, enter the days until then and the plan is " +
                "sized to finish in time, with the report ready to share.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = appointmentDays,
                onValueChange = { text -> appointmentDays = text.filter { it.isDigit() }.take(3) },
                label = { Text("Days left") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(130.dp),
            )
            BigOutlinedButton(onClick = { fitToAppointment() }) { Text("Fit the plan") }
        }
        if (fitMessage.isNotEmpty()) Text(fitMessage, style = MaterialTheme.typography.bodySmall)

        SectionTitle("5. Blinding")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget)
                .clickable { blinded = !blinded },
        ) {
            Text(
                "A helper switches the settings, and the app hides their names from me",
                modifier = Modifier.weight(1f),
            )
            Switch(checked = blinded, onCheckedChange = { blinded = it })
        }

        if (selected.size >= 2 && blockHours != null) {
            Text(
                "$blockCount blocks, about " +
                    Fmt.duration(blockCount * (blockHours + washoutHours) * 3_600_000L) + " in total.",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        BigButton(
            enabled = selected.size >= 2 && blockHours != null && !creating,
            onClick = {
                creating = true
                scope.launch {
                    app.trials.createPlan(selected.toList(), repeats, blockHours!!, washoutHours, blinded)
                    onBack()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create plan") }
    }
}

/** Shown on the home screen while a plan is active and waiting for the next switch. */
@Composable
fun PlanCard(plan: Plan, settings: List<DbsSetting>, blockRunning: Boolean) {
    val app = app()
    val scope = rememberCoroutineScope()
    var reveal by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val total = plan.sequenceList().size
    val nextId = app.trials.nextSettingId(plan)
    val nextLabel = settings.firstOrNull { it.id == nextId }?.label ?: "the next setting"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Plan in progress", style = MaterialTheme.typography.labelLarge)
            Text(
                if (blockRunning) "Block ${plan.position} of $total is running"
                else "${plan.position} of $total blocks done",
                style = MaterialTheme.typography.titleMedium,
            )
            if (!blockRunning && nextId != null) {
                if (plan.blinded) {
                    Text("Ask your helper to switch your DBS to the next setting, without telling you which.")
                    BigOutlinedButton(onClick = { reveal = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Helper: show the next setting")
                    }
                } else {
                    Text("Next: switch your DBS to")
                    Text(nextLabel, style = MaterialTheme.typography.headlineSmall)
                }
                BigButton(
                    onClick = { scope.launch { app.trials.startNextBlock(plan) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Switched: start the next block") }
            }
            Text(
                "Results stay locked until the plan is finished.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { confirmStop = true }, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text("Stop this plan")
            }
        }
    }

    if (reveal) {
        AlertDialog(
            onDismissRequest = { reveal = false },
            title = { Text("For the helper only") },
            text = { Text("Switch the DBS to:\n\n$nextLabel\n\nDo not say which one it is, then close this.") },
            confirmButton = { TextButton(onClick = { reveal = false }) { Text("Close") } },
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop this plan?") },
            text = { Text("The blocks already finished become normal trials and their results unlock.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    scope.launch { app.trials.abandonPlan(plan) }
                }) { Text("Stop plan") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep going") } },
        )
    }
}
