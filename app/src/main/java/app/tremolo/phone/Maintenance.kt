package app.tremolo.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.tremolo.shared.dsp.AnalyzerParams
import app.tremolo.shared.dsp.TremorAnalyzer
import app.tremolo.shared.model.RawWindowCodec
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Learns the user's own tremor frequency and dystonic wrist posture from labelled recordings. */
object Calibration {
    const val TREMOR_LABEL_PREFIX = "Tremor"
    const val DYSTONIC_POSTURE_LABEL = "Posture: dystonic pulling"
    private const val MIN_TREMOR_RECORDINGS = 3
    private const val MARGIN_HZ = 1.0

    /** Returns a message describing what was learned (or why nothing could be). */
    suspend fun calibrate(app: TremoloApp): String = withContext(Dispatchers.IO) {
        val labelled = app.dao.labelledWindowList()
        val messages = ArrayList<String>()

        // Frequencies are measured without any previous calibration, so it cannot feed itself.
        val uncalibrated = AnalyzerParams()
        val frequencies = labelled.filter { it.label?.startsWith(TREMOR_LABEL_PREFIX) == true }
            .mapNotNull { window ->
                runCatching {
                    File(window.rawPath).inputStream().use { TremorAnalyzer.analyze(RawWindowCodec.read(it), uncalibrated) }
                }.getOrNull()
            }
            .filter { it.tremorDetected }
            .map { it.dominantFreqHz }
        if (frequencies.size >= MIN_TREMOR_RECORDINGS) {
            val range = (frequencies.min() - MARGIN_HZ).coerceAtLeast(uncalibrated.tremorLoHz)..
                (frequencies.max() + MARGIN_HZ).coerceAtMost(uncalibrated.tremorHiHz)
            app.prefs.personalRangeHz = range
            messages += "Your tremor range is set to %.1f–%.1f Hz, from %d recordings.".format(
                range.start, range.endInclusive, frequencies.size,
            )
        } else {
            messages += "Tremor range not set: it needs at least $MIN_TREMOR_RECORDINGS recordings " +
                "labelled as tremor in which tremor was found (${frequencies.size} so far)."
        }

        val postures = labelled.filter { it.label == DYSTONIC_POSTURE_LABEL }.mapNotNull { it.features }
            .map { doubleArrayOf(it.gravityX, it.gravityY, it.gravityZ) }
            .filter { v -> v.any { it != 0.0 } }
        if (postures.isNotEmpty()) {
            app.prefs.dystonicPosture = DoubleArray(3) { axis -> postures.map { it[axis] }.average() }
            messages += "Dystonic posture set from ${postures.size} recording(s)."
        } else {
            messages += "Dystonic posture not set: record a \"$DYSTONIC_POSTURE_LABEL\" sample first."
        }

        app.dao.markAllForReanalysis()
        app.ingestor.reanalyseOutdated()
        messages.joinToString("\n")
    }

    /** Changes how faint a tremor still counts, then re-analyses everything stored. */
    suspend fun setSensitivity(app: TremoloApp, level: Int) {
        app.prefs.sensitivity = level
        app.dao.markAllForReanalysis()
        app.ingestor.reanalyseOutdated()
    }

    suspend fun clear(app: TremoloApp) {
        app.prefs.personalRangeHz = null
        app.prefs.dystonicPosture = null
        app.dao.markAllForReanalysis()
        app.ingestor.reanalyseOutdated()
    }
}

/** Saves and restores everything the app holds as one zip file the user chooses. */
object Backup {
    private const val DB_NAME = "tremolo.db"
    private const val PREFS_ENTRY = "prefs.xml"
    private const val RAW_PREFIX = "raw/"
    private const val PHOTO_DIR = "photos"

    suspend fun export(app: TremoloApp, target: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Fold pending writes into the main database file so the copy is complete.
            app.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            val output = app.contentResolver.openOutputStream(target) ?: error("Cannot open the backup file")
            ZipOutputStream(output).use { zip ->
                fun add(name: String, file: File) {
                    if (!file.isFile) return
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                add(DB_NAME, app.getDatabasePath(DB_NAME))
                add(PREFS_ENTRY, prefsFile(app))
                File(app.filesDir, "raw").listFiles()?.forEach { add(RAW_PREFIX + it.name, it) }
                File(app.filesDir, PHOTO_DIR).listFiles()?.forEach { add("$PHOTO_DIR/" + it.name, it) }
            }
        }.isSuccess
    }

    /**
     * Replaces all current data with the backup. Returns false and changes nothing if the
     * file is not a Tremolo backup. On success the caller must restart the app.
     */
    suspend fun restore(app: TremoloApp, source: Uri): Boolean = withContext(Dispatchers.IO) {
        val staging = File(app.cacheDir, "restore").apply {
            deleteRecursively()
            mkdirs()
        }
        val unpacked = runCatching {
            val input = app.contentResolver.openInputStream(source) ?: error("Cannot open the backup file")
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Only known names, and never a path taken from the archive.
                    val target = when {
                        entry.name == DB_NAME -> File(staging, DB_NAME)
                        entry.name == PREFS_ENTRY -> File(staging, PREFS_ENTRY)
                        entry.name.startsWith(RAW_PREFIX) && !entry.isDirectory ->
                            File(File(staging, "raw").apply { mkdirs() }, File(entry.name).name)
                        entry.name.startsWith("$PHOTO_DIR/") && !entry.isDirectory ->
                            File(File(staging, PHOTO_DIR).apply { mkdirs() }, File(entry.name).name)
                        else -> null
                    }
                    target?.outputStream()?.use { zip.copyTo(it) }
                }
            }
            val db = File(staging, DB_NAME)
            db.isFile && db.inputStream().use { it.readNBytesCompat(15) }.contentEquals("SQLite format 3".toByteArray())
        }.getOrDefault(false)
        if (!unpacked) {
            staging.deleteRecursively()
            return@withContext false
        }

        app.db.close()
        val dbFile = app.getDatabasePath(DB_NAME)
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(staging, DB_NAME).copyTo(dbFile, overwrite = true)
        File(staging, PREFS_ENTRY).takeIf { it.isFile }?.copyTo(prefsFile(app), overwrite = true)
        val rawDir = File(app.filesDir, "raw").apply { mkdirs() }
        File(staging, "raw").listFiles()?.forEach { it.copyTo(File(rawDir, it.name), overwrite = true) }
        val photoDir = File(app.filesDir, PHOTO_DIR).apply { mkdirs() }
        File(staging, PHOTO_DIR).listFiles()?.forEach { it.copyTo(File(photoDir, it.name), overwrite = true) }
        staging.deleteRecursively()
        true
    }

    /** Restarts the app so it reopens the restored database and settings. */
    fun restart(context: Context): Nothing {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        exitProcess(0)
    }

    private fun prefsFile(app: TremoloApp) = File(app.applicationInfo.dataDir, "shared_prefs/prefs.xml")

    private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }
}
