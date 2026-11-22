package app.tremolo.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import app.tremolo.shared.model.RawWindow
import app.tremolo.shared.model.SensorSeries
import kotlinx.coroutines.delay

/** Records raw accelerometer and gyroscope samples for a fixed time. */
class SensorRecorder(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Returns null if the watch lacks either sensor. The caller must hold a wake lock. */
    suspend fun record(seconds: Int, rateHz: Int): RawWindow? {
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return null
        val capacity = seconds * rateHz * 2
        val accel = Buffer(capacity)
        val gyro = Buffer(capacity)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val target = if (event.sensor.type == Sensor.TYPE_GYROSCOPE) gyro else accel
                target.add(event.timestamp, event.values[0], event.values[1], event.values[2])
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        val thread = HandlerThread("tremolo-capture").apply { start() }
        val handler = Handler(thread.looper)
        val periodUs = 1_000_000 / rateHz
        try {
            sensorManager.registerListener(listener, accelSensor, periodUs, 0, handler)
            sensorManager.registerListener(listener, gyroSensor, periodUs, 0, handler)
            delay(seconds * 1000L)
        } finally {
            sensorManager.unregisterListener(listener)
            thread.quitSafely()
            thread.join(1000)
        }
        return RawWindow(accel.toSeries(), gyro.toSeries())
    }

    private class Buffer(initialCapacity: Int) {
        private var t = LongArray(initialCapacity)
        private var x = FloatArray(initialCapacity)
        private var y = FloatArray(initialCapacity)
        private var z = FloatArray(initialCapacity)
        private var size = 0

        fun add(timestamp: Long, vx: Float, vy: Float, vz: Float) {
            if (size == t.size) {
                val grown = size * 2
                t = t.copyOf(grown)
                x = x.copyOf(grown)
                y = y.copyOf(grown)
                z = z.copyOf(grown)
            }
            t[size] = timestamp
            x[size] = vx
            y[size] = vy
            z[size] = vz
            size++
        }

        fun toSeries() = SensorSeries(t.copyOf(size), x.copyOf(size), y.copyOf(size), z.copyOf(size))
    }
}
