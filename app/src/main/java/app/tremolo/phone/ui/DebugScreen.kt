package app.tremolo.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tremolo.phone.BuildConfig
import app.tremolo.phone.Calibration
import app.tremolo.phone.data.DbsSetting
import app.tremolo.phone.data.SampleWindow
import app.tremolo.phone.report.Fmt
import app.tremolo.phone.SAMPLING_OPTIONS
import app.tremolo.shared.i18n.tr
import app.tremolo.shared.stats.BatteryReading
import app.tremolo.shared.stats.BatteryUsage
import app.tremolo.shared.dsp.Psd
import app.tremolo.shared.dsp.TremorAnalyzer
import app.tremolo.shared.model.RawWindowCodec
import app.tremolo.shared.protocol.WatchInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the wearer can say they were doing, and whether tremor should be found. */
/** [expectTremor] is null for recordings that are not about tremor and are not scored. */
private class ActivityLabel(val name: String, val expectTremor: Boolean?)

private val LABELS = listOf(
    ActivityLabel("Still, no tremor", false),
    ActivityLabel("Tremor, hand resting", true),
    ActivityLabel("Tremor, arms held out", true),
    ActivityLabel("Tremor while using hand", true),
    ActivityLabel("Walking", false),
    ActivityLabel("Typing, no tremor", false),
    ActivityLabel("Handshake or waving", false),
    ActivityLabel("Eating or drinking, no tremor", false),
    ActivityLabel("Brushing teeth", false),
    ActivityLabel(Calibration.DYSTONIC_POSTURE_LABEL, null),
    ActivityLabel("Posture: relaxed", null),
)

/**
 * Lets every user teach the analysis their own tremor: labelled recordings, a score of how
 * often the analysis agrees with them, and personal calibration. Labelled recordings are
 * shown at once, which is fine because the wearer starts them knowingly. The hidden trial
 * measurements stay out of this screen except in debug builds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    val allWindows by app.dao.recentWindows(if (BuildConfig.DEBUG) 20 else 200)
        .collectAsStateWithLifecycle(emptyList())
    // Blinding: outside debug builds only recordings the wearer made here are shown.
    val windows = if (BuildConfig.DEBUG) allWindows else allWindows.filter { it.label != null }.take(20)
    var sensitivity by remember { mutableStateOf(app.prefs.sensitivity) }
    val labelled by app.dao.labelledWindows().collectAsStateWithLifecycle(emptyList())
    var status by remember { mutableStateOf("") }
    var label by remember { mutableStateOf(LABELS.first().name) }
    val latest = windows.firstOrNull()
    // Re-read after each recording, which is when the watch reports its real sample rate.
    val watch by produceState<WatchInfo?>(null, latest?.windowId) { value = app.wear.watchInfo() }

    val spectrum by produceState<Psd?>(null, latest?.windowId) {
        value = latest?.let { window ->
            withContext(Dispatchers.IO) {
                runCatching {
                    File(window.rawPath).inputStream().use {
                        TremorAnalyzer.spectrum(RawWindowCodec.read(it))
                    }
                }.getOrNull()
            }
        }
    }

    Screen(title = "Calibrate", onBack = onBack) {
        Text(
            "Every tremor is different. Here you show the watch what your tremor looks like, and " +
                "what your everyday movements look like, so it can tell them apart. A few minutes " +
                "here makes every later result more trustworthy.",
        )
        Text(
            "Best done before a trial rather than during one: these recordings show you results " +
                "straight away, and changing the calibration re-scores measurements already taken.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )

        watch?.let { info ->
            SectionTitle("Your watch")
            Text(
                info.name + if (info.measuredGyroHz > 0) "  ·  ${info.measuredGyroHz} readings a second" else "",
            )
            if (info.problems().isEmpty()) {
                Text("This watch has everything Tremolo needs.", style = MaterialTheme.typography.bodySmall)
            }
            info.problems().forEach {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            BatterySection(latest?.windowId)
        }

        SectionTitle("Teach and check the analysis")
        Text(
            "Pick what you are about to do, tap Record, then do exactly that for 20 seconds. " +
                "Each recording is scored against what the analysis decided.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LABELS.forEach { option ->
                FilterChip(
                    selected = label == option.name,
                    onClick = { label = option.name },
                    label = { Text(option.name) },
                )
            }
        }
        BigButton(onClick = {
            scope.launch {
                status = if (app.wear.captureNow(label)) "Recording \"$label\" for 20 seconds…"
                else "Watch not reachable."
            }
        }) { Text("Record labelled sample") }
        if (status.isNotEmpty()) Text(status)

        Evaluation(labelled, onDelete = { window ->
            scope.launch {
                app.dao.deleteWindow(window.windowId)
                withContext(Dispatchers.IO) { File(window.rawPath).delete() }
            }
        })

        SectionTitle("Personal calibration")
        Text(
            "Learns your own tremor frequency from the recordings labelled as tremor, and your " +
                "dystonic wrist posture from a posture recording. Everything stored is then re-analysed.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            app.prefs.personalRangeHz?.let {
                "Tremor range now: ${Fmt.num(it.start)}–${Fmt.num(it.endInclusive)} Hz"
            } ?: "Tremor range now: not calibrated (any 3–12 Hz rhythm can count)",
        )
        Text(if (app.prefs.dystonicPosture != null) "Dystonic posture: set" else "Dystonic posture: not set")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(onClick = { scope.launch { status = Calibration.calibrate(app) } }) { Text("Calibrate") }
            BigOutlinedButton(onClick = {
                scope.launch {
                    Calibration.clear(app)
                    status = "Calibration cleared."
                }
            }) { Text("Clear") }
        }
        if (status.isNotEmpty()) Text(status)

        SectionTitle("Sensitivity")
        Text(
            "How faint a tremor still counts. If your labelled \"no tremor\" recordings are " +
                "scored as tremor, lower it; if a tremor you can feel is missed, raise it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Only strong", "Normal", "Also faint").forEachIndexed { level, name ->
                FilterChip(
                    selected = sensitivity == level,
                    onClick = {
                        sensitivity = level
                        scope.launch {
                            status = "Re-scoring…"
                            Calibration.setSensitivity(app, level)
                            status = "Sensitivity set to \"$name\". Everything stored was re-scored."
                        }
                    },
                    label = { Text(name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        SectionTitle("Other tools")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (BuildConfig.DEBUG) BigOutlinedButton(onClick = {
                scope.launch {
                    if (app.trials.running() != null) {
                        status = "A trial is already running."
                    } else {
                        val settingId = app.dao.insertSetting(DbsSetting(label = "Debug trial"))
                        app.trials.start(
                            settingId, durationMillis = 10 * 60_000L,
                            intervalSeconds = 45..75, ignoreWakeHours = true,
                        )
                        status = "Started a 10-minute trial with a capture about every minute."
                    }
                }
            }) { Text("10-min fast trial") }
            BigOutlinedButton(onClick = {
                scope.launch {
                    app.ingestor.catchUp()
                    status = "Checked the watch for pending data."
                }
            }) { Text("Fetch pending data") }
        }

        SectionTitle("Latest recording: strength by frequency (1–20 Hz)")
        SpectrumChart(spectrum)

        SectionTitle("Recent recordings")
        if (windows.isEmpty()) Text("Nothing received yet.")
        windows.forEach { window ->
            WindowDetails(window)
            HorizontalDivider()
        }
        Text(
            "Timeline letters, one per second: T tremor-like, Q quiet, W walking, " +
                "H harmonic of a slow rhythm, S tail of slow movement, B broadband jitter, I impact.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Scores the labelled recordings: did the analysis find tremor exactly when it should? */
@Composable
private fun Evaluation(labelled: List<SampleWindow>, onDelete: (SampleWindow) -> Unit) {
    val scored = labelled.mapNotNull { window ->
        val expected = LABELS.firstOrNull { it.name == window.label } ?: return@mapNotNull null
        val features = window.features ?: return@mapNotNull null
        val expectTremor = expected.expectTremor ?: return@mapNotNull null
        Triple(window, expected, features.tremorDetected == expectTremor)
    }
    if (scored.isEmpty()) {
        Text("No labelled recordings yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val correct = scored.count { it.third }
    SectionTitle("Agreement: $correct of ${scored.size} recordings")
    scored.groupBy { it.second.name }.forEach { (name, rows) ->
        LabelValue(name, "${rows.count { it.third }} of ${rows.size} right")
    }
    val wrong = scored.filter { !it.third }
    if (wrong.isNotEmpty()) {
        Text("Disagreements", style = MaterialTheme.typography.labelLarge)
        wrong.forEach { (window, expected, _) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${expected.name}: analysis said " +
                            if (expected.expectTremor == true) "no tremor" else "tremor",
                        color = MaterialTheme.colorScheme.error,
                    )
                    WindowDetails(window)
                }
                // For recordings that went wrong (moved differently than labelled).
                TextButton(onClick = { onDelete(window) }) { Text("Discard") }
            }
        }
    }
}

@Composable
private fun WindowDetails(window: SampleWindow) {
    val f = window.features
    Text(
        "${Fmt.dateTime(window.startMillis)}  ·  ${tr(window.label ?: Fmt.testName(window.kind))}",
        style = MaterialTheme.typography.labelLarge,
    )
    if (f == null) {
        Text("Not analysable", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        "${Fmt.context(f.context)}  ·  tremor ${if (f.tremorDetected) "YES" else "no"}  ·  " +
            "${Fmt.pct(f.tremorFraction * 100)} of time  ·  " +
            "${Fmt.num(f.tremorRmsDegPerSec)} °/s at ${Fmt.num(f.dominantFreqHz)} Hz  ·  " +
            "peak ratio ${Fmt.num(f.peakRatio, 2)}  ·  slow ${Fmt.num(f.lowFreqRmsDegPerSec)} °/s  ·  " +
            "accel ${Fmt.num(f.accelTremorRmsMg)} mg",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(f.segmentClasses, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

@Composable
private fun SpectrumChart(psd: Psd?) {
    val lineColor = MaterialTheme.colorScheme.primary
    val bandColor = MaterialTheme.colorScheme.secondaryContainer
    if (psd == null) {
        Text("No capture to show yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val loHz = 1.0
    val hiHz = 20.0
    Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        val first = (loHz / psd.df).toInt().coerceAtLeast(1)
        val last = (hiHz / psd.df).toInt().coerceAtMost(psd.power.size - 1)
        if (last <= first) return@Canvas
        val max = (first..last).maxOf { psd.power[it] }.coerceAtLeast(1e-12)
        fun x(freq: Double) = ((freq - loHz) / (hiHz - loHz)).toFloat() * size.width

        // Shade the 3-12 Hz tremor band.
        drawRect(
            color = bandColor,
            topLeft = Offset(x(3.0), 0f),
            size = Size(x(12.0) - x(3.0), size.height),
        )
        val path = Path()
        for (k in first..last) {
            val px = x(k * psd.df)
            val py = size.height * (1f - (psd.power[k] / max).toFloat())
            if (k == first) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
    }
    Text(
        "Shaded area is the 3–12 Hz tremor band. Peak at " +
            "${Fmt.num(psd.frequencyOf(psd.peakBin(loHz, hiHz)))} Hz.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * What measuring costs the watch battery, from the charge level sent with each recording.
 * Shown per measuring pace once there is enough of each, so the paces can be compared.
 */
@Composable
private fun BatterySection(refreshKey: String?) {
    val app = app()
    val readings by produceState<List<BatteryReading>?>(null, refreshKey) {
        val since = System.currentTimeMillis() - 14 * 24 * 3_600_000L
        value = app.dao.windowsWithBattery(since).map {
            BatteryReading(it.startMillis, it.batteryPercent ?: 0, it.plannedGapSeconds ?: 0)
        }
    }
    val all = readings ?: return
    SectionTitle("Watch battery")
    val overall = BatteryUsage.of(all)
    if (overall == null) {
        Text(
            "After a few hours of measuring, this shows how fast the watch battery runs down.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Text(
        "About ${Fmt.num(overall.percentPerHour)}% an hour, so a full charge lasts about " +
            "${Fmt.num(overall.hoursPerCharge, 0)} hours.",
    )
    BatteryUsage.byPace(all).toSortedMap(reverseOrder()).forEach { (gapSeconds, use) ->
        val pace = SAMPLING_OPTIONS.firstOrNull {
            (it.intervalSeconds.first + it.intervalSeconds.last) / 2 == gapSeconds
        }?.name ?: "A check every ${gapSeconds / 60} min"
        LabelValue(pace, "${Fmt.num(use.percentPerHour)}% an hour")
    }
    Text(
        "Measured over ${Fmt.num(overall.hours, 0)} hours off the charger in the last two weeks. " +
            "This is the whole watch, not only Tremolo; comparing the paces shows what more checks cost.",
        style = MaterialTheme.typography.bodySmall,
    )
}
