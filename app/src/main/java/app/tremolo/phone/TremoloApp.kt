package app.tremolo.phone

import android.util.Log
import app.tremolo.shared.i18n.Translations
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.tremolo.phone.data.Plan
import app.tremolo.phone.data.Trial
import app.tremolo.phone.data.TremoloDao
import app.tremolo.phone.data.TremoloDatabase
import app.tremolo.phone.sync.WearBridge
import app.tremolo.phone.sync.WindowIngestor
import app.tremolo.shared.dsp.AnalyzerParams
import app.tremolo.shared.protocol.BACKGROUND_TRIAL_ID
import app.tremolo.shared.protocol.TrialConfig
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TremoloApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: TremoloDatabase by lazy { TremoloDatabase.open(this, prefs.demoMode) }
    val dao: TremoloDao by lazy { db.dao() }
    val prefs: Prefs by lazy { Prefs(this) }
    val wear: WearBridge by lazy { WearBridge(this) }
    val ingestor: WindowIngestor by lazy { WindowIngestor(this, dao) { prefs.analyzerParams() } }
    val trials: TrialManager by lazy { TrialManager(this) }

    override fun onCreate() {
        super.onCreate()
        // In debug builds, texts that still lack a translation are listed in the log.
        if (BuildConfig.DEBUG) Translations.onMiss = { Log.d("TremoloI18n", it.replace("\n", "\\n")) }
        Reminders.createChannel(this)
        // On example data nothing is synced: the watch and the user's own data are left alone.
        if (prefs.demoMode) return
        appScope.launch {
            runCatching { ingestor.reanalyseOutdated() }
            trials.resync()
            // Trials started before this check existed, or after an app update, get it too.
            trials.running()?.let { trial ->
                Reminders.scheduleWatchCheck(this@TremoloApp)
                if (prefs.demoCleanupPending) Reminders.scheduleTrialEnd(this@TremoloApp, trial.plannedEndMillis)
            }
            if (prefs.demoCleanupPending) {
                // Reminders that came due while the example was showing found nothing to do.
                dao.medicationList().forEach { Reminders.scheduleMedication(this@TremoloApp, it) }
                prefs.demoCleanupPending = false
            }
            ingestor.catchUp()
        }
    }
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(value) = sp.edit().putBoolean("onboarded", value).apply()

    /** "left" or "right": the wrist the watch is worn on. */
    var wrist: String
        get() = sp.getString("wrist", "left") ?: "left"
        set(value) = sp.edit().putString("wrist", value).apply()

    var wakeStartMinute: Int
        get() = sp.getInt("wakeStart", 7 * 60)
        set(value) = sp.edit().putInt("wakeStart", value).apply()

    var wakeEndMinute: Int
        get() = sp.getInt("wakeEnd", 23 * 60)
        set(value) = sp.edit().putInt("wakeEnd", value).apply()

    /** Measure around the clock instead of only in the waking hours. */
    var measureAtNight: Boolean
        get() = sp.getBoolean("measureAtNight", false)
        set(value) = sp.edit().putBoolean("measureAtNight", value).apply()

    /** Keep measuring every day outside trials, to build the tremor timeline. */
    var monitorAlways: Boolean
        get() = sp.getBoolean("monitorAlways", false)
        set(value) = sp.edit().putBoolean("monitorAlways", value).apply()

    /** Time of the end-of-day rating reminder, minutes after midnight. */
    var ratingMinute: Int
        get() = sp.getInt("ratingMinute", 21 * 60)
        set(value) = sp.edit().putInt("ratingMinute", value).apply()

    /** The user's own tremor frequency range from calibration; null when not calibrated. */
    var personalRangeHz: ClosedFloatingPointRange<Double>?
        get() {
            val lo = sp.getFloat("personalLo", -1f)
            val hi = sp.getFloat("personalHi", -1f)
            return if (lo > 0 && hi > lo) lo.toDouble()..hi.toDouble() else null
        }
        set(value) = sp.edit()
            .putFloat("personalLo", value?.start?.toFloat() ?: -1f)
            .putFloat("personalHi", value?.endInclusive?.toFloat() ?: -1f)
            .apply()

    /** Gravity direction recorded while the wrist was in its dystonic posture, if calibrated. */
    var dystonicPosture: DoubleArray?
        get() = sp.getString("dystonicPosture", null)
            ?.split(',')?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 3 }?.toDoubleArray()
        set(value) = sp.edit().putString("dystonicPosture", value?.joinToString(",")).apply()

    /** "dystonia", "parkinsons", "essential" or "other"; only used to pick default lengths. */
    var condition: String
        get() = sp.getString("condition", "dystonia") ?: "dystonia"
        set(value) = sp.edit().putString("condition", value).apply()

    /** Dystonia responds to a setting change over days, tremor in other conditions within hours. */
    fun defaultTrialDays(): Int = if (condition == "dystonia") 7 else 3

    fun defaultSettlingHours(): Int = if (condition == "dystonia") 24 else 2

    /** Day of the next DBS appointment as an epoch day; 0 when none is set. */
    var appointmentDay: Long
        get() = sp.getLong("appointmentDay", 0L)
        set(value) = sp.edit().putLong("appointmentDay", value).apply()

    /**
     * The app is showing made-up example data from a separate database. Written at once,
     * because the app restarts straight after it changes.
     */
    var demoMode: Boolean
        get() = sp.getBoolean("demoMode", false)
        set(value) { sp.edit().putBoolean("demoMode", value).commit() }

    /** Set on entering example mode; reminders are put right again the first start after it. */
    var demoCleanupPending: Boolean
        get() = sp.getBoolean("demoCleanup", false)
        set(value) { sp.edit().putBoolean("demoCleanup", value).commit() }

    /** When the "check your watch" notification was last shown; 0 when it is not showing. */
    var watchWarningMillis: Long
        get() = sp.getLong("watchWarning", 0L)
        set(value) = sp.edit().putLong("watchWarning", value).apply()

    /** How often the watch measures: 0 = light, 1 = balanced, 2 = detailed. */
    var samplingDensity: Int
        get() = sp.getInt("samplingDensity", 1)
        set(value) = sp.edit().putInt("samplingDensity", value).apply()

    /** Gap between hidden checks in seconds, drawn at random from this range. */
    fun captureInterval(): IntRange = SAMPLING_OPTIONS[samplingDensity.coerceIn(0, 2)].intervalSeconds

    /** How faint a tremor still counts: 0 = only strong, 1 = normal, 2 = also faint. */
    var sensitivity: Int
        get() = sp.getInt("sensitivity", 1)
        set(value) = sp.edit().putInt("sensitivity", value).apply()

    fun analyzerParams(sensitivity: Int = this.sensitivity): AnalyzerParams {
        val range = personalRangeHz
        return AnalyzerParams(
            personalLoHz = range?.start,
            personalHiHz = range?.endInclusive,
            minTremorRmsDegPerSec = when (sensitivity) {
                0 -> 4.0
                2 -> 1.0
                else -> 2.0
            },
        )
    }

    /** Wake range sent to the watch: equal start and end means "always". */
    fun wakeRange(): Pair<Int, Int> = if (measureAtNight) 0 to 0 else wakeStartMinute to wakeEndMinute
}

class SamplingOption(val name: String, val perHour: String, val intervalSeconds: IntRange)

/** The measuring rhythms on offer; the index is what [Prefs.samplingDensity] stores. */
val SAMPLING_OPTIONS = listOf(
    SamplingOption("Light", "about 4 an hour", 8 * 60..22 * 60),
    SamplingOption("Balanced", "about 8 an hour", 4 * 60..11 * 60),
    SamplingOption("Detailed", "about 15 an hour", 2 * 60..6 * 60),
)

class TrialManager(private val app: TremoloApp) {

    /**
     * Starts a trial. [intervalSeconds] overrides the default capture spacing (debug trials);
     * [startDelayMillis] postpones measuring, used as a settling-in gap after a switch.
     */
    suspend fun start(
        settingId: Long,
        durationMillis: Long,
        intervalSeconds: IntRange = app.prefs.captureInterval(),
        ignoreWakeHours: Boolean = false,
        startDelayMillis: Long = 0L,
        plan: Plan? = null,
    ): Trial {
        val start = System.currentTimeMillis() + startDelayMillis
        val (wakeStart, wakeEnd) = if (ignoreWakeHours) 0 to 0 else app.prefs.wakeRange()
        val trial = Trial(
            id = UUID.randomUUID().toString(),
            settingId = settingId,
            startMillis = start,
            plannedEndMillis = start + durationMillis,
            wakeStartMinute = wakeStart,
            wakeEndMinute = wakeEnd,
            minIntervalSeconds = intervalSeconds.first,
            maxIntervalSeconds = intervalSeconds.last,
            planId = plan?.id,
            blindCode = plan?.codeOf(settingId),
            wrist = app.prefs.wrist,
        )
        app.dao.insertTrial(trial)
        // A trial started on example data stays inside the example.
        if (app.prefs.demoMode) return trial
        app.wear.pushTrial(trial.toConfig())
        Reminders.scheduleTrialEnd(app, trial.plannedEndMillis)
        Reminders.scheduleWatchCheck(app)
        Reminders.scheduleDailyRating(app)
        return trial
    }

    suspend fun endEarly(trial: Trial) {
        app.dao.endTrial(trial.id, System.currentTimeMillis())
        if (app.prefs.demoMode) return
        resync()
        Reminders.cancelTrialEnd(app)
        Reminders.cancelWatchCheck(app)
    }

    suspend fun running(): Trial? = app.dao.trialList().firstOrNull { it.isRunning() }

    /**
     * Tells the watch what to sample for: the running trial, else everyday monitoring if the
     * user turned it on, else nothing. Safe to call any time; it also lets a watch that
     * missed an update catch up.
     */
    suspend fun resync() {
        if (app.prefs.demoMode) return
        app.wear.pushTrial(running()?.toConfig() ?: backgroundConfig())
    }

    private fun backgroundConfig(): TrialConfig? {
        if (!app.prefs.monitorAlways) return null
        val (wakeStart, wakeEnd) = app.prefs.wakeRange()
        return TrialConfig(
            trialId = BACKGROUND_TRIAL_ID,
            startMillis = 0L,
            endMillis = Long.MAX_VALUE / 2,
            wakeStartMinute = wakeStart,
            wakeEndMinute = wakeEnd,
            minIntervalSeconds = app.prefs.captureInterval().first,
            maxIntervalSeconds = app.prefs.captureInterval().last,
        )
    }

    // --- Crossover plans -----------------------------------------------------------------

    /**
     * Builds the block order: [repeats] rounds, each a fresh shuffle of the settings, never
     * putting the same setting twice in a row across a round boundary.
     */
    suspend fun createPlan(
        settingIds: List<Long>,
        repeats: Int,
        blockHours: Int,
        washoutHours: Int,
        blinded: Boolean,
    ): Plan {
        val sequence = ArrayList<Long>()
        repeat(repeats) {
            var round = settingIds.shuffled()
            while (settingIds.size > 1 && sequence.isNotEmpty() && round.first() == sequence.last()) {
                round = settingIds.shuffled()
            }
            sequence += round
        }
        val plan = Plan(
            sequence = sequence.joinToString(","),
            codeOrder = settingIds.shuffled().joinToString(","),
            blockHours = blockHours,
            washoutHours = washoutHours,
            blinded = blinded,
            createdMillis = System.currentTimeMillis(),
        )
        return plan.copy(id = app.dao.insertPlan(plan))
    }

    /** The setting the next block of [plan] uses, or null when all blocks have been run. */
    fun nextSettingId(plan: Plan): Long? = plan.sequenceList().getOrNull(plan.position)

    /** Call once the DBS has been switched to the next setting of the plan. */
    suspend fun startNextBlock(plan: Plan): Trial? {
        val settingId = nextSettingId(plan) ?: return null
        if (running() != null) return null
        app.dao.setPlanPosition(plan.id, plan.position + 1)
        return start(
            settingId = settingId,
            durationMillis = plan.blockHours * 3_600_000L,
            startDelayMillis = plan.washoutHours * 3_600_000L,
            plan = plan,
        )
    }

    /** Marks a plan finished once every block has been run, which unlocks its results. */
    suspend fun settlePlan() {
        val plan = app.dao.activePlan() ?: return
        if (nextSettingId(plan) == null && running() == null) {
            app.dao.finishPlan(plan.id, System.currentTimeMillis())
        }
    }

    /** Stops a plan part-way; the blocks already run become ordinary, visible trials. */
    suspend fun abandonPlan(plan: Plan) {
        running()?.takeIf { it.planId == plan.id }?.let { endEarly(it) }
        app.dao.finishPlan(plan.id, System.currentTimeMillis())
    }

    /** When results must stay hidden from: the running trial or the whole unfinished plan. */
    suspend fun hiddenSince(): Long? {
        val trialStart = running()?.let { minOf(it.startMillis, System.currentTimeMillis()) }
        val planStart = app.dao.activePlan()?.createdMillis
        return listOfNotNull(trialStart, planStart).minOrNull()
    }
}
