package app.tremolo.shared.protocol

import app.tremolo.shared.model.RawWindow
import app.tremolo.shared.model.SensorSeries
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * What the watch is and what its sensors can do. Published by the watch so the phone can
 * warn about watches that cannot measure well, and so reports can name the hardware.
 */
@Serializable
data class WatchInfo(
    val manufacturer: String = "",
    val model: String = "",
    val sdk: Int = 0,
    val hasAccelerometer: Boolean = true,
    val hasGyroscope: Boolean = true,
    /** A sensor that says whether the watch is on the wrist. */
    val hasOffBodySensor: Boolean = true,
    /** Fastest rates the sensors advertise, 0 when unknown. */
    val accelMaxHz: Int = 0,
    val gyroMaxHz: Int = 0,
    /** Rates actually delivered in the latest recording, 0 until one was made. */
    val measuredAccelHz: Int = 0,
    val measuredGyroHz: Int = 0,
) {
    val name: String
        get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "Unknown watch" }

    /** True when the watch can measure tremor at all. */
    val usable: Boolean
        get() = hasAccelerometer && hasGyroscope &&
            (measuredGyroHz == 0 || measuredGyroHz >= MIN_USABLE_HZ)

    /** Plain-language problems and caveats; empty when everything is as it should be. */
    fun problems(): List<String> = buildList {
        if (!hasGyroscope) add("This watch has no gyroscope (rotation sensor), which Tremolo needs. It cannot measure tremor.")
        if (!hasAccelerometer) add("This watch has no accelerometer, which Tremolo needs.")
        if (measuredGyroHz in 1 until MIN_USABLE_HZ) {
            add(
                "This watch delivers only $measuredGyroHz rotation readings a second; at least " +
                    "$MIN_USABLE_HZ are needed to see tremor up to 12 Hz. Results will be unreliable.",
            )
        }
        if (measuredGyroHz >= MIN_USABLE_HZ && measuredGyroHz < TARGET_HZ - 5) {
            add("This watch delivers $measuredGyroHz readings a second instead of $TARGET_HZ. That is enough, but fast tremor is measured a little less precisely.")
        }
        if (!hasOffBodySensor) {
            add(
                "This watch cannot tell when it is taken off. Tremolo leaves out recordings in " +
                    "which the watch lay perfectly still instead, which is a rougher check.",
            )
        }
    }

    companion object {
        const val TARGET_HZ = 50

        /** Below this the 12 Hz end of the tremor band is no longer sampled properly. */
        const val MIN_USABLE_HZ = 30
    }
}

/** Sensor-based guesses about a recording, used on watches that lack an off-body sensor. */
object Stillness {
    /** Readings per second actually delivered, rounded; 0 for an empty series. */
    fun rateHz(series: SensorSeries): Int {
        if (series.size < 2) return 0
        val nanos = series.timestampsNanos[series.size - 1] - series.timestampsNanos[0]
        return if (nanos <= 0) 0 else Math.round((series.size - 1) * 1e9 / nanos).toInt()
    }

    private fun spread(values: FloatArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.sumOf { it.toDouble() } / values.size
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    private fun spread(series: SensorSeries) = sqrt(spread(series.x) + spread(series.y) + spread(series.z))

    /**
     * True when the watch lay as still as only an object does: a wrist, even a resting one,
     * always moves a little with breathing and pulse. The limits are cautious guesses.
     */
    fun looksUnworn(window: RawWindow): Boolean {
        if (window.gyro.size < 100 || window.accel.size < 100) return false
        return spread(window.gyro) < MAX_GYRO_RAD_PER_SEC && spread(window.accel) < MAX_ACCEL_MS2
    }

    /** About 0.17 degrees per second. */
    private const val MAX_GYRO_RAD_PER_SEC = 0.003
    private const val MAX_ACCEL_MS2 = 0.02
}
