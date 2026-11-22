package app.tremolo.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tremolo.phone.report.ChartBar
import app.tremolo.phone.report.Fmt
import app.tremolo.phone.report.niceCeiling
import app.tremolo.shared.i18n.tr
import kotlin.math.ceil

/**
 * Bar chart with a readable scale. Compared with a bare row of bars it adds:
 *  - a value axis with clean round numbers, so heights can be read and not just compared;
 *  - a marker for "nothing was measured", which is not the same as "measured zero";
 *  - paler bars where there were too few measurements to lean on;
 *  - an optional average line;
 *  - tap a bar to read its exact value, and a table of the numbers for anyone who
 *    prefers reading to looking.
 */
@Composable
fun BarChart(
    bars: List<ChartBar>,
    unit: String,
    modifier: Modifier = Modifier,
    /** Draws a line at this value, labelled [referenceLabel]. */
    reference: Double? = null,
    referenceLabel: String = "average",
) {
    val values = bars.mapNotNull { it.value }
    if (values.isEmpty()) {
        Text("No measurements to show yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    var showTable by remember { mutableStateOf(false) }

    val top = niceCeiling(maxOf(values.max(), reference ?: 0.0))
    val decimals = if (top >= 20) 0 else 1
    fun format(value: Double) = Fmt.num(value, decimals) + unit
    val ticks = listOf(0.0, top / 2, top)

    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.surfaceVariant
    val mutedInk = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface

    val density = LocalDensity.current
    val labelPx = with(density) { 11.sp.toPx() }
    val gap = with(density) { 6.dp.toPx() }
    val mutedPaint = remember(mutedInk, labelPx) {
        android.graphics.Paint().apply {
            color = mutedInk.toArgb()
            textSize = labelPx
            isAntiAlias = true
        }
    }
    val inkPaint = remember(ink, labelPx) {
        android.graphics.Paint().apply {
            color = ink.toArgb()
            textSize = labelPx
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val axisWidth = ticks.maxOf { mutedPaint.measureText(Fmt.num(it, decimals)) } + gap
    val hasPale = bars.any { it.isThin }
    val hasMissing = bars.any { it.value == null }
    val highest = bars.indices.filter { bars[it].value != null }.maxByOrNull { bars[it].value!! }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .semantics {
                contentDescription = "Bar chart. " + bars.filter { it.value != null }
                    .joinToString(", ") { "${it.detail} ${format(it.value!!)}" }
            }
            .pointerInput(bars) {
                detectTapGestures { tap ->
                    val slot = (size.width - axisWidth) / bars.size
                    val index = ((tap.x - axisWidth) / slot).toInt()
                    selected = index.takeIf { it in bars.indices && it != selected }
                }
            },
    ) {
        val plotLeft = axisWidth
        val plotTop = labelPx * 1.8f
        val plotBottom = size.height - labelPx * 1.9f
        val plotHeight = plotBottom - plotTop
        val slot = (size.width - plotLeft) / bars.size
        val barWidth = minOf(slot * 0.72f, 24.dp.toPx()).coerceAtLeast(1.dp.toPx())
        val corner = minOf(4.dp.toPx(), barWidth / 2)
        fun y(value: Double) = plotBottom - (value / top).toFloat().coerceIn(0f, 1f) * plotHeight
        val canvas = drawContext.canvas.nativeCanvas

        // Recessive scale: hairlines and round numbers.
        mutedPaint.textAlign = android.graphics.Paint.Align.RIGHT
        ticks.forEach { tick ->
            drawLine(gridColor, Offset(plotLeft, y(tick)), Offset(size.width, y(tick)), 1f)
            canvas.drawText(Fmt.num(tick, decimals), plotLeft - gap, y(tick) + labelPx * 0.35f, mutedPaint)
        }

        selected?.let { index ->
            drawRect(highlight, Offset(plotLeft + index * slot, plotTop), Size(slot, plotHeight))
        }

        bars.forEachIndexed { index, bar ->
            val centre = plotLeft + index * slot + slot / 2
            val value = bar.value
            if (value == null) {
                // Nothing measured: a small neutral dot, clearly not a bar of height zero.
                drawCircle(gridColor, radius = 2.dp.toPx(), center = Offset(centre, plotBottom - 5.dp.toPx()))
            } else {
                val barTop = minOf(y(value), plotBottom - 2.dp.toPx()) // a zero still shows as a sliver
                val shape = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = centre - barWidth / 2, top = barTop,
                            right = centre + barWidth / 2, bottom = plotBottom,
                            topLeftCornerRadius = CornerRadius(corner),
                            topRightCornerRadius = CornerRadius(corner),
                        ),
                    )
                }
                drawPath(shape, barColor.copy(alpha = if (bar.isThin) 0.4f else 1f))
            }
        }

        if (reference != null) {
            drawLine(mutedInk, Offset(plotLeft, y(reference)), Offset(size.width, y(reference)), 1.dp.toPx())
        }

        // One value label only: the tapped bar, or else the highest.
        (selected ?: highest)?.let { index ->
            bars[index].value?.let { value ->
                val text = format(value)
                val half = inkPaint.measureText(text) / 2
                val x = (plotLeft + index * slot + slot / 2).coerceIn(plotLeft + half, size.width - half)
                canvas.drawText(text, x, y(value) - labelPx * 0.5f, inkPaint)
            }
        }

        // Category labels, thinned so they never run into each other.
        mutedPaint.textAlign = android.graphics.Paint.Align.CENTER
        val widest = bars.maxOf { mutedPaint.measureText(it.label) }
        val every = ceil((widest + gap) / slot).toInt().coerceAtLeast(1)
        bars.forEachIndexed { index, bar ->
            if (index % every == 0) {
                canvas.drawText(bar.label, plotLeft + index * slot + slot / 2, size.height - labelPx * 0.4f, mutedPaint)
            }
        }
    }

    val picked = selected?.let { bars[it] }
    Text(
        when {
            picked == null -> tr("Tap a bar to read it.") +
                (if (reference != null) tr(" The line marks the $referenceLabel: ${format(reference)}.") else "") +
                (if (hasPale) tr(" Pale bars rest on fewer than ${ChartBar.THIN_COUNT} measurements.") else "") +
                (if (hasMissing) tr(" A dot means nothing was measured.") else "")
            picked.value == null -> "${tr(picked.detail)}: " + tr("nothing was measured.")
            else -> "${tr(picked.detail)}: ${format(picked.value)}" +
                (picked.count?.let { "  ·  " + tr(if (it == 1) "1 measurement" else "$it measurements") } ?: "")
        },
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = { showTable = !showTable }, modifier = Modifier.heightIn(min = TouchTarget)) {
        Text(if (showTable) "Hide the numbers" else "Show the numbers")
    }
    if (showTable) {
        bars.forEach { bar ->
            LabelValue(
                bar.detail,
                (bar.value?.let { format(it) } ?: "not measured") + (bar.count?.let { "  ($it)" } ?: ""),
            )
        }
    }
}
