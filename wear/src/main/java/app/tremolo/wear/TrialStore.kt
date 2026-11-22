package app.tremolo.wear

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.tremolo.shared.protocol.ProtocolJson
import app.tremolo.shared.protocol.TrialConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WearApp : Application() {
    val trialStore: TrialStore by lazy { TrialStore(this) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Runs on every process start: app opened, boot, or a message from the phone.
        appScope.launch { TrialSync.refresh(this@WearApp) }
        appScope.launch { WatchInfoPublisher.publish(this@WearApp) }
    }
}

val Context.trialStore: TrialStore
    get() = (applicationContext as WearApp).trialStore

/** Persists the trial the watch is sampling for, so it survives restarts. */
class TrialStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("trial", Context.MODE_PRIVATE)

    var config: TrialConfig?
        get() = prefs.getString(KEY_CONFIG, null)?.let {
            runCatching { ProtocolJson.decodeFromString(TrialConfig.serializer(), it) }.getOrNull()
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_CONFIG)
                else putString(KEY_CONFIG, ProtocolJson.encodeToString(TrialConfig.serializer(), value))
            }.apply()
        }

    /** The trial to sample for right now, or null if there is none or it has ended. */
    fun activeConfig(now: Long = System.currentTimeMillis()): TrialConfig? =
        config?.takeIf { now < it.endMillis }

    /** Wall-clock time of the pending capture alarm; 0 when none is scheduled. */
    var nextCaptureMillis: Long
        get() = prefs.getLong(KEY_NEXT, 0L)
        set(value) = prefs.edit().putLong(KEY_NEXT, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val KEY_CONFIG = "config"
        const val KEY_NEXT = "nextCapture"
    }
}
