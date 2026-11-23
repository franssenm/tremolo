package app.tremolo.phone.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import app.tremolo.shared.dsp.WindowFeatures
import app.tremolo.shared.protocol.ProtocolJson
import app.tremolo.shared.protocol.SkipReason
import app.tremolo.shared.protocol.TrialConfig
import app.tremolo.shared.protocol.WindowKind
import app.tremolo.phone.Demo
import kotlinx.coroutines.flow.Flow

/** A DBS program / parameter set the patient wants to evaluate. Values are free text. */
@Entity(tableName = "settings")
data class DbsSetting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val amplitudeLeft: String = "",
    val amplitudeRight: String = "",
    val pulseWidthLeft: String = "",
    val pulseWidthRight: String = "",
    val frequencyLeft: String = "",
    val frequencyRight: String = "",
    val contactsLeft: String = "",
    val contactsRight: String = "",
    val notes: String = "",
    /** File name of a photo of the programmer screen, in the app's photos folder; "" for none. */
    val photoPath: String = "",
) {
    /** One-line description of the parameters that were filled in. */
    fun details(): String {
        fun side(name: String, amp: String, pw: String, freq: String, contacts: String): String? {
            val parts = listOfNotNull(
                amp.takeIf { it.isNotBlank() }?.let { "$it mA" },
                pw.takeIf { it.isNotBlank() }?.let { "$it µs" },
                freq.takeIf { it.isNotBlank() }?.let { "$it Hz" },
                contacts.takeIf { it.isNotBlank() }?.let { "contacts $it" },
            )
            return if (parts.isEmpty()) null else "$name: ${parts.joinToString(", ")}"
        }
        return listOfNotNull(
            side("Left", amplitudeLeft, pulseWidthLeft, frequencyLeft, contactsLeft),
            side("Right", amplitudeRight, pulseWidthRight, frequencyRight, contactsRight),
            notes.takeIf { it.isNotBlank() },
        ).joinToString("  ·  ")
    }
}

@Entity(tableName = "trials")
data class Trial(
    @PrimaryKey val id: String,
    val settingId: Long,
    val startMillis: Long,
    val plannedEndMillis: Long,
    /** Set when the trial was ended early. */
    val endedMillis: Long? = null,
    val wakeStartMinute: Int,
    val wakeEndMinute: Int,
    val minIntervalSeconds: Int,
    val maxIntervalSeconds: Int,
    /** The crossover plan this trial is a block of, if any. */
    val planId: Long? = null,
    /** Letter standing in for the setting's name while a plan is still running. */
    val blindCode: String? = null,
    /** "left" or "right": the wrist the watch was on; "" for trials from before this was recorded. */
    val wrist: String = "",
) {
    val endMillis: Long get() = endedMillis ?: plannedEndMillis

    /** True during the settling-in gap after a switch, before measuring begins. */
    fun inWashout(now: Long = System.currentTimeMillis()): Boolean = isRunning(now) && now < startMillis

    fun isRunning(now: Long = System.currentTimeMillis()): Boolean =
        endedMillis == null && now < plannedEndMillis

    fun toConfig() = TrialConfig(
        trialId = id,
        startMillis = startMillis,
        endMillis = plannedEndMillis,
        wakeStartMinute = wakeStartMinute,
        wakeEndMinute = wakeEndMinute,
        minIntervalSeconds = minIntervalSeconds,
        maxIntervalSeconds = maxIntervalSeconds,
    )
}

/**
 * One capture received from the watch. [features] is stored as JSON and is null if the
 * capture could not be analysed; [analysisVersion] says which analyser produced it, so
 * captures can be re-analysed from the raw file when the analyser improves.
 */
@Entity(tableName = "windows", indices = [Index("trialId")])
data class SampleWindow(
    @PrimaryKey val windowId: String,
    val trialId: String?,
    val kind: WindowKind,
    val startMillis: Long,
    val rawPath: String,
    /** What the wearer said they were doing (labelled debug recordings only). */
    val label: String? = null,
    val analysisVersion: Int = 0,
    val features: WindowFeatures? = null,
    /** Watch charge at the time, 0-100; null when the watch did not report it. */
    val batteryPercent: Int? = null,
    /** Average planned gap between hidden checks at the time, in seconds. */
    val plannedGapSeconds: Int? = null,
)

/** A scheduled capture the watch skipped (off the wrist or on the charger). */
@Entity(tableName = "skips", indices = [Index("trialId")])
data class SkipRecord(
    @PrimaryKey val eventId: String,
    val trialId: String,
    val timeMillis: Long,
    val reason: SkipReason,
)

/** The patient's own end-of-day scores, 0 (none / fine) to 10 (worst). */
@Entity(tableName = "ratings")
data class DailyRating(
    @PrimaryKey val epochDay: Long,
    val tremor: Int,
    val sideEffects: Int,
    val mood: Int,
    val note: String = "",
)

@Entity(tableName = "events", indices = [Index(value = ["externalId"], unique = true)])
data class EventLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    val type: String,
    val note: String = "",
    /** Id given by the watch for events logged there, so a re-sent event is stored once. */
    val externalId: String? = null,
)

/** A medication with the times of day it is taken, stored as minutes after midnight. */
@Entity(tableName = "medications")
data class Medication(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dose: String = "",
    /** Comma-separated minutes after midnight, e.g. "480,780,1200". */
    val times: String = "",
    val remind: Boolean = true,
    /** "medication", or "reminder" for things like recharging the implant. */
    @ColumnInfo(defaultValue = "medication") val kind: String = "medication",
) {
    val isReminder: Boolean get() = kind == "reminder"

    fun timeList(): List<Int> = times.split(',').mapNotNull { it.trim().toIntOrNull() }.sorted()

    fun title(): String = if (dose.isBlank()) name else "$name $dose"
}

/**
 * A crossover plan: several settings tested in blocks, in random order and repeated, so a
 * real difference can be told from a good or bad week. Results stay locked until it ends.
 */
@Entity(tableName = "plans")
data class Plan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Setting ids in the order the blocks are run, comma-separated. */
    val sequence: String,
    /** Setting ids in the order of their code letters (A, B, ...), comma-separated. */
    val codeOrder: String,
    val blockHours: Int,
    /** Settling-in time after each switch during which nothing is measured. */
    val washoutHours: Int,
    /** Hide setting names from the patient; a helper does the switching. */
    val blinded: Boolean,
    /** Number of blocks started so far. */
    val position: Int = 0,
    val createdMillis: Long,
    val finishedMillis: Long? = null,
) {
    fun sequenceList(): List<Long> = sequence.split(',').mapNotNull { it.toLongOrNull() }

    fun codeOf(settingId: Long): String {
        val index = codeOrder.split(',').mapNotNull { it.toLongOrNull() }.indexOf(settingId)
        return if (index < 0) "?" else ('A' + index).toString()
    }

    val isFinished: Boolean get() = finishedMillis != null
}

/** A test done on the phone screen, such as finger tapping or drawing a spiral. */
@Entity(tableName = "phone_tests")
data class PhoneTest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    val kind: String,
    /** Headline number: taps for tapping, wobble % for the spiral. */
    val score: Double,
    val detail: String = "",
)

@Dao
interface TremoloDao {
    @Insert
    suspend fun insertPlan(plan: Plan): Long

    @Query("SELECT * FROM plans ORDER BY createdMillis DESC")
    fun plans(): Flow<List<Plan>>

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun plan(id: Long): Plan?

    @Query("SELECT * FROM plans WHERE finishedMillis IS NULL ORDER BY createdMillis DESC LIMIT 1")
    suspend fun activePlan(): Plan?

    @Query("UPDATE plans SET position = :position WHERE id = :id")
    suspend fun setPlanPosition(id: Long, position: Int)

    @Query("UPDATE plans SET finishedMillis = :at WHERE id = :id")
    suspend fun finishPlan(id: Long, at: Long)

    @Insert
    suspend fun insertPhoneTest(test: PhoneTest)

    @Query("SELECT * FROM phone_tests ORDER BY timeMillis DESC LIMIT 30")
    fun recentPhoneTests(): Flow<List<PhoneTest>>

    @Query("SELECT * FROM phone_tests WHERE timeMillis BETWEEN :from AND :to ORDER BY timeMillis")
    suspend fun phoneTestsBetween(from: Long, to: Long): List<PhoneTest>

    @Query("UPDATE windows SET analysisVersion = 0")
    suspend fun markAllForReanalysis()

    @Query("SELECT * FROM windows WHERE label IS NOT NULL")
    suspend fun labelledWindowList(): List<SampleWindow>

    @Query("SELECT MAX(startMillis) FROM windows WHERE trialId = :trialId")
    suspend fun lastWindowTime(trialId: String): Long?

    @Query("SELECT * FROM windows WHERE batteryPercent IS NOT NULL AND startMillis >= :since ORDER BY startMillis")
    suspend fun windowsWithBattery(since: Long): List<SampleWindow>

    @Query("SELECT COUNT(*) FROM skips WHERE trialId = :trialId")
    suspend fun skipCount(trialId: String): Int

    @Query("SELECT COUNT(*) FROM windows WHERE trialId = :trialId AND startMillis >= :since")
    suspend fun windowCountSince(trialId: String, since: Long): Int

    @Query("SELECT COUNT(*) FROM skips WHERE trialId = :trialId AND reason = 'OFF_WRIST' AND timeMillis >= :since")
    suspend fun offWristCountSince(trialId: String, since: Long): Int

    @Insert
    suspend fun insertSetting(setting: DbsSetting): Long

    @Update
    suspend fun updateSetting(setting: DbsSetting)

    @Query("SELECT * FROM settings ORDER BY label")
    fun settings(): Flow<List<DbsSetting>>

    @Query("SELECT * FROM settings WHERE id = :id")
    suspend fun setting(id: Long): DbsSetting?

    @Insert
    suspend fun insertTrial(trial: Trial)

    @Query("UPDATE trials SET endedMillis = :at WHERE id = :id")
    suspend fun endTrial(id: String, at: Long)

    @Query("DELETE FROM trials WHERE id = :id")
    suspend fun deleteTrial(id: String)

    /** Recordings the user labelled teach the analysis, so they outlive the trial they came from. */
    @Query("UPDATE windows SET trialId = NULL WHERE trialId = :trialId AND label IS NOT NULL")
    suspend fun detachLabelledWindows(trialId: String)

    @Query("DELETE FROM windows WHERE trialId = :trialId")
    suspend fun deleteWindowsFor(trialId: String)

    @Query("DELETE FROM skips WHERE trialId = :trialId")
    suspend fun deleteSkipsFor(trialId: String)

    @Query("SELECT * FROM trials ORDER BY startMillis DESC")
    fun trials(): Flow<List<Trial>>

    @Query("SELECT * FROM trials ORDER BY startMillis DESC")
    suspend fun trialList(): List<Trial>

    @Query("SELECT * FROM trials WHERE id = :id")
    suspend fun trial(id: String): Trial?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWindow(window: SampleWindow)

    @Query("SELECT EXISTS(SELECT 1 FROM windows WHERE windowId = :id)")
    suspend fun hasWindow(id: String): Boolean

    @Query("SELECT * FROM windows WHERE trialId = :trialId ORDER BY startMillis")
    suspend fun windowsFor(trialId: String): List<SampleWindow>

    @Query("SELECT * FROM windows ORDER BY startMillis DESC LIMIT :limit")
    fun recentWindows(limit: Int): Flow<List<SampleWindow>>

    @Query("SELECT * FROM windows WHERE label IS NOT NULL ORDER BY startMillis DESC")
    fun labelledWindows(): Flow<List<SampleWindow>>

    @Query("SELECT * FROM windows WHERE analysisVersion < :version")
    suspend fun windowsAnalysedBefore(version: Int): List<SampleWindow>

    @Query("UPDATE windows SET features = :features, analysisVersion = :version WHERE windowId = :id")
    suspend fun setAnalysis(id: String, features: WindowFeatures?, version: Int)

    @Query("DELETE FROM windows WHERE windowId = :id")
    suspend fun deleteWindow(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkip(skip: SkipRecord)

    @Query("SELECT * FROM skips WHERE trialId = :trialId")
    suspend fun skipsFor(trialId: String): List<SkipRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRating(rating: DailyRating)

    @Query("SELECT * FROM ratings WHERE epochDay = :epochDay")
    suspend fun rating(epochDay: Long): DailyRating?

    @Query("SELECT * FROM ratings WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay")
    suspend fun ratingsBetween(fromDay: Long, toDay: Long): List<DailyRating>

    /** Ignores an event whose externalId is already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: EventLogEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMedication(medication: Medication): Long

    @Delete
    suspend fun deleteMedication(medication: Medication)

    @Query("SELECT * FROM medications ORDER BY name")
    fun medications(): Flow<List<Medication>>

    @Query("SELECT * FROM medications ORDER BY name")
    suspend fun medicationList(): List<Medication>

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun medication(id: Long): Medication?

    @Query("SELECT * FROM windows WHERE kind = 'PASSIVE' AND startMillis BETWEEN :from AND :to ORDER BY startMillis")
    suspend fun hiddenWindowsBetween(from: Long, to: Long): List<SampleWindow>

    @Delete
    suspend fun deleteEvent(event: EventLogEntry)

    @Query("SELECT * FROM events ORDER BY timeMillis DESC LIMIT 100")
    fun recentEvents(): Flow<List<EventLogEntry>>

    @Query("SELECT * FROM events WHERE timeMillis BETWEEN :from AND :to ORDER BY timeMillis")
    suspend fun eventsBetween(from: Long, to: Long): List<EventLogEntry>
}

@Database(
    entities = [
        DbsSetting::class, Trial::class, SampleWindow::class,
        SkipRecord::class, DailyRating::class, EventLogEntry::class, Medication::class,
        Plan::class, PhoneTest::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TremoloDatabase : RoomDatabase() {
    abstract fun dao(): TremoloDao

    companion object {
        /** [demo] opens the separate database with example data instead of the user's own. */
        fun open(context: Context, demo: Boolean = false): TremoloDatabase =
            Room.databaseBuilder(context, TremoloDatabase::class.java, if (demo) Demo.DB_NAME else "tremolo.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()

        /**
         * Features move from one column each to a single JSON column, and captures gain a
         * label and an analysis version. Old features are dropped rather than converted:
         * version 0 makes the app re-analyse every capture from its raw file.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE windows_new (windowId TEXT NOT NULL, trialId TEXT, " +
                        "kind TEXT NOT NULL, startMillis INTEGER NOT NULL, rawPath TEXT NOT NULL, " +
                        "label TEXT, analysisVersion INTEGER NOT NULL, features TEXT, " +
                        "PRIMARY KEY(windowId))",
                )
                db.execSQL(
                    "INSERT INTO windows_new (windowId, trialId, kind, startMillis, rawPath, " +
                        "label, analysisVersion, features) " +
                        "SELECT windowId, trialId, kind, startMillis, rawPath, NULL, 0, NULL FROM windows",
                )
                db.execSQL("DROP TABLE windows")
                db.execSQL("ALTER TABLE windows_new RENAME TO windows")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_windows_trialId ON windows (trialId)")
            }
        }

        /** Adds watch-logged events (deduplicated by id) and the medication list. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN externalId TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_events_externalId ON events (externalId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS medications (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, dose TEXT NOT NULL, times TEXT NOT NULL, remind INTEGER NOT NULL)",
                )
            }
        }

        /** Adds crossover plans, phone-screen tests and non-medication reminders. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE windows ADD COLUMN batteryPercent INTEGER")
                db.execSQL("ALTER TABLE windows ADD COLUMN plannedGapSeconds INTEGER")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trials ADD COLUMN wrist TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN photoPath TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trials ADD COLUMN planId INTEGER")
                db.execSQL("ALTER TABLE trials ADD COLUMN blindCode TEXT")
                db.execSQL("ALTER TABLE medications ADD COLUMN kind TEXT NOT NULL DEFAULT 'medication'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS plans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sequence TEXT NOT NULL, codeOrder TEXT NOT NULL, blockHours INTEGER NOT NULL, " +
                        "washoutHours INTEGER NOT NULL, blinded INTEGER NOT NULL, position INTEGER NOT NULL, " +
                        "createdMillis INTEGER NOT NULL, finishedMillis INTEGER)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS phone_tests (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "timeMillis INTEGER NOT NULL, kind TEXT NOT NULL, score REAL NOT NULL, detail TEXT NOT NULL)",
                )
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun featuresToJson(features: WindowFeatures?): String? =
        features?.let { ProtocolJson.encodeToString(WindowFeatures.serializer(), it) }

    @TypeConverter
    fun jsonToFeatures(json: String?): WindowFeatures? =
        json?.let {
            runCatching { ProtocolJson.decodeFromString(WindowFeatures.serializer(), it) }.getOrNull()
        }
}
