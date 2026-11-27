package app.tremolo.phone.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tremolo.phone.Backup
import app.tremolo.phone.Reminders
import app.tremolo.phone.SAMPLING_OPTIONS
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.tremolo.phone.data.DailyRating
import app.tremolo.phone.data.DayKindEntry
import app.tremolo.shared.stats.DayKind
import app.tremolo.phone.data.EventLogEntry
import app.tremolo.phone.report.Fmt
import app.tremolo.phone.report.ReportExporter
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun RatingScreen(onBack: () -> Unit, navigate: (String) -> Unit = {}) {
    val app = app()
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now().toEpochDay() }
    // Saveable, so scores set but not yet saved survive a visit to the earlier-days screen.
    var tremor by rememberSaveable { mutableIntStateOf(5) }
    var sideEffects by rememberSaveable { mutableIntStateOf(0) }
    var mood by rememberSaveable { mutableIntStateOf(5) }
    var note by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var kindToday by remember { mutableStateOf("") }
    var kindYesterday by remember { mutableStateOf("") }

    LaunchedEffect(today) {
        app.dao.dayKindsBetween(today - 1, today).forEach {
            if (it.epochDay == today) kindToday = it.kind else kindYesterday = it.kind
        }
        if (!loaded) {
            app.dao.rating(today)?.let {
                tremor = it.tremor
                sideEffects = it.sideEffects
                mood = it.mood
                note = it.note
            }
            loaded = true
        }
    }

    @Composable
    fun Score(title: String, low: String, high: String, value: Int, onChange: (Int) -> Unit) {
        Column {
            Text("$title: $value", style = MaterialTheme.typography.titleMedium)
            Text("0 = $low, 10 = $high", style = MaterialTheme.typography.bodySmall)
            ScorePicker(value, onChange)
        }
    }

    Screen(title = "Today: ${Fmt.date(today)}", onBack = onBack) {
        Text("Your own impression of today. It is kept next to the watch measurements in the report.")
        Score("Tremor", "none", "worst", tremor) { tremor = it }
        Score("Side effects", "none", "worst", sideEffects) { sideEffects = it }
        Score("Mood", "very good", "very bad", mood) { mood = it }
        DayKindPicker("What kind of day is today?", kindToday) { kindToday = it }
        DayKindPicker("And yesterday?", kindYesterday) { kindYesterday = it }
        TextButton(
            onClick = { navigate("dayKinds") },
            modifier = Modifier.heightIn(min = TouchTarget),
        ) { Text("Set the kind of day for earlier days") }
        Text(
            "Tremor often differs between kinds of days. The report shows them apart, and warns " +
                "when two settings were measured on different kinds of days.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        BigButton(onClick = {
            scope.launch {
                app.dao.upsertRating(DailyRating(today, tremor, sideEffects, mood, note.trim()))
                listOf(today to kindToday, today - 1 to kindYesterday).forEach { (day, kind) ->
                    if (kind.isEmpty()) app.dao.clearDayKind(day) else app.dao.saveDayKind(DayKindEntry(day, kind))
                }
                onBack()
            }
        }) { Text("Save") }
    }
}

/**
 * The kind of day for each of the last weeks. Unlike a rating, this is a plain fact, so it
 * can be filled in or corrected afterwards. Every tap is saved at once.
 */
@Composable
fun DayKindsScreen(onBack: () -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now().toEpochDay() }
    val kinds = remember { mutableStateMapOf<Long, String>() }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }

    LaunchedEffect(today) {
        app.dao.dayKindsBetween(today - DAY_KIND_HISTORY + 1, today).forEach { kinds[it.epochDay] = it.kind }
    }

    Screen(title = "Kind of day", onBack = onBack) {
        Text(
            "Set what kind of day each day was. Reports and comparisons use it right away.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (day in today downTo today - DAY_KIND_HISTORY + 1) {
            DayKindPicker(LocalDate.ofEpochDay(day).format(dayFormat), kinds[day].orEmpty()) { kind ->
                if (kind.isEmpty()) kinds.remove(day) else kinds[day] = kind
                scope.launch {
                    if (kind.isEmpty()) app.dao.clearDayKind(day) else app.dao.saveDayKind(DayKindEntry(day, kind))
                }
            }
        }
    }
}

/** How many days back the kind of day can be set. */
private const val DAY_KIND_HISTORY = 28L

/** Tapping the chosen kind again clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayKindPicker(title: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayKind.ALL.forEach { kind ->
                FilterChip(
                    selected = value == kind,
                    onClick = { onChange(if (value == kind) "" else kind) },
                    label = { Text(DayKind.title(kind)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

private val EVENT_TYPES =
    listOf("Medication", "Switched program", "Side effect", "Caffeine", "Alcohol", "Stress", "Exercise", "Poor sleep", "Other")

/** Extra things worth logging with Parkinson's. */
private val PARKINSONS_EVENT_TYPES = listOf("ON", "OFF", "Dyskinesia", "Freezing")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventLogScreen(onBack: () -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    val events by app.dao.recentEvents().collectAsStateWithLifecycle(emptyList())
    val eventTypes = remember {
        if (app.prefs.condition == "parkinsons") PARKINSONS_EVENT_TYPES + EVENT_TYPES else EVENT_TYPES
    }
    var type by remember { mutableStateOf(eventTypes.first()) }
    var note by remember { mutableStateOf("") }
    val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { note = it }
    }

    Screen(title = "Event log", onBack = onBack) {
        Text("Note things that can change your tremor. They are listed in the trial report.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            eventTypes.forEach { option ->
                FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option) })
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        BigOutlinedButton(
            onClick = {
                runCatching {
                    speak.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Speak the note instead of typing") }
        BigButton(onClick = {
            scope.launch {
                app.dao.insertEvent(EventLogEntry(timeMillis = System.currentTimeMillis(), type = type, note = note.trim()))
                note = ""
            }
        }) { Text("Log it now") }

        if (events.isNotEmpty()) SectionTitle("Recent")
        events.forEach { event ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(if (event.note.isBlank()) event.type else "${event.type}: ${event.note}")
                    Text(Fmt.dateTime(event.timeMillis), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { scope.launch { app.dao.deleteEvent(event) } }) { Text("Delete") }
            }
            HorizontalDivider()
        }
    }
}

/** First-run setup and the settings page share this screen. */
@Composable
fun SettingsScreen(firstRun: Boolean, onDone: () -> Unit) {
    val app = app()
    val context = LocalContext.current
    var wrist by remember { mutableStateOf(app.prefs.wrist) }
    var wakeStart by remember { mutableIntStateOf(app.prefs.wakeStartMinute) }
    var wakeEnd by remember { mutableIntStateOf(app.prefs.wakeEndMinute) }
    var ratingMinute by remember { mutableIntStateOf(app.prefs.ratingMinute) }
    var monitorAlways by remember { mutableStateOf(app.prefs.monitorAlways) }
    val scope = rememberCoroutineScope()
    var measureAtNight by remember { mutableStateOf(app.prefs.measureAtNight) }
    var samplingDensity by remember { mutableStateOf(app.prefs.samplingDensity) }
    var condition by remember { mutableStateOf(app.prefs.condition) }
    var backupStatus by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) scope.launch {
            backupStatus = if (Backup.export(app, uri)) "Backup saved." else "The backup could not be saved."
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            if (Backup.restore(app, uri)) Backup.restart(context)
            else backupStatus = "That file is not a Tremolo backup. Nothing was changed."
        }
    }

    fun save() {
        app.prefs.wrist = wrist
        app.prefs.wakeStartMinute = wakeStart
        app.prefs.wakeEndMinute = wakeEnd
        app.prefs.ratingMinute = ratingMinute
        app.prefs.monitorAlways = monitorAlways
        app.prefs.measureAtNight = measureAtNight
        app.prefs.samplingDensity = samplingDensity
        app.prefs.condition = condition
        app.prefs.onboarded = true
        Reminders.scheduleDailyRating(context)
        scope.launch { app.trials.resync() }
        onDone()
    }

    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { save() }

    Screen(
        title = if (firstRun) "Welcome to Tremolo" else "Settings",
        onBack = if (firstRun) null else onDone,
    ) {
        if (firstRun) {
            Text(
                "Tremolo measures your tremor with your watch while you test a DBS setting, " +
                    "at moments you cannot predict, and reports the result when the trial ends.",
            )
        }

        SectionTitle("What is your DBS for?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("dystonia" to "Dystonia", "parkinsons" to "Parkinson's", "essential" to "Essential tremor", "other" to "Other")
                .forEach { (value, name) ->
                    FilterChip(
                        selected = condition == value,
                        onClick = { condition = value },
                        label = { Text(name) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
        }
        Text(
            if (condition == "dystonia") {
                "Dystonia can take days to respond to a new setting, so trials default to a week " +
                    "with a day of settling-in first. You can change both for every trial."
            } else {
                "Used only to suggest how long a trial and its settling-in time should be. " +
                    "You can change both for every trial."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Which wrist wears the watch?")
        Text("Wear it on the arm with the tremor.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = wrist == "left", onClick = { wrist = "left" })
            Text("Left")
            RadioButton(selected = wrist == "right", onClick = { wrist = "right" })
            Text("Right")
        }

        SectionTitle("Waking hours")
        Text("The watch only measures between these times.", style = MaterialTheme.typography.bodySmall)
        TimeField("Wake up", wakeStart) { wakeStart = it }
        TimeField("Go to bed", wakeEnd) { wakeEnd = it }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget)
                .clickable { measureAtNight = !measureAtNight },
        ) {
            Text("Measure at night too (uses more watch battery)", modifier = Modifier.weight(1f))
            Switch(checked = measureAtNight, onCheckedChange = { measureAtNight = it })
        }

        SectionTitle("How often to measure")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SAMPLING_OPTIONS.forEachIndexed { index, option ->
                FilterChip(
                    selected = samplingDensity == index,
                    onClick = { samplingDensity = index },
                    label = { Text(option.name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Text(
            "${SAMPLING_OPTIONS[samplingDensity].name}: ${SAMPLING_OPTIONS[samplingDensity].perHour}, " +
                "each 20 seconds long, at moments you cannot predict. More checks give firmer " +
                "results in fewer days but use more watch battery. To protect the battery the watch " +
                "halves the pace below 30% charge and quarters it below 15%. A trial that is " +
                "already running keeps the pace it started with.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Everyday monitoring")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget)
                .clickable { monitorAlways = !monitorAlways },
        ) {
            Text(
                "Also measure on days without a trial, to build your tremor timeline",
                modifier = Modifier.weight(1f),
            )
            Switch(checked = monitorAlways, onCheckedChange = { monitorAlways = it })
        }
        Text(
            "During a trial the timeline is hidden, so it cannot influence you.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("End-of-day rating reminder")
        TimeField("Remind me at", ratingMinute) { ratingMinute = it }
        if (!firstRun) {
            Text(
                "Changes to waking hours apply to the next trial you start.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (!firstRun) SleepSection()
        if (!firstRun) LanguageSection()

        if (!firstRun) {
            SectionTitle("Example")
            if (app.prefs.demoMode) {
                Text("You are looking at example data. Go to the home screen to return to your own.")
            }
            DemoEntry()
        }

        // A backup is of the user's own data, so it is not offered while the example shows.
        if (!firstRun && !app.prefs.demoMode) {
            SectionTitle("Backup")
            Text(
                "Save everything (trials, measurements, log, settings) to one file, for example " +
                    "before changing phones.",
                style = MaterialTheme.typography.bodySmall,
            )
            BigOutlinedButton(
                onClick = { saveBackup.launch("tremolo-backup.zip") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save a backup file") }
            BigOutlinedButton(onClick = { confirmRestore = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Restore from a backup file")
            }
            if (backupStatus.isNotEmpty()) Text(backupStatus)
        }
        if (confirmRestore) {
            AlertDialog(
                onDismissRequest = { confirmRestore = false },
                title = { Text("Replace everything with the backup?") },
                text = {
                    Text("All data now in the app is replaced by what is in the backup file. This cannot be undone.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRestore = false
                        pickBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) { Text("Choose backup file") }
                },
                dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
            )
        }

        HorizontalDivider()
        Text(ReportExporter.DISCLAIMER, style = MaterialTheme.typography.bodySmall)
        Text(
            "Only change programs or settings within what your clinician has allowed. " +
                "All data stays on this phone unless you export a report.",
            style = MaterialTheme.typography.bodySmall,
        )

        BigButton(onClick = {
            if (firstRun && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                save()
            }
        }) { Text(if (firstRun) "Get started" else "Save") }
    }
}
