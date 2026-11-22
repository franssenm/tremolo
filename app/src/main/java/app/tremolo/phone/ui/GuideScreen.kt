package app.tremolo.phone.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tremolo.phone.report.ReportExporter

private val GUIDE = listOf(
    "What Tremolo does" to listOf(
        "It measures the tremor in your arm with your watch while one DBS setting is active, " +
            "so you and your clinician can see which setting really works best.",
        "The watch measures for 20 seconds at moments you cannot predict. Knowing you are being " +
            "measured changes a tremor, so not knowing gives a truer picture.",
    ),
    "Before your first trial" to listOf(
        "Wear the watch on the arm with the tremor, snug enough that it does not slide.",
        "Open Calibrate and make a few recordings of your tremor, and a few of everyday things " +
            "without tremor such as eating or typing. Then tap Calibrate. This teaches the app " +
            "what your tremor looks like.",
        "Add each DBS setting you want to test. A photo of the programmer screen is enough.",
    ),
    "Testing one setting" to listOf(
        "Switch your DBS to the setting, then start a trial. A week is a good length; a day is " +
            "too short to trust.",
        "If you only just switched, choose a settling-in time so the old setting can fade first.",
        "While the trial runs you see no results. That is on purpose. Wear the watch as much as " +
            "you can and charge it at a regular time.",
        "Log what you notice: side effects, bad moments, medication. Rate each day in the evening.",
    ),
    "Comparing settings" to listOf(
        "When trials of two settings are finished, Compare shows them side by side and says " +
            "whether the difference is larger than chance.",
        "Better still, use a plan: it tests each setting more than once in a random order, which " +
            "separates a real difference from a good or bad week.",
    ),
    "At your appointment" to listOf(
        "From Compare, share the report for your DBS programmer. It lists each setting with its " +
            "parameters, tremor, side effects and test results.",
        "Only change your stimulation in the ways your clinician has allowed.",
    ),
    "Your data" to listOf(
        "Everything stays on your phone and watch. Nothing is sent anywhere unless you share a " +
            "report or save a backup yourself.",
        "Save a backup from Settings now and then, and before changing phones.",
    ),
)

/** A short plain-language walkthrough, shown after first setup and from the home screen. */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    Screen(title = "How Tremolo works", onBack = onBack) {
        GUIDE.forEach { (title, points) ->
            SectionTitle(title)
            points.forEach { Text(it) }
        }
        Text(ReportExporter.DISCLAIMER, style = MaterialTheme.typography.bodySmall)
        BigButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Got it") }
    }
}
