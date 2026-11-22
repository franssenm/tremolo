package app.tremolo.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import app.tremolo.shared.protocol.CaptureSchedule
import app.tremolo.shared.protocol.TrialConfig
import java.time.ZoneId
import kotlin.random.Random

/** Sets the alarm for the next hidden capture. */
object CaptureScheduler {

    /** Keeps an alarm that is still in the future; otherwise draws a new random time. */
    fun ensureScheduled(context: Context, config: TrialConfig) {
        val pending = context.trialStore.nextCaptureMillis
        if (pending > System.currentTimeMillis() && pending < config.endMillis) {
            setAlarm(context, pending)
        } else {
            scheduleNext(context, config)
        }
    }

    /** Returns false when the trial has no captures left. */
    fun scheduleNext(context: Context, config: TrialConfig): Boolean {
        // Battery guard: measure less often as the charge runs down, rather than draining
        // the watch or stopping altogether.
        val stretch = batteryStretch(context)
        val paced = config.copy(
            minIntervalSeconds = config.minIntervalSeconds * stretch,
            maxIntervalSeconds = config.maxIntervalSeconds * stretch,
        )
        // Counted from when the last check was meant to run, so late alarms do not add up.
        val next = CaptureSchedule.next(
            paced, System.currentTimeMillis(), Random.Default, ZoneId.systemDefault(),
            plannedMillis = context.trialStore.nextCaptureMillis,
        )
        if (next == null) {
            cancel(context)
            return false
        }
        context.trialStore.nextCaptureMillis = next
        setAlarm(context, next)
        return true
    }

    /** Charge in percent, or -1 when the watch does not say. */
    fun batteryPercent(context: Context): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 1..100 } ?: -1

    /** Average gap between checks right now, in seconds, battery guard included. */
    fun plannedGapSeconds(context: Context, config: TrialConfig): Int =
        (config.minIntervalSeconds + config.maxIntervalSeconds) / 2 * batteryStretch(context)

    /** 1 normally, 2 below 30% charge, 4 below 15%. */
    private fun batteryStretch(context: Context): Int {
        val percent = batteryPercent(context)
        return when {
            percent <= 0 -> 1 // unknown
            percent < 15 -> 4
            percent < 30 -> 2
            else -> 1
        }
    }

    fun cancel(context: Context) {
        context.trialStore.nextCaptureMillis = 0L
        alarmManager(context).cancel(pendingIntent(context))
    }

    /**
     * Whether the system lets this app set exact alarms. It matters more than it sounds: a
     * dozing watch runs only about 7 inexact alarms an hour for an app, against 72 exact ones,
     * so without it the faster measuring paces make half their checks or fewer.
     */
    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    private fun setAlarm(context: Context, atMillis: Long) {
        val alarms = alarmManager(context)
        val intent = pendingIntent(context)
        if (exactAllowed(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        }
    }

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Holds the CPU awake from the alarm firing until the capture has been stored. */
object CaptureWakeLock {
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context, millis: Long) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = lock ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tremolo:capture")
            .apply { setReferenceCounted(false) }
            .also { lock = it }
        wakeLock.acquire(millis)
    }

    @Synchronized
    fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CaptureWakeLock.acquire(context, 90_000L)
        CaptureService.start(context, CaptureService.ACTION_CAPTURE)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (context.trialStore.activeConfig() != null) {
            CaptureService.start(context, CaptureService.ACTION_SYNC)
        }
    }
}
