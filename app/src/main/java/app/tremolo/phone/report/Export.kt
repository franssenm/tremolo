package app.tremolo.phone.report

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import app.tremolo.phone.TremoloApp
import app.tremolo.phone.ui.SPIRAL_TEST
import app.tremolo.phone.ui.SettingPhotos
import app.tremolo.phone.ui.SYLLABLE_TEST
import app.tremolo.phone.ui.TAPPING_TEST
import app.tremolo.phone.ui.VOICE_TEST
import app.tremolo.phone.ui.describeComparison
import app.tremolo.shared.dsp.MovementContext
import app.tremolo.shared.i18n.tr
import app.tremolo.shared.dsp.TremorAnalyzer
import app.tremolo.shared.protocol.WindowKind
import app.tremolo.shared.stats.Fairness
import app.tremolo.shared.stats.Sample
import java.time.Instant
import java.time.ZoneId
import app.tremolo.shared.stats.TrialStatistics
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ReportExporter {
    const val DISCLAIMER =
        "Tremolo is not a medical device. Tremor strength is wrist rotation speed in the " +
            "3–12 Hz band (degrees per second). Ranges in brackets are 95% confidence intervals. " +
            "Discuss any change of stimulation with your clinician."

    suspend fun sharePdf(context: Context, report: TrialReport) {
        val file = withContext(Dispatchers.IO) { writePdf(context, report) }
        share(context, listOf(file), "application/pdf")
    }

    /** One document for the neurologist: every setting side by side, best first. */
    suspend fun shareClinicianSummary(context: Context, results: List<SettingResult>) {
        val file = withContext(Dispatchers.IO) {
            val pdf = PdfBuilder()
            pdf.text("Tremolo summary for clinician", size = 20f, bold = true)
            pdf.text(
                "Generated ${Fmt.dateTime(System.currentTimeMillis())}. Settings are listed from least " +
                    "to most time with tremor. All finished trials of a setting are combined.",
            )
            results.forEachIndexed { index, result ->
                pdf.heading("${index + 1}. ${result.label}")
                if (result.details.isNotBlank()) pdf.text(result.details)
                pdf.row(
                    "Trials / measurements",
                    "${result.trialCount} / ${result.summary.usableCount}" +
                        if (result.enoughData) "" else " (too few to rely on)",
                )
                resultRows(result.summary, result.dystonicPosturePct, irregularRestPct(result.samples))
                    .forEach { (label, value) -> pdf.row(label, value) }
                result.ratingAverages()?.let { pdf.row("Patient's own ratings, 0-10 (average)", it) }
            }
            if (results.size >= 2) {
                pdf.heading("Best setting against each other one")
                val best = results.first()
                results.drop(1).forEach { other ->
                    val comparison = TrialStatistics.compare(best.samples, other.samples)
                    pdf.text(
                        "${best.label} vs ${other.label}: " + describeComparison(
                            comparison.tremorTime, best.label, other.label, "time with tremor", " percentage points",
                        ),
                    )
                    Fairness.check(best.samples, other.samples, best.label, other.label, ZoneId.systemDefault()).let { fairness ->
                        fairness.concerns.forEach { pdf.text("Caution: $it", size = 9f) }
                        fairness.matchedNote?.let { pdf.text(it, size = 9f) }
                    }
                }
            }
            pdf.heading("How to read this")
            pdf.text(
                "Measurements are 20-second wrist recordings taken by a smartwatch at random moments " +
                    "the patient could not predict, several per waking hour. Recordings made while " +
                    "walking, in large movement or asleep are excluded. The method has not been clinically validated.",
            )
            pdf.space(10f)
            pdf.text(DISCLAIMER, size = 9f)
            File(exportDir(context), "tremolo-clinician-summary.pdf").also { pdf.writeTo(it) }
        }
        share(context, listOf(file), "application/pdf")
    }

    /**
     * The technical report for whoever programs the stimulator: parameters, tremor tables
     * with confidence intervals, tolerability, function tests and the method.
     */
    suspend fun shareProgrammerReport(context: Context, app: TremoloApp, results: List<SettingResult>) {
        val watch = app.wear.watchInfo()
        val file = withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val pdf = PdfBuilder()
            val letters = results.indices.map { ('A' + it % 26).toString() }
            val trials = results.flatMap { it.reports }.map { it.trial }

            pdf.text("Tremolo report for DBS programming", size = 20f, bold = true)
            pdf.text(
                "Generated ${Fmt.dateTime(System.currentTimeMillis())}. Data collected by the patient " +
                    "with a smartwatch and phone. Tremolo is not a medical device and the method has " +
                    "not been clinically validated; see \"Method and limits\" at the end.",
            )
            pdf.space(4f)
            pdf.row("Watch worn on", "${app.prefs.wrist} wrist")
            watch?.let {
                pdf.row(
                    "Watch",
                    it.name + (if (it.measuredGyroHz > 0) ", ${it.measuredGyroHz} Hz gyroscope" else "") +
                        if (it.hasOffBodySensor) "" else ", no off-wrist sensor",
                )
            }
            if (trials.isNotEmpty()) {
                pdf.row(
                    "Period covered",
                    "${Fmt.dateTime(trials.minOf { it.startMillis })} to ${Fmt.dateTime(trials.maxOf { it.endMillis })}",
                )
            }
            pdf.row(
                "Tremor band",
                app.prefs.personalRangeHz?.let {
                    "${Fmt.num(it.start)}–${Fmt.num(it.endInclusive)} Hz (calibrated to this patient)"
                } ?: "3–12 Hz (not personally calibrated)",
            )
            pdf.row(
                "Smallest tremor counted",
                "${Fmt.num(app.prefs.analyzerParams().minTremorRmsDegPerSec)} °/s RMS wrist rotation",
            )
            pdf.row("Analysis version", TremorAnalyzer.VERSION.toString())

            pdf.heading("1. Settings tested")
            pdf.text("Listed from least to most time with tremor. Letters are used in the tables below.", size = 9f)
            results.forEachIndexed { i, result ->
                val setting = result.reports.firstOrNull()?.setting
                pdf.space(4f)
                pdf.text("${letters[i]}. ${result.label}", bold = true)
                if (setting != null) {
                    val rows = listOf(
                        listOf("Left", setting.amplitudeLeft, setting.pulseWidthLeft, setting.frequencyLeft, setting.contactsLeft),
                        listOf("Right", setting.amplitudeRight, setting.pulseWidthRight, setting.frequencyRight, setting.contactsRight),
                    ).filter { row -> row.drop(1).any { it.isNotBlank() } }
                    if (rows.isNotEmpty()) {
                        pdf.table(
                            listOf("Side", "Amplitude (mA)", "Pulse width (µs)", "Frequency (Hz)", "Contacts"),
                            rows.map { row -> row.map { it.ifBlank { "–" } } },
                            listOf(1f, 1.5f, 1.5f, 1.5f, 2.5f),
                        )
                    } else if (setting.photoPath.isBlank()) {
                        pdf.text("No stimulation parameters were entered for this setting.", size = 9f)
                    }
                    if (setting.notes.isNotBlank()) pdf.text("Patient's note: ${setting.notes}", size = 9f)
                    SettingPhotos.load(context, setting.photoPath)?.let {
                        pdf.text("Patient's photo of the programmer screen:", size = 9f)
                        pdf.image(it)
                    }
                }
                result.reports.forEach { trialReport ->
                    val quality = dataQuality(trialReport, zone)
                    if (quality.concerns.isNotEmpty()) {
                        pdf.text(
                            "Data quality ${quality.verdict.lowercase()} for the trial of " +
                                "${Fmt.dateTime(trialReport.trial.startMillis)}: " + quality.concerns.joinToString(" "),
                            size = 9f,
                        )
                    }
                }
                pdf.text(
                    "Tested: " + result.reports.sortedBy { it.trial.startMillis }.joinToString("; ") {
                        "${Fmt.dateTime(it.trial.startMillis)} for ${Fmt.duration(it.trial.endMillis - it.trial.startMillis)}" +
                            if (it.trial.wrist.isBlank()) "" else ", ${it.trial.wrist} wrist"
                    },
                    size = 9f,
                )
            }

            pdf.heading("2. Tremor from hidden wrist measurements")
            pdf.table(
                listOf("", "n", "Time with tremor (95% CI)", "Typical strength °/s (95% CI)", "Top 10% above °/s", "Frequency Hz"),
                results.mapIndexed { i, r ->
                    val s = r.summary
                    listOf(
                        letters[i],
                        s.usableCount.toString() + if (r.enoughData) "" else " *",
                        s.tremorTimePct?.let(Fmt::estimatePct) ?: "–",
                        s.medianAmplitude?.let(Fmt::estimateAmplitude) ?: "–",
                        s.p90Amplitude?.let { Fmt.num(it) } ?: "–",
                        s.medianTremorFreqHz?.let { Fmt.num(it) } ?: "–",
                    )
                },
                listOf(0.5f, 0.9f, 2.4f, 2.6f, 1.5f, 1.3f),
            )
            if (results.any { !it.enoughData }) {
                pdf.text(
                    "* Fewer than ${TrialStatistics.MIN_SAMPLES_TO_COMPARE} measurements: too few to rely on.",
                    size = 9f,
                )
            }

            pdf.heading("3. Time with tremor by situation and time of day")
            fun cell(samples: List<Sample>): String =
                if (samples.size < 5) "–"
                else "${Fmt.pct(samples.map { it.features.tremorFraction * 100 }.average())} (${samples.size})"
            pdf.table(
                listOf("", "At rest", "Hand in use", "06–12 h", "12–18 h", "18–24 h", "00–06 h"),
                results.mapIndexed { i, r ->
                    val usable = r.samples.filter(TrialStatistics::isUsable)
                    fun hours(range: IntRange) = usable.filter {
                        Instant.ofEpochMilli(it.timeMillis).atZone(zone).hour in range
                    }
                    listOf(
                        letters[i],
                        cell(usable.filter { it.features.context == MovementContext.REST }),
                        cell(usable.filter { it.features.context == MovementContext.ACTIVE }),
                        cell(hours(6..11)), cell(hours(12..17)), cell(hours(18..23)), cell(hours(0..5)),
                    )
                },
                listOf(0.5f, 1.4f, 1.4f, 1.4f, 1.4f, 1.4f, 1.4f),
            )
            pdf.text("Percent of time with tremor, with the number of measurements in brackets. A dash means fewer than five.", size = 9f)

            pdf.heading("4. Tolerability and the patient's own view")
            fun isSideEffect(type: String) = type.contains("side effect", ignoreCase = true)
            pdf.table(
                listOf("", "Days rated", "Tremor 0–10", "Side effects 0–10", "Mood 0–10", "Side effects logged", "\"Tremor bad\" logged"),
                results.mapIndexed { i, r ->
                    val events = r.reports.flatMap { it.events }
                    fun avg(pick: (app.tremolo.phone.data.DailyRating) -> Int) =
                        if (r.ratings.isEmpty()) "–" else Fmt.num(r.ratings.map(pick).average())
                    listOf(
                        letters[i], r.ratings.size.toString(),
                        avg { it.tremor }, avg { it.sideEffects }, avg { it.mood },
                        events.count { isSideEffect(it.type) }.toString(),
                        events.count { it.type.equals("Tremor bad", ignoreCase = true) }.toString(),
                    )
                },
                listOf(0.5f, 1.1f, 1.2f, 1.5f, 1.1f, 1.5f, 1.5f),
            )
            pdf.text("Ratings: 0 = none (for mood: very good), 10 = worst.", size = 9f)
            results.forEachIndexed { i, r ->
                val notes = r.reports.flatMap { it.events }.filter { isSideEffect(it.type) && it.note.isNotBlank() }
                    .map { "${Fmt.dateTime(it.timeMillis)}: ${it.note}" } +
                    r.ratings.filter { it.note.isNotBlank() }.map { "${Fmt.date(it.epochDay)}: ${it.note}" }
                if (notes.isNotEmpty()) {
                    pdf.text("${letters[i]}. ${r.label}, in the patient's words:", size = 9f, bold = true)
                    notes.take(12).forEach { pdf.text("• $it", size = 9f) }
                }
            }

            var section = 5
            val anyTests = results.any { r -> r.reports.any { it.phoneTests.isNotEmpty() || it.tests.isNotEmpty() } }
            if (anyTests) {
                pdf.heading("${section++}. Function tests")
                fun mean(values: List<Double>, decimals: Int = 1) =
                    if (values.isEmpty()) "–" else "${Fmt.num(values.average(), decimals)} (${values.size})"
                pdf.table(
                    listOf("", "Rest test °/s", "Posture test °/s", "Taps in 15 s", "Spiral wobble %", "Pa-ta-ka per s", "Voice wavering %"),
                    results.mapIndexed { i, r ->
                        val phone = r.reports.flatMap { it.phoneTests }
                        val guided = r.reports.flatMap { it.tests }
                        fun phoneScores(kind: String) = phone.filter { it.kind == kind }.map { it.score }
                        fun guidedScores(kind: WindowKind) =
                            guided.filter { it.kind == kind }.mapNotNull { it.features?.tremorRmsDegPerSec }
                        listOf(
                            letters[i],
                            mean(guidedScores(WindowKind.TEST_REST)), mean(guidedScores(WindowKind.TEST_POSTURAL)),
                            mean(phoneScores(TAPPING_TEST), 0), mean(phoneScores(SPIRAL_TEST)),
                            mean(phoneScores(SYLLABLE_TEST)), mean(phoneScores(VOICE_TEST)),
                        )
                    },
                    listOf(0.5f, 1.3f, 1.4f, 1.2f, 1.4f, 1.4f, 1.5f),
                )
                pdf.text(
                    "Averages, with the number of tests in brackets. Rest and posture tests are 30-second " +
                        "watch recordings the patient started knowingly (tremor-band wrist rotation). The " +
                        "others are done on the phone: alternate tapping of two targets, tracing a spiral " +
                        "(fast deviation from the line as % of its radius), syllable repetition rate, and " +
                        "2–12 Hz loudness modulation of a held vowel.",
                    size = 9f,
                )
            }

            if (results.any { it.summary.walking != null }) {
                pdf.heading("${section++}. Walking")
                pdf.table(
                    listOf("", "Measurements while walking", "Pace (steps/min)", "Arm swing °/s"),
                    results.mapIndexed { i, r ->
                        val w = r.summary.walking
                        listOf(
                            letters[i], (w?.count ?: 0).toString(),
                            w?.let { Fmt.num(it.medianCadenceSpm, 0) } ?: "–",
                            w?.let { Fmt.num(it.medianArmSwingDegPerSec, 0) } ?: "–",
                        )
                    },
                    listOf(0.5f, 2.5f, 2f, 2f),
                )
                pdf.text("Medians, for the arm wearing the watch. Reduced arm swing or slower pace can accompany some settings.", size = 9f)
            }

            if (results.size >= 2) {
                pdf.heading("${section++}. Statistical comparison with setting A")
                val best = results.first()
                results.drop(1).forEachIndexed { i, other ->
                    val comparison = TrialStatistics.compare(best.samples, other.samples)
                    pdf.text(
                        "A vs ${letters[i + 1]}: " + describeComparison(
                            comparison.tremorTime, best.label, other.label, "time with tremor", " percentage points",
                        ),
                    )
                    Fairness.check(best.samples, other.samples, best.label, other.label, ZoneId.systemDefault()).let { fairness ->
                        fairness.concerns.forEach { pdf.text("Caution: $it", size = 9f) }
                        fairness.matchedNote?.let { pdf.text(it, size = 9f) }
                    }
                }
            }

            pdf.heading("Method and limits")
            listOf(
                "Sensor: wrist-worn Wear OS smartwatch, 3-axis gyroscope and accelerometer, resampled to " +
                    "50 Hz, on the arm stated above. Watch models differ in sensor noise, so compare " +
                    "settings measured with the same watch.",
                "Sampling: 20-second recordings at random intervals of roughly 8 to 22 minutes during the " +
                    "patient's waking hours. The patient cannot tell when a recording is made and sees no " +
                    "results until a trial (or a whole multi-setting plan) has ended.",
                "Tremor: a sustained (3 s or longer), narrow-band rhythm in the tremor band of the gyroscope " +
                    "signal. Strength is RMS angular velocity in that band. Rhythms explained by walking, " +
                    "harmonics of slower movement, impacts or broadband movement are rejected.",
                "Excluded: recordings while walking or during large movement, and moments when the watch " +
                    "was off the wrist or charging.",
                "Sleep: recordings during sleep are excluded, since tremor stops in sleep and they would " +
                    "otherwise dilute the result. Sleep is taken from the watch's sleep tracking when the " +
                    "patient has linked it, and otherwise from three or more consecutive recordings " +
                    "without any arm movement spanning at least 45 minutes.",
                "Uncertainty: 95% confidence intervals by bootstrap over recordings. Recordings close in " +
                    "time are not independent, so true uncertainty is somewhat larger.",
                "Limits: only one arm is measured; irregular (jerky) dystonic tremor may be under-counted; " +
                    "thresholds are engineering estimates, not clinically validated; settings were not " +
                    "necessarily tested in random order or under the same daily conditions unless the " +
                    "patient used a plan; carry-over between settings is possible.",
            ).forEach { pdf.text("• $it", size = 9f) }
            pdf.space(8f)
            pdf.text(DISCLAIMER, size = 9f)
            File(exportDir(context), "tremolo-dbs-programming-report.pdf").also { pdf.writeTo(it) }
        }
        share(context, listOf(file), "application/pdf")
    }

    suspend fun shareCsv(context: Context, report: TrialReport) {
        val files = withContext(Dispatchers.IO) { writeCsv(context, report) }
        share(context, files, "text/csv")
    }

    private fun exportDir(context: Context) = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun fileStem(report: TrialReport): String {
        val label = report.label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "trial" }
        return "tremolo-$label-${report.trial.id.take(8)}"
    }

    private fun share(context: Context, files: List<File>, mimeType: String) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "app.tremolo.files", it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.setType(mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(intent, tr("Share report")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun writeCsv(context: Context, report: TrialReport): List<File> {
        val stem = fileStem(report)
        val dir = exportDir(context)

        val checks = File(dir, "$stem-checks.csv")
        checks.bufferedWriter().use { out ->
            out.appendLine(
                "time,kind,context,tremor_detected,tremor_time_fraction,tremor_rms_deg_s," +
                    "tremor_rms_when_present_deg_s,dominant_freq_hz,peak_ratio,low_freq_rms_deg_s," +
                    "accel_tremor_rms_mg,walking_fraction,segment_classes",
            )
            for (w in report.hiddenWindows + report.tests) {
                val f = w.features
                out.appendLine(
                    listOf(
                        Fmt.iso(w.startMillis), w.kind.name, f?.context?.name ?: "",
                        f?.tremorDetected ?: "", f?.tremorFraction ?: "", f?.tremorRmsDegPerSec ?: "",
                        f?.tremorRmsWhenPresent ?: "", f?.dominantFreqHz ?: "", f?.peakRatio ?: "",
                        f?.lowFreqRmsDegPerSec ?: "", f?.accelTremorRmsMg ?: "",
                        f?.walkingFraction ?: "", f?.segmentClasses ?: "",
                    ).joinToString(",") { csv(it.toString()) },
                )
            }
        }

        val ratings = File(dir, "$stem-ratings.csv")
        ratings.bufferedWriter().use { out ->
            out.appendLine("date,tremor,side_effects,mood,note")
            for (r in report.ratings) {
                out.appendLine(
                    listOf(
                        java.time.LocalDate.ofEpochDay(r.epochDay).toString(),
                        r.tremor.toString(), r.sideEffects.toString(), r.mood.toString(), r.note,
                    ).joinToString(",") { csv(it) },
                )
            }
        }

        val events = File(dir, "$stem-events.csv")
        events.bufferedWriter().use { out ->
            out.appendLine("time,type,note")
            for (e in report.events) {
                out.appendLine(listOf(Fmt.iso(e.timeMillis), e.type, e.note).joinToString(",") { csv(it) })
            }
        }
        return listOf(checks, ratings, events)
    }

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun writePdf(context: Context, report: TrialReport): File {
        val pdf = PdfBuilder()
        pdf.text("Tremolo trial report", size = 20f, bold = true)
        pdf.text(report.label, size = 15f, bold = true)
        report.setting?.details()?.takeIf { it.isNotBlank() }?.let { pdf.text(it) }
        pdf.text("${Fmt.dateTime(report.trial.startMillis)} to ${Fmt.dateTime(report.trial.endMillis)}")
        if (!report.enoughData) {
            pdf.text("Few measurements were collected, so these numbers are uncertain.", bold = true)
        }

        pdf.heading("Results")
        report.headline().forEach { (label, value) -> pdf.row(label, value) }

        pdf.heading("Measurements")
        report.coverage().forEach { (label, value) -> pdf.row(label, value) }

        val quality = dataQuality(report)
        pdf.heading("Data quality: ${quality.verdict}")
        quality.rows.forEach { (label, value) -> pdf.row(label, value) }
        quality.concerns.forEach { pdf.text("• $it", size = 9f) }

        pdf.heading("By situation")
        report.summary.byContext.toSortedMap().forEach { (context, group) ->
            pdf.row(
                "${Fmt.context(context)} (${group.count} checks)",
                "${Fmt.pct(group.tremorTimePct)} of time, ${Fmt.num(group.medianAmplitude)} °/s",
            )
        }

        if (report.summary.byHour.isNotEmpty()) {
            pdf.heading("Time with tremor by hour of day")
            pdf.bars(hourBars(report.summary.byHour), "%")
        }
        if (report.summary.byDay.size > 1) {
            pdf.heading("Time with tremor by day")
            pdf.bars(
                dayBars(report.summary.byDay.keys.sorted(), report.summary.byDay) { it.tremorTimePct }, "%",
                reference = report.summary.tremorTimePct?.value,
            )
        }

        if (report.tests.isNotEmpty()) {
            pdf.heading("Guided tests")
            for (test in report.tests) {
                val f = test.features
                pdf.row(
                    "${Fmt.testName(test.kind)}, ${Fmt.dateTime(test.startMillis)}",
                    if (f == null) "not analysable"
                    else "${Fmt.num(f.tremorRmsDegPerSec)} °/s at ${Fmt.num(f.dominantFreqHz)} Hz, " +
                        "${Fmt.pct(f.tremorFraction * 100)} of time",
                )
            }
        }
        if (report.phoneTests.isNotEmpty()) {
            pdf.heading("Hand tests on the phone")
            for (test in report.phoneTests) {
                pdf.row("${test.kind}, ${Fmt.dateTime(test.timeMillis)}", test.detail)
            }
        }
        if (report.ratings.isNotEmpty()) {
            pdf.heading("Daily ratings (0 = none, 10 = worst)")
            for (r in report.ratings) {
                pdf.row(
                    Fmt.date(r.epochDay),
                    "tremor ${r.tremor}, side effects ${r.sideEffects}, mood ${r.mood}" +
                        if (r.note.isBlank()) "" else " — ${r.note}",
                )
            }
        }
        if (report.events.isNotEmpty()) {
            pdf.heading("Logged events")
            for (e in report.events) {
                pdf.row(Fmt.dateTime(e.timeMillis), if (e.note.isBlank()) e.type else "${e.type}: ${e.note}")
            }
        }

        pdf.space(14f)
        pdf.text(DISCLAIMER, size = 9f)

        val file = File(exportDir(context), "${fileStem(report)}.pdf")
        pdf.writeTo(file)
        return file
    }
}

/** Minimal top-to-bottom A4 text layout on top of [PdfDocument]. */
private class PdfBuilder {
    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 48f
    private val document = PdfDocument()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var page: PdfDocument.Page? = null
    private var y = 0f
    private var pageNumber = 0

    private val canvas: Canvas get() = page!!.canvas
    private val contentWidth get() = pageWidth - 2 * margin

    private fun ensureSpace(height: Float) {
        if (page != null && y + height <= pageHeight - margin) return
        page?.let { document.finishPage(it) }
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        y = margin
    }

    private fun style(size: Float, bold: Boolean) {
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.color = 0xFF000000.toInt()
        paint.textAlign = Paint.Align.LEFT
    }

    private fun wrap(text: String, width: Float): List<String> {
        val lines = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            var count = paint.breakText(rest, true, width, null)
            if (count < rest.length) {
                val lastSpace = rest.lastIndexOf(' ', count)
                if (lastSpace > 0) count = lastSpace + 1
            }
            lines += rest.substring(0, count).trimEnd()
            rest = rest.substring(count)
        }
        return lines
    }

    fun text(text: String, size: Float = 11f, bold: Boolean = false) {
        style(size, bold)
        for (line in wrap(tr(text), contentWidth)) {
            ensureSpace(size * 1.5f)
            y += size * 1.2f
            canvas.drawText(line, margin, y, paint)
            y += size * 0.3f
        }
    }

    fun heading(text: String) {
        space(12f)
        // Keep a heading together with at least the first line that follows it.
        ensureSpace(50f)
        text(text, size = 13f, bold = true)
    }

    fun row(label: String, value: String) {
        val size = 11f
        style(size, false)
        val labelWidth = contentWidth * 0.48f
        val valueX = margin + contentWidth * 0.5f
        val labelLines = wrap(tr(label), labelWidth)
        val valueLines = wrap(tr(value), contentWidth * 0.5f)
        val lineCount = maxOf(labelLines.size, valueLines.size)
        ensureSpace(lineCount * size * 1.5f)
        style(size, false)
        for (i in 0 until lineCount) {
            y += size * 1.2f
            labelLines.getOrNull(i)?.let { canvas.drawText(it, margin, y, paint) }
            valueLines.getOrNull(i)?.let { canvas.drawText(it, valueX, y, paint) }
            y += size * 0.3f
        }
    }

    fun space(height: Float) {
        y += height
    }

    /** A picture, scaled to fit within [maxWidth] by [maxHeight] points. */
    fun image(bitmap: android.graphics.Bitmap, maxWidth: Float = 300f, maxHeight: Float = 260f) {
        val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        ensureSpace(height + 8f)
        y += 4f
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(margin, y, margin + width, y + height), Paint(Paint.FILTER_BITMAP_FLAG))
        y += height + 4f
    }

    /** A simple table: bold header row, wrapped cells, a thin rule under every row. */
    fun table(headers: List<String>, rows: List<List<String>>, weights: List<Float>) {
        val size = 9f
        val widths = weights.map { contentWidth * it / weights.sum() }
        fun draw(cells: List<String>, bold: Boolean) {
            style(size, bold)
            val wrapped = cells.mapIndexed { i, cell -> wrap(tr(cell).ifEmpty { " " }, widths[i] - 6f) }
            val lineCount = wrapped.maxOf { it.size }
            ensureSpace(lineCount * size * 1.4f + 6f)
            style(size, bold)
            for (line in 0 until lineCount) {
                y += size * 1.2f
                var x = margin
                wrapped.forEachIndexed { i, lines ->
                    lines.getOrNull(line)?.let { canvas.drawText(it, x, y, paint) }
                    x += widths[i]
                }
                y += size * 0.2f
            }
            y += 3f
            paint.color = 0xFFBBBBBB.toInt()
            paint.strokeWidth = 0.5f
            canvas.drawLine(margin, y, margin + contentWidth, y, paint)
            y += 2f
        }
        draw(headers, true)
        rows.forEach { draw(it, false) }
    }

    /** Bar chart with a scale; see the on-screen BarChart for what the marks mean. */
    fun bars(values: List<ChartBar>, unit: String, reference: Double? = null) {
        val measured = values.mapNotNull { it.value }
        if (measured.isEmpty()) return
        val chartHeight = 100f
        ensureSpace(chartHeight + 56f)
        val top = niceCeiling(maxOf(measured.max(), reference ?: 0.0))
        val decimals = if (top >= 20) 0 else 1
        val axis = 30f
        val left = margin + axis
        val plotTop = y + 14f
        val base = plotTop + chartHeight
        val slot = (contentWidth - axis) / values.size
        val barWidth = minOf(slot * 0.7f, 18f)
        fun yOf(value: Double) = base - (value / top).toFloat().coerceIn(0f, 1f) * chartHeight

        listOf(0.0, top / 2, top).forEach { tick ->
            paint.color = 0xFFCCCCCC.toInt()
            paint.strokeWidth = 0.5f
            canvas.drawLine(left, yOf(tick), margin + contentWidth, yOf(tick), paint)
            style(8f, false)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(Fmt.num(tick, decimals), left - 4f, yOf(tick) + 3f, paint)
        }
        val widest = values.maxOf { style(8f, false); paint.measureText(it.label) }
        val labelEvery = Math.ceil(((widest + 4f) / slot).toDouble()).toInt().coerceAtLeast(1)
        val highest = values.filter { it.value != null }.maxByOrNull { it.value!! }
        values.forEachIndexed { i, bar ->
            val centre = left + i * slot + slot / 2
            val value = bar.value
            if (value == null) {
                paint.color = 0xFFAAAAAA.toInt()
                canvas.drawCircle(centre, base - 4f, 1.5f, paint)
            } else {
                paint.color = if (bar.isThin) 0xFFA9C5D5.toInt() else 0xFF2A6F97.toInt()
                canvas.drawRect(centre - barWidth / 2, minOf(yOf(value), base - 1f), centre + barWidth / 2, base, paint)
                if (bar === highest) {
                    style(8f, true)
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(Fmt.num(value, decimals) + unit, centre, yOf(value) - 3f, paint)
                }
            }
            if (i % labelEvery == 0) {
                style(8f, false)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(bar.label, centre, base + 10f, paint)
            }
        }
        if (reference != null) {
            paint.color = 0xFF555555.toInt()
            paint.strokeWidth = 0.8f
            canvas.drawLine(left, yOf(reference), margin + contentWidth, yOf(reference), paint)
            style(8f, false)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(tr("whole trial") + " ${Fmt.num(reference, decimals)}$unit", margin + contentWidth, yOf(reference) - 3f, paint)
        }
        y = base + 16f
        val notes = listOfNotNull(
            "Pale bars rest on fewer than ${ChartBar.THIN_COUNT} measurements.".takeIf { values.any { it.isThin } },
            "A dot means nothing was measured.".takeIf { values.any { it.value == null } },
        )
        if (notes.isNotEmpty()) text(notes.joinToString(" "), size = 8f)
    }

    fun writeTo(file: File) {
        page?.let { document.finishPage(it) }
        file.outputStream().use { document.writeTo(it) }
        document.close()
    }
}
