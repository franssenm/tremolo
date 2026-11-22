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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    fun load(context: Context, name: String, maxSide: Int = 1200): Bitmap? {
        if (name.isBlank()) return null
        val file = file(context, name).takeIf { it.isFile } ?: return null
        return runCatching { decode(context, Uri.fromFile(file), maxSide) }.getOrNull()
    }
}

/** Part of the setting editor: take, choose, view or remove the photo. */
@Composable
fun SettingPhotoSection(photo: String, onChange: (String) -> Unit) {
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
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Photo of the programmer screen for this setting",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
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
