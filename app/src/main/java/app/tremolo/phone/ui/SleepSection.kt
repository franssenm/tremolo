package app.tremolo.phone.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import app.tremolo.phone.SleepImport

/** Settings block for linking sleep data from Health Connect. */
@Composable
fun SleepSection() {
    val context = LocalContext.current
    val available = remember { SleepImport.available(context) }
    var granted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { granted = SleepImport.granted(context) }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        granted = it.containsAll(SleepImport.PERMISSIONS)
    }

    SectionTitle("Sleep")
    Text(
        "Tremor is often worse after a short night. If your watch's sleep tracking is shared with " +
            "Health Connect (for a Pixel Watch: Fitbit app, then Health Connect in its settings), " +
            "the timeline can compare tremor after shorter and longer nights. Tremolo only reads " +
            "sleep times, and they stay on this phone.",
        style = MaterialTheme.typography.bodySmall,
    )
    when {
        !available -> Text("Health Connect is not available on this phone.")
        granted -> Text("Sleep data is linked.")
        else -> BigOutlinedButton(
            onClick = { runCatching { ask.launch(SleepImport.PERMISSIONS) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Link sleep data") }
    }
}
