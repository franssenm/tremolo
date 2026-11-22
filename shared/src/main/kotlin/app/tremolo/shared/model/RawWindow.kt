package app.tremolo.shared.model

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Samples of one 3-axis sensor. Timestamps are sensor-clock nanoseconds (monotonic). */
class SensorSeries(
    val timestampsNanos: LongArray,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
) {
    val size: Int get() = timestampsNanos.size

    init {
        require(x.size == size && y.size == size && z.size == size)
    }
}

/** One raw capture: accelerometer in m/s^2 and gyroscope in rad/s. */
class RawWindow(val accel: SensorSeries, val gyro: SensorSeries)

/** Compact gzip'd binary encoding of a [RawWindow], used for watch storage and transfer. */
object RawWindowCodec {
    private const val MAGIC = 0x54525731 // "TRW1"
    private const val MAX_SAMPLES = 1_000_000

    fun write(window: RawWindow, out: OutputStream) {
        DataOutputStream(GZIPOutputStream(out)).use { data ->
            data.writeInt(MAGIC)
            writeSeries(data, window.accel)
            writeSeries(data, window.gyro)
        }
    }

    fun read(input: InputStream): RawWindow {
        DataInputStream(GZIPInputStream(input)).use { data ->
            require(data.readInt() == MAGIC) { "Not a Tremolo raw window" }
            val accel = readSeries(data)
            val gyro = readSeries(data)
            return RawWindow(accel, gyro)
        }
    }

    private fun writeSeries(data: DataOutputStream, s: SensorSeries) {
        data.writeInt(s.size)
        for (i in 0 until s.size) {
            data.writeLong(s.timestampsNanos[i])
            data.writeFloat(s.x[i])
            data.writeFloat(s.y[i])
            data.writeFloat(s.z[i])
        }
    }

    private fun readSeries(data: DataInputStream): SensorSeries {
        val n = data.readInt()
        require(n in 0..MAX_SAMPLES) { "Corrupt sample count: $n" }
        val t = LongArray(n)
        val x = FloatArray(n)
        val y = FloatArray(n)
        val z = FloatArray(n)
        for (i in 0 until n) {
            t[i] = data.readLong()
            x[i] = data.readFloat()
            y[i] = data.readFloat()
            z[i] = data.readFloat()
        }
        return SensorSeries(t, x, y, z)
    }
}
