package app.tremolo.wear

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import app.tremolo.shared.protocol.BACKGROUND_TRIAL_ID
import app.tremolo.shared.protocol.TrialConfig
import app.tremolo.shared.protocol.WindowKind
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Re-read on every return to the app, as the wearer may just have allowed it in Settings. */
    private val exactAllowed = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The answer needs no handling: without it the app works, just without its notice.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent { MaterialTheme { WatchScreen(exactAllowed.value) } }
    }

    override fun onResume() {
        super.onResume()
        exactAllowed.value = CaptureScheduler.exactAllowed(this)
        // Opening the app is always allowed to start the service, so this also recovers
        // from a background start that the system refused.
        if (trialStore.activeConfig() != null) {
            CaptureService.start(this, CaptureService.ACTION_SYNC)
        }
        (application as WearApp).appScope.launch {
            TrialSync.refresh(applicationContext)
            // Tells the phone at once when the alarm permission was just given or taken away.
            WatchInfoPublisher.publish(applicationContext)
        }
    }
}

@Composable
private fun WatchScreen(exactAllowed: Boolean) {
    val context = LocalContext.current
    val store = context.trialStore
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(store.activeConfig()) }
    var message by remember { mutableStateOf<String?>(null) }
    val watchProblem = remember {
        WatchInfoPublisher.describe(context).takeIf { !it.usable }?.problems()?.firstOrNull()
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            scope.launch {
                message = if (logEvent(context, "Note", text)) "Logged note: $text" else "Could not log. Try again."
            }
        }
    }

    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            config = store.activeConfig()
        }
        store.registerListener(listener)
        onDispose { store.unregisterListener(listener) }
    }

    @Composable
    fun Caption(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "Tremolo",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        // Deliberately no sample counts or times here: the wearer must not know when
        // the watch is measuring.
        item { Caption(statusText(config)) }
        // On a watch that cannot measure, say so plainly instead of collecting nothing.
        watchProblem?.let { text -> item { Caption(text) } }
        message?.let { text -> item { Caption(text) } }
        if (!exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item { Caption("The watch may skip half the checks until Tremolo is allowed to set alarms.") }
            item {
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:" + context.packageName),
                                ),
                            )
                        }.onFailure {
                            message = "Open Settings, Apps, Special app access, Alarms and reminders, and allow Tremolo."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow alarms") }
            }
        }

        item { Caption("Log now") }
        QUICK_EVENTS.forEach { type ->
            item {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            message = if (logEvent(context, type)) {
                                context.getSystemService(Vibrator::class.java)
                                    ?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                                "Logged: $type"
                            } else {
                                "Could not log. Try again."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(type) }
            }
        }

        item {
            FilledTonalButton(
                onClick = {
                    runCatching {
                        voice.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            ),
                        )
                    }.onFailure { message = "Voice input is not available on this watch." }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Voice note") }
        }

        item { Caption("Guided tests") }
        item {
            Button(
                onClick = {
                    CaptureService.startTest(context, WindowKind.TEST_REST, 30, 5)
                    message = "Rest your hand in your lap. Recording runs from the buzz to the double buzz."
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Rest test") }
        }
        item {
            Button(
                onClick = {
                    CaptureService.startTest(context, WindowKind.TEST_POSTURAL, 30, 5)
                    message = "Hold both arms straight out. Recording runs from the buzz to the double buzz."
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Posture test") }
        }
        if (BuildConfig.DEBUG) {
            item {
                Button(
                    onClick = { CaptureService.start(context, CaptureService.ACTION_DEBUG_CAPTURE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Debug: capture now") }
            }
        }
    }
}

private fun statusText(config: TrialConfig?): String = when {
    config == null -> "Not monitoring. Start a trial from the phone app."
    config.trialId == BACKGROUND_TRIAL_ID -> "Everyday monitoring is on."
    else -> "Trial running until " +
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(config.endMillis))
}
