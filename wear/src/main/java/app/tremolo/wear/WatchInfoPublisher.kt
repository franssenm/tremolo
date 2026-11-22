package app.tremolo.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import app.tremolo.shared.protocol.ProtocolJson
import app.tremolo.shared.protocol.WatchInfo
import app.tremolo.shared.protocol.WearPaths
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Tells the phone what this watch is and what its sensors deliver. Watches differ a lot
 * (some lack a gyroscope or an off-body sensor, some cap the sample rate), and the phone
 * uses this to warn the wearer rather than quietly produce poor numbers.
 */
object WatchInfoPublisher {
    private const val PREFS = "watchinfo"
    private const val KEY_ACCEL_HZ = "accelHz"
    private const val KEY_GYRO_HZ = "gyroHz"
    private const val KEY_PUBLISHED = "published"

    fun describe(context: Context): WatchInfo {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        fun maxHz(sensor: Sensor?) = sensor?.minDelay?.takeIf { it > 0 }?.let { 1_000_000 / it } ?: 0
        return WatchInfo(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            sdk = Build.VERSION.SDK_INT,
            hasAccelerometer = accel != null,
            hasGyroscope = gyro != null,
            hasOffBodySensor = sensors.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) != null,
            accelMaxHz = maxHz(accel),
            gyroMaxHz = maxHz(gyro),
            measuredAccelHz = prefs.getInt(KEY_ACCEL_HZ, 0),
            measuredGyroHz = prefs.getInt(KEY_GYRO_HZ, 0),
            exactAlarms = CaptureScheduler.exactAllowed(context),
        )
    }

    /** Publishes the description if it differs from what the phone was last told. */
    suspend fun publish(context: Context, measuredAccelHz: Int? = null, measuredGyroHz: Int? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (measuredAccelHz != null && measuredGyroHz != null) {
            prefs.edit().putInt(KEY_ACCEL_HZ, measuredAccelHz).putInt(KEY_GYRO_HZ, measuredGyroHz).apply()
        }
        val json = ProtocolJson.encodeToString(WatchInfo.serializer(), describe(context))
        if (json == prefs.getString(KEY_PUBLISHED, null)) return
        runCatching {
            val request = PutDataMapRequest.create(WearPaths.WATCH_INFO).apply {
                dataMap.putString(WearPaths.KEY_INFO, json)
            }.asPutDataRequest()
            Wearable.getDataClient(context).putDataItem(request).await()
            prefs.edit().putString(KEY_PUBLISHED, json).apply()
        }
    }
}
