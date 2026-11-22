package app.tremolo.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tremolo.phone.data.PhoneTest
import app.tremolo.phone.report.ChartBar
import app.tremolo.phone.report.Fmt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val TAPPING_TEST = "Finger tapping"
const val SPIRAL_TEST = "Spiral drawing"

/** Menu and history for the tests done on the phone screen. */
@Composable
fun HandTestsScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val app = app()
    val tests by app.dao.recentPhoneTests().collectAsStateWithLifecycle(emptyList())

    Screen(title = "Hand and speech tests", onBack = onBack) {
        Text(
            "Short tests of hand control and speech. Settings can trade tremor against control, " +
                "speed and clear speech, and these put a number on that. Do the hand tests with " +
                "the hand that has the tremor. Tests done during a trial appear in its report.",
        )
        BigButton(onClick = { navigate("tapping") }, modifier = Modifier.fillMaxWidth()) {
            Text("Finger tapping (15 seconds)")
        }
        BigButton(onClick = { navigate("spiral") }, modifier = Modifier.fillMaxWidth()) {
            Text("Draw a spiral")
        }
        BigButton(onClick = { navigate("speech") }, modifier = Modifier.fillMaxWidth()) {
            Text("Speech test")
        }
        listOf(
            TAPPING_TEST to " taps", SPIRAL_TEST to "% wobble",
            SYLLABLE_TEST to " per s", VOICE_TEST to "% wavering",
        ).forEach { (kind, unit) ->
            val history = tests.filter { it.kind == kind }.sortedBy { it.timeMillis }.takeLast(12)
            if (history.size >= 2) {
                SectionTitle("$kind over time")
                BarChart(
                    history.map {
                        val day = java.time.Instant.ofEpochMilli(it.timeMillis)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        ChartBar("${day.dayOfMonth}/${day.monthValue}", it.score, detail = Fmt.dateTime(it.timeMillis))
                    },
                    unit = unit,
                )
            }
        }
        if (tests.isNotEmpty()) SectionTitle("Recent results")
        tests.forEach { test ->
            LabelValue("${test.kind}\n${Fmt.dateTime(test.timeMillis)}", test.detail)
            HorizontalDivider()
        }
    }
}

/** Alternate between two targets as fast as possible; counts taps and rhythm steadiness. */
@Composable
fun TappingTestScreen(onBack: () -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    val durationMillis = 15_000L
    var startedAt by remember { mutableLongStateOf(0L) }
    var remaining by remember { mutableLongStateOf(durationMillis) }
    var nextSide by remember { mutableIntStateOf(0) }
    val tapTimes = remember { mutableStateListOf<Long>() }
    var result by remember { mutableStateOf<String?>(null) }

    fun finish() {
        val taps = tapTimes.size
        val intervals = tapTimes.zipWithNext { a, b -> (b - a).toDouble() }
        val variation = if (intervals.size >= 3) {
            val mean = intervals.average()
            100.0 * sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size) / mean
        } else {
            0.0
        }
        val detail = "$taps taps in 15 s, rhythm variation ${Fmt.num(variation, 0)}%"
        result = detail
        scope.launch {
            app.dao.insertPhoneTest(
                PhoneTest(timeMillis = System.currentTimeMillis(), kind = TAPPING_TEST, score = taps.toDouble(), detail = detail),
            )
        }
    }

    LaunchedEffect(startedAt) {
        if (startedAt == 0L) return@LaunchedEffect
        while (true) {
            remaining = durationMillis - (System.currentTimeMillis() - startedAt)
            if (remaining <= 0) break
            delay(100)
        }
        remaining = 0
        finish()
    }

    fun tap(side: Int) {
        if (result != null || side != nextSide) return
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        tapTimes += System.currentTimeMillis()
        nextSide = 1 - side
    }

    Screen(title = TAPPING_TEST, onBack = onBack) {
        val done = result
        if (done != null) {
            Text("Done", style = MaterialTheme.typography.headlineSmall)
            Text(done, style = MaterialTheme.typography.titleMedium)
            BigButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
            return@Screen
        }
        Text(
            "With one finger of the hand that has the tremor, tap the two buttons in turn, as " +
                "fast and evenly as you can. The clock starts on your first tap.",
        )
        Text(
            if (startedAt == 0L) "15 seconds" else "${(remaining + 999) / 1000} s left   ·   ${tapTimes.size} taps",
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            for (side in 0..1) {
                val modifier = Modifier.weight(1f).height(180.dp)
                // The button to hit next is the brightly filled one.
                if (side == nextSide) {
                    Button(onClick = { tap(side) }, modifier = modifier) { Text("Tap") }
                } else {
                    FilledTonalButton(onClick = { tap(side) }, modifier = modifier) { Text("") }
                }
            }
        }
    }
}

private const val SPIRAL_TURNS = 3.0

/**
 * Scores a traced spiral against the guide. Returns (wobble %, off-the-line %) of the
 * spiral's radius, or null if too little was drawn. Wobble is the fast, shaky part of the
 * error; off-the-line is the total error including slow drift.
 */
private fun scoreSpiral(points: List<Offset>, size: IntSize): Pair<Double, Double>? {
    if (points.size < 50) return null
    val cx = size.width / 2.0
    val cy = size.height / 2.0
    val maxRadius = minOf(size.width, size.height) / 2.0 * 0.9
    var theta = 0.0
    val errors = ArrayList<Double>(points.size)
    points.forEachIndexed { index, point ->
        val dx = point.x - cx
        val dy = point.y - cy
        val angle = (atan2(dy, dx) + 2 * PI) % (2 * PI)
        if (index == 0) {
            theta = angle
        } else {
            var step = angle - theta % (2 * PI)
            if (step > PI) step -= 2 * PI
            if (step < -PI) step += 2 * PI
            theta += step
        }
        errors += hypot(dx, dy) - maxRadius * theta / (2 * PI * SPIRAL_TURNS)
    }
    if (theta < 2 * PI * 1.5) return null // less than a turn and a half
    val half = 10
    val wobble = errors.indices.map { i ->
        val from = maxOf(0, i - half)
        val to = minOf(errors.size - 1, i + half)
        errors[i] - errors.subList(from, to + 1).average()
    }
    fun rms(values: List<Double>) = sqrt(values.sumOf { it * it } / values.size)
    return 100.0 * rms(wobble) / maxRadius to 100.0 * rms(errors) / maxRadius
}

/** Trace a spiral with a finger; tremor shows as wobble around the line. */
@Composable
fun SpiralTestScreen(onBack: () -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    val points = remember { mutableStateListOf<Offset>() }
    var drawStart by remember { mutableLongStateOf(0L) }
    var result by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val inkColor = MaterialTheme.colorScheme.primary

    Screen(title = SPIRAL_TEST, onBack = onBack) {
        Text(
            "With a finger of the hand that has the tremor, trace the spiral from the centre " +
                "outwards in one go, without resting your hand on the phone.",
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            if (result == null) {
                                points.clear()
                                points += start
                                problem = null
                                drawStart = System.currentTimeMillis()
                            }
                        },
                        onDrag = { change, _ -> if (result == null) points += change.position },
                        onDragEnd = {
                            if (result != null) return@detectDragGestures
                            val score = scoreSpiral(points.toList(), size)
                            if (score == null) {
                                problem = "That was too short to score. Trace at least two full turns."
                            } else {
                                val seconds = (System.currentTimeMillis() - drawStart) / 1000.0
                                val detail = "wobble ${Fmt.num(score.first)}%, off the line " +
                                    "${Fmt.num(score.second)}%, ${Fmt.num(seconds, 0)} s"
                                result = detail
                                scope.launch {
                                    app.dao.insertPhoneTest(
                                        PhoneTest(
                                            timeMillis = System.currentTimeMillis(), kind = SPIRAL_TEST,
                                            score = score.first, detail = detail,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                },
        ) {
            val centre = Offset(size.width / 2, size.height / 2)
            val maxRadius = minOf(size.width, size.height) / 2 * 0.9f
            val guide = Path()
            val steps = 360
            for (i in 0..steps) {
                val theta = 2 * PI * SPIRAL_TURNS * i / steps
                val radius = maxRadius * i / steps
                val x = centre.x + (radius * cos(theta)).toFloat()
                val y = centre.y + (radius * sin(theta)).toFloat()
                if (i == 0) guide.moveTo(x, y) else guide.lineTo(x, y)
            }
            drawPath(guide, guideColor, style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(inkColor, radius = 8.dp.toPx(), center = centre)
            if (points.size > 1) {
                val ink = Path()
                points.forEachIndexed { i, p -> if (i == 0) ink.moveTo(p.x, p.y) else ink.lineTo(p.x, p.y) }
                drawPath(ink, inkColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { detail ->
            Text(detail, style = MaterialTheme.typography.titleMedium)
            Text(
                "Wobble is the shaky part of the line; lower is steadier.",
                style = MaterialTheme.typography.bodySmall,
            )
            BigButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
        }
    }
}
