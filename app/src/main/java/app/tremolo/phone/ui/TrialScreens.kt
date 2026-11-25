@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.tremolo.phone.ui

import androidx.compose.runtime.produceState
import app.tremolo.shared.protocol.WatchInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.tremolo.phone.SleepImport
import app.tremolo.phone.data.DbsSetting
import kotlinx.coroutines.launch

@Composable
fun NewTrialScreen(onBack: () -> Unit, onEditSetting: (Long) -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    val settings by app.dao.settings().collectAsStateWithLifecycle(emptyList())
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var amount by rememberSaveable { mutableStateOf(app.prefs.defaultTrialDays().toString()) }
    var settleHours by rememberSaveable { mutableStateOf(app.prefs.defaultSettlingHours()) }
    var wrist by remember { mutableStateOf(app.prefs.wrist) }
    var inDays by rememberSaveable { mutableStateOf(true) }
    var starting by remember { mutableStateOf(false) }

    val amountValue = amount.toIntOrNull()?.takeIf { it > 0 }
    val durationMillis = amountValue?.let { it * (if (inDays) 24L else 1L) * 3_600_000L }

    val alarmsBlocked by produceState(false) { value = app.wear.watchInfo()?.exactAlarms == false }
    // Measuring around the clock without sleep times: sleep then has to be estimated.
    val sleepGuessed by produceState(false) {
        value = app.prefs.wakeRange().let { it.first == it.second } && !SleepImport.granted(app)
    }

    Screen(title = "New trial", onBack = onBack) {
        if (alarmsBlocked) {
            Text(WatchInfo.ALARMS_NOT_ALLOWED, color = MaterialTheme.colorScheme.error)
        }
        if (sleepGuessed) {
            Text(
                "This trial measures day and night, and no sleep data is linked. Tremolo will " +
                    "estimate when you slept from how still your arm was, and leave those " +
                    "measurements out. Linking sleep data or setting your waking hours, both in " +
                    "Settings, is more reliable.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionTitle("1. Which DBS setting are you testing?")
        if (settings.isEmpty()) {
            Text("Add the setting you are about to test, for example \"Program B\".")
        }
        settings.forEach { setting ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedId = setting.id },
            ) {
                RadioButton(selected = selectedId == setting.id, onClick = { selectedId = setting.id })
                Column(Modifier.weight(1f)) {
                    Text(setting.label, style = MaterialTheme.typography.titleMedium)
                    setting.details().takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { onEditSetting(setting.id) }) { Text("Edit") }
            }
        }
        BigOutlinedButton(onClick = { onEditSetting(0L) }) { Text("Add a setting") }

        SectionTitle("2. How long should the trial run?")
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
            FilterChip(selected = !inDays, onClick = { inDays = false }, label = { Text("Hours") })
            FilterChip(selected = inDays, onClick = { inDays = true }, label = { Text("Days") })
        }
        Text(
            "Longer trials give more reliable results. Tremor changes from day to day, so a " +
                "week shows much more than a day, and a comparison needs at least 30 measurements.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("3. Settling-in time")
        Text(
            "If you have only just switched to this setting, let it settle before measuring " +
                "starts. Choose None if you have been on it for a while already.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 2, 24, 48, 72).forEach { option ->
                FilterChip(
                    selected = settleHours == option,
                    onClick = { settleHours = option },
                    label = { Text(if (option == 0) "None" else "$option h") },
                )
            }
        }

        SectionTitle("4. Before you start")
        Text(
            "Make sure your DBS is already on this setting, and wear the watch as much as you " +
                "can during the trial. Which wrist is the watch on?",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("left" to "Left wrist", "right" to "Right wrist").forEach { (value, name) ->
                FilterChip(
                    selected = wrist == value,
                    onClick = {
                        wrist = value
                        app.prefs.wrist = value
                    },
                    label = { Text(name) },
                )
            }
        }
        Text(
            "Results from different wrists are kept apart, so you can test each arm in turn.",
            style = MaterialTheme.typography.bodySmall,
        )
        BigButton(
            enabled = selectedId != null && durationMillis != null && !starting,
            onClick = {
                starting = true
                scope.launch {
                    app.trials.start(
                        selectedId!!, durationMillis!!, startDelayMillis = settleHours * 3_600_000L,
                    )
                    onBack()
                }
            },
        ) { Text("Start trial") }
    }
}

@Composable
fun SettingEditorScreen(settingId: Long, onBack: () -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    var setting by remember { mutableStateOf(DbsSetting(label = "")) }

    LaunchedEffect(settingId) {
        if (settingId != 0L) app.dao.setting(settingId)?.let { setting = it }
    }

    @Composable
    fun Field(label: String, value: String, numeric: Boolean = false, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Screen(title = if (settingId == 0L) "Add setting" else "Edit setting", onBack = onBack) {
        Field("Name, e.g. Program B", setting.label) { setting = setting.copy(label = it) }
        Text(
            "Everything below is optional. A photo of the programmer screen is the quickest; " +
                "typed details also appear in the tables of the reports.",
            style = MaterialTheme.typography.bodySmall,
        )

        SettingPhotoSection(setting.photoPath) { setting = setting.copy(photoPath = it) }

        SectionTitle("Left side of the brain")
        Field("Amplitude (mA)", setting.amplitudeLeft, true) { setting = setting.copy(amplitudeLeft = it) }
        Field("Pulse width (µs)", setting.pulseWidthLeft, true) { setting = setting.copy(pulseWidthLeft = it) }
        Field("Frequency (Hz)", setting.frequencyLeft, true) { setting = setting.copy(frequencyLeft = it) }
        Field("Contacts", setting.contactsLeft) { setting = setting.copy(contactsLeft = it) }

        SectionTitle("Right side of the brain")
        Field("Amplitude (mA)", setting.amplitudeRight, true) { setting = setting.copy(amplitudeRight = it) }
        Field("Pulse width (µs)", setting.pulseWidthRight, true) { setting = setting.copy(pulseWidthRight = it) }
        Field("Frequency (Hz)", setting.frequencyRight, true) { setting = setting.copy(frequencyRight = it) }
        Field("Contacts", setting.contactsRight) { setting = setting.copy(contactsRight = it) }

        SectionTitle("Notes")
        OutlinedTextField(
            value = setting.notes,
            onValueChange = { setting = setting.copy(notes = it) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        BigButton(
            enabled = setting.label.isNotBlank(),
            onClick = {
                scope.launch {
                    val cleaned = setting.copy(label = setting.label.trim())
                    if (settingId == 0L) app.dao.insertSetting(cleaned) else app.dao.updateSetting(cleaned)
                    onBack()
                }
            },
        ) { Text("Save") }
    }
}
