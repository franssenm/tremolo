package app.tremolo.phone

import android.content.Context
import androidx.room.withTransaction
import app.tremolo.phone.data.DailyRating
import app.tremolo.phone.data.DbsSetting
import app.tremolo.phone.data.EventLogEntry
import app.tremolo.phone.data.SampleWindow
import app.tremolo.phone.data.SkipRecord
import app.tremolo.phone.data.Trial
import app.tremolo.phone.data.TremoloDatabase
import app.tremolo.shared.dsp.MovementContext
import app.tremolo.shared.dsp.TremorAnalyzer
import app.tremolo.shared.dsp.WindowFeatures
import app.tremolo.shared.protocol.SkipReason
import app.tremolo.shared.protocol.WindowKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Example mode: the app runs on a separate database filled with made-up trials, so someone
 * can see reports and comparisons before collecting a week of their own data. The user's own
 * database is not opened while it is on, the watch is left alone, and measurements arriving
 * from the watch wait until example mode is left.
 */
object Demo {
    const val DB_NAME = "tremolo-demo.db"
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /** Builds fresh example data and restarts the app on it. */
    suspend fun enter(app: TremoloApp, context: Context): Nothing {
        withContext(Dispatchers.IO) {
            app.deleteDatabase(DB_NAME)
            val db = TremoloDatabase.open(app, demo = true)
            try {
                seed(db, System.currentTimeMillis(), app.prefs.wrist)
            } finally {
                db.close()
            }
        }
        app.prefs.demoCleanupPending = true
        app.prefs.demoMode = true
        Backup.restart(context)
    }

    /** Restarts the app on the user's own data. */
    fun leave(app: TremoloApp, context: Context): Nothing {
        app.prefs.demoMode = false
        Backup.restart(context)
    }

    /** What makes one example setting differ from another. */
    private class Profile(
        val label: String,
        val amplitude: String,
        /** Chance that a check at rest finds tremor, before time of day is applied. */
        val tremorChance: Double,
        val strengthDegPerSec: Double,
        val ratedTremor: Int,
        val ratedSideEffects: Int,
        val ratedMood: Int,
    )

    private suspend fun seed(db: TremoloDatabase, now: Long, wrist: String) {
        val dao = db.dao()
        val zone = ZoneId.systemDefault()
        val random = Random(7)
        // B controls the tremor best but has more side effects: the trade-off people really face.
        val profiles = listOf(
            Profile("Program A", "2.5", 0.40, 9.0, 6, 1, 5) to (now - 17 * DAY),
            Profile("Program B", "3.2", 0.18, 5.0, 3, 4, 6) to (now - 9 * DAY - 12 * HOUR),
            Profile("Program C", "2.9", 0.27, 6.5, 4, 2, 6) to (now - 2 * DAY - 5 * HOUR),
        )
        db.withTransaction {
            var windowNumber = 0
            profiles.forEachIndexed { index, (profile, start) ->
                val settingId = dao.insertSetting(
                    DbsSetting(
                        label = profile.label,
                        amplitudeLeft = profile.amplitude,
                        pulseWidthLeft = "60",
                        frequencyLeft = "130",
                        notes = "Example",
                    ),
                )
                val trial = Trial(
                    id = "demo-$index",
                    settingId = settingId,
                    startMillis = start,
                    plannedEndMillis = start + 7 * DAY,
                    wakeStartMinute = 7 * 60,
                    wakeEndMinute = 23 * 60,
                    minIntervalSeconds = 4 * 60,
                    maxIntervalSeconds = 11 * 60,
                    wrist = wrist,
                )
                dao.insertTrial(trial)
                val until = minOf(trial.plannedEndMillis, now)
                val dayFactor = HashMap<LocalDate, Double>()

                var t = start
                while (true) {
                    t += random.nextInt(trial.minIntervalSeconds, trial.maxIntervalSeconds + 1) * 1000L
                    if (t >= until) break
                    val local = Instant.ofEpochMilli(t).atZone(zone)
                    val minute = local.hour * 60 + local.minute
                    if (minute !in trial.wakeStartMinute until trial.wakeEndMinute) continue
                    windowNumber++
                    // A daily spell on the charger, and now and then the watch is off the wrist.
                    val skip = when {
                        minute in 18 * 60 until 18 * 60 + 40 -> SkipReason.CHARGING
                        random.nextDouble() < 0.03 -> SkipReason.OFF_WRIST
                        else -> null
                    }
                    if (skip != null) {
                        dao.insertSkip(SkipRecord("demo-skip-$windowNumber", trial.id, t, skip))
                        continue
                    }
                    val goodOrBadDay = dayFactor.getOrPut(local.toLocalDate()) { 0.75 + random.nextDouble() * 0.5 }
                    // Tremor builds up through the day.
                    val timeOfDay = 0.7 + 0.6 * (local.hour - 7) / 16.0
                    val hoursAwake = (minute - trial.wakeStartMinute) / 60.0
                    dao.insertWindow(
                        SampleWindow(
                            windowId = "demo-$windowNumber",
                            trialId = trial.id,
                            kind = WindowKind.PASSIVE,
                            startMillis = t,
                            rawPath = "",
                            analysisVersion = TremorAnalyzer.VERSION,
                            features = features(random, profile, goodOrBadDay * timeOfDay),
                            batteryPercent = (100 - 3.2 * hoursAwake).toInt().coerceAtLeast(20),
                            plannedGapSeconds = (trial.minIntervalSeconds + trial.maxIntervalSeconds) / 2,
                        ),
                    )
                }

                var day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val lastDay = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
                val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                while (!day.isAfter(lastDay)) {
                    fun at(hour: Int, minute: Int) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                    fun around(value: Int) = (value + random.nextInt(-1, 2)).coerceIn(0, 10)
                    dao.saveDayKind(
                        app.tremolo.phone.data.DayKindEntry(
                            day.toEpochDay(),
                            when {
                                day.dayOfWeek.value >= 6 -> app.tremolo.shared.stats.DayKind.OFF
                                day.dayOfWeek.value % 2 == 0 -> app.tremolo.shared.stats.DayKind.HOME
                                else -> app.tremolo.shared.stats.DayKind.WORK
                            },
                        ),
                    )
                    // Today is left unrated, and so is the odd other day.
                    if (day != today && random.nextDouble() < 0.85) {
                        dao.upsertRating(
                            DailyRating(
                                epochDay = day.toEpochDay(),
                                tremor = around(profile.ratedTremor),
                                sideEffects = around(profile.ratedSideEffects),
                                mood = around(profile.ratedMood),
                            ),
                        )
                    }
                    listOf(
                        Triple("Medication", at(8, 0), 1.0),
                        Triple("Caffeine", at(10, 30), 0.6),
                        Triple("Stress", at(15, 0), 0.2),
                        Triple("Exercise", at(17, 0), 0.3),
                        Triple("Poor sleep", at(7, 30), 0.15),
                    ).forEach { (type, time, chance) ->
                        if (time in start until until && random.nextDouble() < chance) {
                            dao.insertEvent(EventLogEntry(timeMillis = time, type = type, note = "Example"))
                        }
                    }
                    day = day.plusDays(1)
                }
            }
        }
    }

    private fun features(random: Random, profile: Profile, factor: Double): WindowFeatures {
        fun gauss() = (1..6).sumOf { random.nextDouble() } - 3.0 // roughly normal, spread about 0.7
        val roll = random.nextDouble()
        val context = when {
            roll < 0.10 -> MovementContext.WALKING
            roll < 0.15 -> MovementContext.GROSS
            roll < 0.50 -> MovementContext.ACTIVE
            else -> MovementContext.REST
        }
        val judged = context == MovementContext.REST || context == MovementContext.ACTIVE
        val chance = profile.tremorChance * factor * if (context == MovementContext.ACTIVE) 0.7 else 1.0
        val detected = judged && random.nextDouble() < chance
        val strength = if (detected) profile.strengthDegPerSec * exp(gauss() * 0.45) else 0.4 + random.nextDouble() * 1.2
        val walking = context == MovementContext.WALKING
        return WindowFeatures(
            durationSeconds = 20.0,
            dominantFreqHz = if (detected) 5.2 + gauss() * 0.4 else 3.0 + random.nextDouble() * 9.0,
            tremorRmsDegPerSec = strength,
            peakRatio = if (detected) 0.6 + random.nextDouble() * 0.3 else 0.1 + random.nextDouble() * 0.2,
            lowFreqRmsDegPerSec = when (context) {
                MovementContext.REST -> 0.8 + random.nextDouble() * 2.5
                MovementContext.ACTIVE -> 8 + random.nextDouble() * 20
                MovementContext.WALKING -> 40 + random.nextDouble() * 30
                else -> 80 + random.nextDouble() * 80
            },
            accelTremorRmsMg = strength * 2,
            tremorFraction = when {
                detected -> 0.3 + random.nextDouble() * 0.7
                judged && random.nextDouble() < 0.2 -> random.nextDouble() * 0.2
                else -> 0.0
            },
            tremorRmsWhenPresent = if (detected) strength * 1.2 else 0.0,
            context = context,
            tremorDetected = detected,
            walkingFraction = if (walking) 0.9 else 0.0,
            cadenceSpm = if (walking) 104 + gauss() * 8 else 0.0,
            armSwingDegPerSec = if (walking) 70 + gauss() * 20 else 0.0,
        )
    }
}
