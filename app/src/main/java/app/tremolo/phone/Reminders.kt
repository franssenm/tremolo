package app.tremolo.phone

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.tremolo.phone.data.EventLogEntry
import app.tremolo.phone.data.Medication
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import app.tremolo.shared.i18n.tr
import kotlinx.coroutines.launch

/**
 * Phone notifications: medication reminders, the daily rating prompt and the "trial
 * finished" notice. Phone notifications also appear on a paired watch, including their
 * action buttons, so "Taken" can be tapped from the wrist.
 */
object Reminders {
    const val EXTRA_ROUTE = "route"
    private const val CHANNEL_ID = "reminders"
    private const val ACTION_RATING = "app.tremolo.phone.RATING"
    private const val ACTION_TRIAL_END = "app.tremolo.phone.TRIAL_END"
    private const val ACTION_APPOINTMENT = "app.tremolo.phone.APPOINTMENT"
    private const val APPOINTMENT_NOTIFICATION = 3
    private const val ACTION_MEDICATION = "app.tremolo.phone.MEDICATION"
    private const val ACTION_MEDICATION_TAKEN = "app.tremolo.phone.MEDICATION_TAKEN"
    private const val EXTRA_MEDICATION_ID = "medicationId"
    private const val EXTRA_MINUTE = "minute"
    private const val RATING_NOTIFICATION = 1
    private const val TRIAL_END_NOTIFICATION = 2
    private const val MEDICATION_NOTIFICATION_BASE = 1000
    private const val ACTION_WATCH_CHECK = "app.tremolo.phone.WATCH_CHECK"
    private const val WATCH_NOTIFICATION = 4

    /** The same problem is not announced again within this time. */
    private const val WATCH_WARNING_REPEAT_MILLIS = 6 * 3_600_000L

    /** Most reminder times one medication can have. */
    const val MAX_TIMES_PER_MEDICATION = 8

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("Reminders"), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun scheduleDailyRating(context: Context) {
        val minute = (context.applicationContext as TremoloApp).prefs.ratingMinute
        alarms(context).setInexactRepeating(
            AlarmManager.RTC_WAKEUP, nextOccurrence(minute),
            AlarmManager.INTERVAL_DAY, broadcast(context, ACTION_RATING),
        )
    }

    fun scheduleTrialEnd(context: Context, atMillis: Long) {
        alarms(context).set(AlarmManager.RTC_WAKEUP, atMillis, broadcast(context, ACTION_TRIAL_END))
    }

    /** Reminds the evening before the appointment to share the report; clears it when none is set. */
    fun scheduleAppointment(context: Context) {
        val day = (context.applicationContext as TremoloApp).prefs.appointmentDay
        val intent = broadcast(context, ACTION_APPOINTMENT)
        alarms(context).cancel(intent)
        if (day <= 0L) return
        val at = LocalDate.ofEpochDay(day).minusDays(1).atTime(18, 0).atZone(ZoneId.systemDefault())
        if (at.isAfter(ZonedDateTime.now())) {
            alarms(context).set(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), intent)
        }
    }

    /**
     * During a trial, looks about once an hour whether measurements still arrive, so a watch
     * that has stopped (flat, off the wrist, out of reach) is noticed the same day.
     */
    fun scheduleWatchCheck(context: Context) {
        alarms(context).setInexactRepeating(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR,
            AlarmManager.INTERVAL_HOUR, broadcast(context, ACTION_WATCH_CHECK),
        )
    }

    fun cancelWatchCheck(context: Context) {
        alarms(context).cancel(broadcast(context, ACTION_WATCH_CHECK))
        context.getSystemService(NotificationManager::class.java).cancel(WATCH_NOTIFICATION)
        (context.applicationContext as TremoloApp).prefs.watchWarningMillis = 0L
    }

    internal suspend fun checkWatch(context: Context, app: TremoloApp) {
        val trial = app.trials.running()
        if (trial == null) {
            cancelWatchCheck(context)
            return
        }
        val now = System.currentTimeMillis()
        val warning = trialHealthWarning(app, trial, now)
        if (warning == null) {
            // Measuring again: take the notice away and allow a new one next time.
            context.getSystemService(NotificationManager::class.java).cancel(WATCH_NOTIFICATION)
            app.prefs.watchWarningMillis = 0L
        } else if (now - app.prefs.watchWarningMillis >= WATCH_WARNING_REPEAT_MILLIS) {
            app.prefs.watchWarningMillis = now
            notify(context, WATCH_NOTIFICATION, "Check your watch", warning, "home", long = true)
        }
    }

    fun cancelTrialEnd(context: Context) {
        alarms(context).cancel(broadcast(context, ACTION_TRIAL_END))
    }

    /** Replaces all reminder alarms of one medication with its current times. */
    fun scheduleMedication(context: Context, medication: Medication) {
        cancelMedication(context, medication.id)
        if (!medication.remind) return
        medication.timeList().take(MAX_TIMES_PER_MEDICATION).forEachIndexed { slot, minute ->
            setMedicationAlarm(context, medication.id, slot, minute)
        }
    }

    fun cancelMedication(context: Context, medicationId: Long) {
        for (slot in 0 until MAX_TIMES_PER_MEDICATION) {
            alarms(context).cancel(medicationIntent(context, medicationId, slot, 0))
        }
        context.getSystemService(NotificationManager::class.java)
            .cancel(MEDICATION_NOTIFICATION_BASE + medicationId.toInt())
    }

    private fun setMedicationAlarm(context: Context, medicationId: Long, slot: Int, minute: Int) {
        // Not an exact alarm (those need a special permission), so the reminder can arrive a
        // few minutes late when the phone is idle.
        alarms(context).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, nextOccurrence(minute),
            medicationIntent(context, medicationId, slot, minute),
        )
    }

    internal suspend fun handle(context: Context, intent: Intent, app: TremoloApp) {
        when (intent.action) {
            // Only ask for a rating on days that are being measured.
            ACTION_RATING -> if (app.trials.running() != null || app.prefs.monitorAlways) {
                notify(
                    context, RATING_NOTIFICATION, "How was today?",
                    "Rate today's tremor, side effects and mood.", "rating",
                )
            }

            ACTION_WATCH_CHECK -> checkWatch(context, app)

            ACTION_TRIAL_END -> {
                cancelWatchCheck(context)
                notify(
                    context, TRIAL_END_NOTIFICATION, "Trial finished",
                    "Your tremor report is ready.", "home",
                )
                // Hands the watch back to everyday monitoring, if that is switched on.
                app.trials.resync()
            }

            ACTION_APPOINTMENT -> notify(
                context, APPOINTMENT_NOTIFICATION, "DBS appointment tomorrow",
                "Open Tremolo to share the report for your DBS programmer.", "compare",
            )

            ACTION_MEDICATION -> {
                val medication = app.dao.medication(intent.getLongExtra(EXTRA_MEDICATION_ID, -1)) ?: return
                val minute = intent.getIntExtra(EXTRA_MINUTE, -1)
                val slot = medication.timeList().indexOf(minute)
                if (!medication.remind || slot < 0) return
                notifyMedication(context, medication)
                setMedicationAlarm(context, medication.id, slot, minute) // same time tomorrow
            }

            ACTION_MEDICATION_TAKEN -> {
                val medication = app.dao.medication(intent.getLongExtra(EXTRA_MEDICATION_ID, -1)) ?: return
                logTaken(app, medication)
                context.getSystemService(NotificationManager::class.java)
                    .cancel(MEDICATION_NOTIFICATION_BASE + medication.id.toInt())
            }
        }
    }

    suspend fun logTaken(app: TremoloApp, medication: Medication) {
        app.dao.insertEvent(
            EventLogEntry(
                timeMillis = System.currentTimeMillis(),
                type = if (medication.isReminder) "Reminder" else "Medication",
                note = medication.title(),
            ),
        )
    }

    private fun notifyMedication(context: Context, medication: Medication) {
        val taken = PendingIntent.getBroadcast(
            context, medication.id.toInt(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_MEDICATION_TAKEN)
                .putExtra(EXTRA_MEDICATION_ID, medication.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tremolo)
            .setContentTitle(tr("Time for ${medication.title()}"))
            .setContentText(tr(if (medication.isReminder) "Tap Done when finished." else "Tap Taken once you have taken it."))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, MEDICATION_NOTIFICATION_BASE + medication.id.toInt(), "medications"))
            .addAction(0, tr(if (medication.isReminder) "Done" else "Taken"), taken)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(MEDICATION_NOTIFICATION_BASE + medication.id.toInt(), notification)
        }
    }

    private fun notify(
        context: Context, id: Int, title: String, text: String, route: String, long: Boolean = false,
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tremolo)
            .setContentTitle(tr(title))
            .setContentText(tr(text))
            .apply { if (long) setStyle(NotificationCompat.BigTextStyle().bigText(tr(text))) }
            .setAutoCancel(true)
            .setContentIntent(openApp(context, id, route))
            .build()
        // Silently does nothing if the notification permission was not granted.
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    private fun openApp(context: Context, requestCode: Int, route: String) = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The next time the clock shows [minute] (minutes after midnight), as epoch millis. */
    private fun nextOccurrence(minute: Int): Long {
        var at = LocalDate.now().atTime(minute / 60 % 24, minute % 60).atZone(ZoneId.systemDefault())
        if (!at.isAfter(ZonedDateTime.now().plusSeconds(30))) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    private fun alarms(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun broadcast(context: Context, action: String) = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** One pending intent per medication and time slot, told apart by the request code. */
    private fun medicationIntent(context: Context, medicationId: Long, slot: Int, minute: Int) =
        PendingIntent.getBroadcast(
            context, (medicationId * MAX_TIMES_PER_MEDICATION + slot).toInt(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_MEDICATION)
                .putExtra(EXTRA_MEDICATION_ID, medicationId)
                .putExtra(EXTRA_MINUTE, minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TremoloApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                Reminders.handle(context, intent, app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms do not survive a restart, so they are set again here. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as TremoloApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.trials.running()?.let {
                    Reminders.scheduleTrialEnd(context, it.plannedEndMillis)
                    Reminders.scheduleWatchCheck(context)
                }
                Reminders.scheduleDailyRating(context)
                Reminders.scheduleAppointment(context)
                app.dao.medicationList().forEach { Reminders.scheduleMedication(context, it) }
            } finally {
                pending.finish()
            }
        }
    }
}
