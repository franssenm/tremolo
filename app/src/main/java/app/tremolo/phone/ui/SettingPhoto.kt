package app.tremolo.phone.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tremolo.shared.i18n.tr
import app.tremolo.shared.model.ProgrammerScreen
import app.tremolo.shared.model.TextBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photos of the clinician programmer or patient remote, kept with a DBS setting so the
 * parameters need not be typed. Stored inside the app only; they leave the phone solely
 * in a report or backup the user shares.
 */
object SettingPhotos {
    const val DIR = "photos"
    private const val MAX_SIDE = 2000

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** [name] is a bare file name; anything path-like is reduced to its last part. */
    fun file(context: Context, name: String): File = File(dir(context), File(name).name)

    private fun decode(context: Context, source: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bitmap = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // Cameras store sideways pictures with a note saying how to turn them.
        val degrees = resolver.openInputStream(source)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true,
        )
    }

    /** Saves a downsized copy of a picked or captured picture; returns its stored name. */
    suspend fun store(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decode(context, source, MAX_SIDE) ?: error("Not a picture")
            val name = "setting-${UUID.randomUUID()}.jpg"
            file(context, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            name
        }.getOrNull()
    }

    /**
     * Reads the stimulation values from the stored photo, on the phone itself. Null when the
     * photo could not be read or shows nothing that looks like a programmer screen.
     */
    suspend fun readValues(context: Context, name: String): ProgrammerScreen.Reading? = withContext(Dispatchers.Default) {
        runCatching {
            val stored = load(context, name, MAX_SIDE) ?: return@runCatching null
            // Contact names on a lead drawing are tiny; enlarged, far more of them are recognised.
            val bitmap = if (maxOf(stored.width, stored.height) > 1600) stored
            else Bitmap.createScaledBitmap(stored, stored.width * 2, stored.height * 2, true)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                fun box(content: String, rect: android.graphics.Rect?) = rect?.let {
                    TextBox(content, it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
                }
                val lines = text.textBlocks.flatMap { it.lines }
                ProgrammerScreen.read(
                    lines = lines.mapNotNull { box(it.text, it.boundingBox) },
                    words = lines.flatMap { it.elements }.mapNotNull { box(it.text, it.boundingBox) },
                )
            } finally {
                recognizer.close()
            }
        }.getOrNull()
    }

    fun load(context: Context, name: String, maxSide: Int = 1200): Bitmap? {
        if (name.isBlank()) return null
        val file = file(context, name).takeIf { it.isFile } ?: return null
        return runCatching { decode(context, Uri.fromFile(file), maxSide) }.getOrNull()
    }
}

/** The photo on the whole screen: pinch to zoom, drag to move, double tap to zoom in or back out. */
@Composable
private fun PhotoViewer(photo: String, onClose: () -> Unit) {
    val context = LocalContext.current
    // Loaded at full stored size, so that small print on a programmer screen stays readable.
    val bitmap by produceState<ImageBitmap?>(null, photo) {
        value = withContext(Dispatchers.IO) { SettingPhotos.load(context, photo, maxSide = 2000)?.asImageBitmap() }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    /** Keeps the picture from being dragged off the screen. */
    fun bounded(value: Offset, atScale: Float): Offset {
        val maxX = size.width * (atScale - 1f) / 2f
        val maxY = size.height * (atScale - 1f) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = bounded(offset + pan, scale)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else 3f
                        offset = Offset.Zero
                    })
                },
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = tr("Photo of the programmer screen for this setting"),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
            TextButton(
                onClick = onClose,
                // A dark backing: zoomed in, the picture runs underneath and is often light.
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .heightIn(min = TouchTarget),
            ) { Text("Close", color = Color.White) }
            if (scale == 1f) {
                Text(
                    "Pinch to zoom, or tap twice.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun FoundSide(title: String, side: ProgrammerScreen.Side) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (side.isEmpty) {
            Text("Nothing found.")
        } else {
            val parts = listOfNotNull(
                side.amplitude.takeIf { it.isNotEmpty() }?.let { "$it mA" },
                side.pulseWidth.takeIf { it.isNotEmpty() }?.let { "$it µs" },
                side.frequency.takeIf { it.isNotEmpty() }?.let { "$it Hz" },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(", "))
            if (side.contacts.isNotEmpty()) Text(tr("Contacts") + ": " + side.contacts)
        }
    }
}

/** Part of the setting editor: take, choose, view or remove the photo. */
@Composable
fun SettingPhotoSection(photo: String, onChange: (String) -> Unit, onValues: (ProgrammerScreen.Reading) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val captureUri = remember {
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "capture.jpg")
        FileProvider.getUriForFile(context, "app.tremolo.files", file)
    }
    fun keep(uri: Uri) {
        scope.launch {
            val name = SettingPhotos.store(context, uri)
            if (name == null) problem = "That picture could not be read." else onChange(name)
        }
    }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) keep(captureUri)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) keep(uri)
    }
    val bitmap by produceState<ImageBitmap?>(null, photo) {
        value = withContext(Dispatchers.IO) { SettingPhotos.load(context, photo)?.asImageBitmap() }
    }

    SectionTitle("Photo of the programmer screen")
    Text(
        "Quicker than typing: photograph the screen of your programmer or remote that shows " +
            "this setting. The photo is added to the report for your DBS programmer.",
        style = MaterialTheme.typography.bodySmall,
    )
    var enlarged by remember { mutableStateOf(false) }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = tr("Photo of the programmer screen for this setting"),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = tr("Enlarge the photo")) { enlarged = true },
        )
        Text("Tap the photo to enlarge it and zoom in.", style = MaterialTheme.typography.bodySmall)
    }
    if (enlarged) PhotoViewer(photo) { enlarged = false }

    var reading by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<ProgrammerScreen.Reading?>(null) }
    if (photo.isNotBlank()) {
        BigOutlinedButton(
            enabled = !reading,
            onClick = {
                problem = null
                reading = true
                scope.launch {
                    found = SettingPhotos.readValues(context, photo)
                    if (found == null) problem = "The values could not be read from this photo."
                    reading = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (reading) "Reading the photo…" else "Read the values from the photo") }
    }
    found?.let { values ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Found on the photo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A programmer screen names the sides of the brain; the app asks by side of the body.
                    FoundSide("Left side of your body (right side of the brain)", values.rightBrain)
                    FoundSide("Right side of your body (left side of the brain)", values.leftBrain)
                    Text(
                        "Check every value against the photo. The photo shows the setting as it " +
                            "was programmed; if you changed the amplitude with your remote since, " +
                            "type the current one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onValues(values)
                    found = null
                }) { Text("Fill in") }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text("Cancel") } },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigOutlinedButton(
            onClick = {
                problem = null
                runCatching { take.launch(captureUri) }.onFailure { problem = "No camera app was found." }
            },
            modifier = Modifier.weight(1f),
        ) { Text("Take photo") }
        BigOutlinedButton(
            onClick = {
                problem = null
                runCatching {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }.onFailure { problem = "No photo picker was found." }
            },
            modifier = Modifier.weight(1f),
        ) { Text("Choose photo") }
    }
    if (photo.isNotBlank()) {
        TextButton(onClick = { onChange("") }, modifier = Modifier.heightIn(min = TouchTarget)) {
            Text("Remove photo", color = MaterialTheme.colorScheme.error)
        }
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
