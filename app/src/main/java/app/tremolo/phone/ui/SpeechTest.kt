package app.tremolo.phone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.tremolo.phone.data.PhoneTest
import app.tremolo.phone.report.Fmt
import app.tremolo.shared.dsp.SpeechAnalyzer
import app.tremolo.shared.i18n.tr
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val SYLLABLE_TEST = "Speech: pa-ta-ka"
const val VOICE_TEST = "Speech: held note"

private const val SPEECH_SAMPLE_RATE = 16_000
private const val SPEECH_SECONDS = 8

/** Records the microphone into memory. The sound is analysed and then thrown away. */
@SuppressLint("MissingPermission") // only called once the permission is granted
private suspend fun recordVoice(onSecondsLeft: (Int) -> Unit): ShortArray? = withContext(Dispatchers.IO) {
    val minBuffer = AudioRecord.getMinBufferSize(
        SPEECH_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBuffer <= 0) return@withContext null
    // The "voice recognition" source skips automatic volume levelling, which would hide
    // exactly the loudness wavering this test looks for.
    val recorder = runCatching {
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SPEECH_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SPEECH_SAMPLE_RATE),
        )
    }.getOrNull() ?: return@withContext null
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        return@withContext null
    }
    val audio = ShortArray(SPEECH_SAMPLE_RATE * SPEECH_SECONDS)
    var filled = 0
    try {
        recorder.startRecording()
        while (filled < audio.size) {
            coroutineContext.ensureActive()
            val read = recorder.read(audio, filled, minOf(1600, audio.size - filled))
            if (read <= 0) break
            filled += read
            onSecondsLeft(SPEECH_SECONDS - filled / SPEECH_SAMPLE_RATE)
        }
    } finally {
        runCatching { recorder.stop() }
        recorder.release()
    }
    if (filled < audio.size) null else audio
}

/**
 * Two short voice tasks. Slurred or slowed speech is a common side effect of stimulation,
 * and dystonia can make the voice waver, so these give each setting a number for both.
 */
@Composable
fun SpeechTestScreen(onBack: () -> Unit) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    var recording by remember { mutableStateOf<String?>(null) }
    var secondsLeft by remember { mutableIntStateOf(SPEECH_SECONDS) }
    var problem by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<String>() }

    fun run(kind: String) {
        if (recording != null) return
        problem = null
        recording = kind
        secondsLeft = SPEECH_SECONDS
        scope.launch {
            val audio = recordVoice { secondsLeft = it }
            recording = null
            if (audio == null) {
                problem = "The microphone could not be used. Close other apps that are recording and try again."
                return@launch
            }
            val outcome: Pair<Double, String>? = withContext(Dispatchers.Default) {
                if (kind == SYLLABLE_TEST) {
                    SpeechAnalyzer.syllables(audio, SPEECH_SAMPLE_RATE)?.let {
                        it.ratePerSecond to "${Fmt.num(it.ratePerSecond)} syllables a second, " +
                            "rhythm variation ${Fmt.num(it.rhythmVariationPct, 0)}%"
                    }
                } else {
                    SpeechAnalyzer.sustained(audio, SPEECH_SAMPLE_RATE)?.let {
                        it.loudnessTremorPct to "held ${Fmt.num(it.seconds)} s, loudness wavering " +
                            "${Fmt.num(it.loudnessTremorPct)}% at ${Fmt.num(it.tremorHz)} Hz"
                    }
                }
            }
            if (outcome == null) {
                problem = "That was too quiet or too short to measure. Hold the phone about a hand's " +
                    "width from your mouth, in a quiet room, and try again."
            } else {
                results += "${tr(kind)}: ${tr(outcome.second)}"
                app.dao.insertPhoneTest(
                    PhoneTest(
                        timeMillis = System.currentTimeMillis(), kind = kind,
                        score = outcome.first, detail = outcome.second,
                    ),
                )
            }
        }
    }

    Screen(title = "Speech test", onBack = onBack) {
        Text(
            "Two short voice tasks of $SPEECH_SECONDS seconds each. Do them in a quiet room with the " +
                "phone about a hand's width from your mouth, the same way every time.",
        )
        Text(
            "Only loudness over time is measured. The sound itself is never saved and never " +
                "leaves the phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!granted) {
            BigButton(
                onClick = { askPermission.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow the microphone") }
            return@Screen
        }

        val active = recording
        if (active != null) {
            Text("Recording… $secondsLeft s left", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (active == SYLLABLE_TEST) "Keep going: pa-ta-ka-pa-ta-ka…" else "Keep the note going: aaah…",
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            SectionTitle("1. Pa-ta-ka")
            Text("Tap the button, then say \"pa-ta-ka\" over and over, as fast and as clearly as you can, until it stops.")
            BigButton(onClick = { run(SYLLABLE_TEST) }, modifier = Modifier.fillMaxWidth()) {
                Text("Start pa-ta-ka")
            }
            SectionTitle("2. Held note")
            Text("Tap the button, take a breath and say \"aaah\" at a steady, comfortable loudness for as long as you can.")
            BigButton(onClick = { run(VOICE_TEST) }, modifier = Modifier.fillMaxWidth()) {
                Text("Start held note")
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (results.isNotEmpty()) {
            HorizontalDivider()
            results.forEach { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(
                "Faster and more even pa-ta-ka is better. Less loudness wavering means a steadier voice.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
