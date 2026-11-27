package app.tremolo.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.tremolo.shared.i18n.tr
import app.tremolo.shared.model.RawWindowCodec
import app.tremolo.shared.protocol.BACKGROUND_TRIAL_ID
import app.tremolo.shared.protocol.ProtocolJson
import app.tremolo.shared.protocol.SkipEvent
import app.tremolo.shared.protocol.SkipReason
import app.tremolo.shared.protocol.Stillness
import app.tremolo.shared.protocol.WearPaths
import app.tremolo.shared.protocol.WindowKind
import app.tremolo.shared.protocol.WindowMeta
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Foreground service that runs for the whole trial (and briefly for a guided test outside
 * one). Its notification never changes, so it does not reveal when a capture is happening. Captures are triggered by [AlarmReceiver].
 */
class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureLock = Mutex()
    private lateinit var recorder: SensorRecorder
    private lateinit var sensorManager: SensorManager

    /** False on watches without an off-body sensor; a stillness check is used instead. */
    private var hasOffBodySensor = false

    /** Last reading of the off-body sensor; null until it has reported. */
    @Volatile
    private var onWrist: Boolean? = null

    private val offBodyListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            onWrist = event.values[0] != 0f
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        recorder = SensorRecorder(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)?.let {
            hasOffBodySensor = true
            sensorManager.registerListener(offBodyListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CAPTURE -> scope.launch { passiveCapture(startId) }
            ACTION_TEST -> {
                val kind = runCatching {
                    WindowKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty())
                }.getOrDefault(WindowKind.TEST_REST)
                val seconds = intent.getIntExtra(EXTRA_SECONDS, 30)
                val leadIn = intent.getIntExtra(EXTRA_LEAD_IN_SECONDS, 5)
                scope.launch { guidedTest(kind, seconds, leadIn, startId) }
            }
            ACTION_DEBUG_CAPTURE -> {
                val label = intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() }
                scope.launch { debugCapture(startId, label) }
            }
            else -> scope.launch { syncWithTrial(startId) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(offBodyListener)
        scope.cancel()
        CaptureWakeLock.release()
        super.onDestroy()
    }

    /** Schedules captures if a trial is running; otherwise shuts the service down. */
    private suspend fun syncWithTrial(startId: Int) = captureLock.withLock {
        val config = trialStore.activeConfig()
        if (config == null) {
            shutDown(startId)
        } else {
            CaptureScheduler.ensureScheduled(this, config)
        }
    }

    private suspend fun passiveCapture(startId: Int) {
        try {
            captureLock.withLock {
                val config = trialStore.activeConfig()
                if (config == null) {
                    shutDown(startId)
                    return
                }
                val skip = when {
                    isCharging() -> SkipReason.CHARGING
                    onWrist == false -> SkipReason.OFF_WRIST
                    else -> null
                }
                if (skip != null) {
                    reportSkip(config.trialId, skip)
                } else {
                    recordAndSend(
                        config.trialId, WindowKind.PASSIVE,
                        config.windowSeconds, config.sampleRateHz, urgent = false,
                        skipIfUnworn = !hasOffBodySensor,
                        plannedGapSeconds = CaptureScheduler.plannedGapSeconds(this, config),
                    )
                }
                if (!CaptureScheduler.scheduleNext(this, config)) shutDown(startId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Capture failed", e)
            trialStore.activeConfig()?.let { CaptureScheduler.scheduleNext(this, it) }
        } finally {
            CaptureWakeLock.release()
        }
    }

    /** A test the wearer knows about: buzz, record, double buzz. */
    private suspend fun guidedTest(kind: WindowKind, seconds: Int, leadInSeconds: Int, startId: Int) {
        CaptureWakeLock.acquire(this, (leadInSeconds + seconds + 30) * 1000L)
        try {
            captureLock.withLock {
                delay(leadInSeconds * 1000L)
                vibrate(longArrayOf(0, 300))
                val config = trialStore.activeConfig()
                recordAndSend(
                    config?.trialId, kind, seconds, config?.sampleRateHz ?: 50, urgent = true,
                )
                vibrate(longArrayOf(0, 200, 150, 200))
                if (config == null) shutDown(startId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Guided test failed", e)
        } finally {
            CaptureWakeLock.release()
        }
    }

    private suspend fun debugCapture(startId: Int, label: String?) {
        CaptureWakeLock.acquire(this, 60_000L)
        try {
            captureLock.withLock {
                val config = trialStore.activeConfig()
                recordAndSend(config?.trialId, WindowKind.DEBUG, 20, 50, urgent = true, label = label)
                if (config == null) shutDown(startId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Debug capture failed", e)
        } finally {
            CaptureWakeLock.release()
        }
    }

    private suspend fun recordAndSend(
        trialId: String?,
        kind: WindowKind,
        seconds: Int,
        rateHz: Int,
        urgent: Boolean,
        label: String? = null,
        skipIfUnworn: Boolean = false,
        plannedGapSeconds: Int = 0,
    ) {
        val startMillis = System.currentTimeMillis()
        val window = recorder.record(seconds, rateHz) ?: return
        // Watches differ in what they really deliver; the phone warns when it is too little.
        WatchInfoPublisher.publish(this, Stillness.rateHz(window.accel), Stillness.rateHz(window.gyro))
        if (skipIfUnworn && trialId != null && Stillness.looksUnworn(window)) {
            reportSkip(trialId, SkipReason.OFF_WRIST)
            return
        }
        val bytes = ByteArrayOutputStream().also { RawWindowCodec.write(window, it) }.toByteArray()
        val meta = WindowMeta(
            UUID.randomUUID().toString(), trialId, kind, startMillis, label = label,
            batteryPercent = CaptureScheduler.batteryPercent(this), plannedGapSeconds = plannedGapSeconds,
        )

        // The Data Layer stores the item on the watch and delivers it whenever the phone is
        // reachable; it is deleted when the phone acknowledges it (see PhoneListenerService).
        val request = PutDataMapRequest.create(WearPaths.WINDOW_PREFIX + meta.windowId).apply {
            dataMap.putString(
                WearPaths.KEY_META, ProtocolJson.encodeToString(WindowMeta.serializer(), meta),
            )
            dataMap.putAsset(WearPaths.KEY_RAW, Asset.createFromBytes(bytes))
        }.asPutDataRequest()
        if (urgent) request.setUrgent()
        Wearable.getDataClient(this).putDataItem(request).await()
    }

    private suspend fun reportSkip(trialId: String, reason: SkipReason) {
        val event = SkipEvent(UUID.randomUUID().toString(), trialId, System.currentTimeMillis(), reason)
        val request = PutDataMapRequest.create(WearPaths.SKIP_PREFIX + event.eventId).apply {
            dataMap.putString(
                WearPaths.KEY_SKIP, ProtocolJson.encodeToString(SkipEvent.serializer(), event),
            )
        }.asPutDataRequest()
        Wearable.getDataClient(this).putDataItem(request).await()
    }

    private fun isCharging(): Boolean =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging

    private fun vibrate(pattern: LongArray) {
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /**
     * Stops the service once there is no trial, unless a newer request (for example another
     * test queued behind this one) has arrived in the meantime; that request stops it later.
     */
    private fun shutDown(startId: Int) {
        CaptureScheduler.cancel(this)
        if (stopSelfResult(startId)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(this),
            // The "health" type only exists from Android 14; older versions need none.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            } else {
                0
            },
        )
        true
    } catch (e: Exception) {
        // Background start refused by the system: ask the wearer to open the app instead.
        Log.w(TAG, "Could not enter foreground", e)
        showOpenAppNotification(this)
        false
    }

    companion object {
        private const val TAG = "TremoloCapture"
        const val ACTION_SYNC = "app.tremolo.wear.SYNC"
        const val ACTION_CAPTURE = "app.tremolo.wear.CAPTURE"
        const val ACTION_TEST = "app.tremolo.wear.TEST"
        const val ACTION_DEBUG_CAPTURE = "app.tremolo.wear.DEBUG_CAPTURE"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_LEAD_IN_SECONDS = "leadIn"
        const val EXTRA_LABEL = "label"

        private const val CHANNEL_ID = "trial"
        private const val NOTIFICATION_ID = 1
        private const val OPEN_APP_NOTIFICATION_ID = 2

        fun start(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, CaptureService::class.java).setAction(action).apply(extras)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start service", e)
                showOpenAppNotification(context)
            }
        }

        fun startTest(context: Context, kind: WindowKind, seconds: Int, leadInSeconds: Int) =
            start(context, ACTION_TEST) {
                putExtra(EXTRA_KIND, kind.name)
                putExtra(EXTRA_SECONDS, seconds)
                putExtra(EXTRA_LEAD_IN_SECONDS, leadInSeconds)
            }

        private fun ensureChannel(context: Context): NotificationManager {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tremolo trial", NotificationManager.IMPORTANCE_LOW),
            )
            return manager
        }

        private fun openAppIntent(context: Context) = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /**
         * Says what the watch is measuring for. It never says when: the text stays the same
         * between checks and during them, so it gives no clue about the moments of measuring.
         */
        private fun activityText(context: Context): String {
            val config = context.trialStore.activeConfig()
            return when {
                // A guided test or a calibration recording outside any trial.
                config == null -> "Tremolo is active"
                config.trialId == BACKGROUND_TRIAL_ID -> "Everyday monitoring is on."
                else -> "A trial is running."
            }
        }

        private fun buildNotification(context: Context): Notification {
            ensureChannel(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_tremolo)
                .setContentTitle("Tremolo")
                .setContentText(tr(activityText(context)))
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(openAppIntent(context))
                .build()
        }

        private fun showOpenAppNotification(context: Context) {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_tremolo)
                .setContentTitle("Tremolo")
                .setContentText(tr("Tap to resume monitoring"))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
            runCatching { ensureChannel(context).notify(OPEN_APP_NOTIFICATION_ID, notification) }
        }
    }
}
