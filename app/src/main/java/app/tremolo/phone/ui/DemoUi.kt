package app.tremolo.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tremolo.phone.Demo
import kotlinx.coroutines.launch

/** Shown on the home screen for as long as the app runs on example data. */
@Composable
fun DemoBanner() {
    val app = app()
    val context = LocalContext.current
    if (!app.prefs.demoMode) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Example data", style = MaterialTheme.typography.titleMedium)
            Text(
                "Everything you see now is made up, to show what Tremolo looks like after a few " +
                    "trials. Your own data is safe and untouched, and your watch keeps measuring.",
            )
            BigButton(onClick = { Demo.leave(app, context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Back to my own data")
            }
        }
    }
}

/** The way into example mode. Does nothing visible while it is already on. */
@Composable
fun DemoEntry() {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    if (app.prefs.demoMode) return
    Text(
        "Curious what the results look like? Open an example with made-up trials. Your own " +
            "data stays as it is, and you can go back at any time.",
        style = MaterialTheme.typography.bodySmall,
    )
    BigOutlinedButton(
        onClick = {
            if (!loading) {
                loading = true
                scope.launch { Demo.enter(app, context) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (loading) "Preparing the example…" else "Show an example") }
}
