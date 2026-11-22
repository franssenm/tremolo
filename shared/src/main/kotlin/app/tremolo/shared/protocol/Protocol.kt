package app.tremolo.shared.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wearable Data Layer paths and keys shared by the phone and watch apps. */
object WearPaths {
    /** DataItem written by the phone: the trial the watch should be sampling for (or none). */
    const val TRIAL = "/tremolo/trial"
    const val KEY_CONFIG = "config"
    const val KEY_UPDATED = "updated"

    /** DataItems written by the watch, one per capture: [KEY_META] json + [KEY_RAW] asset. */
    const val WINDOW_PREFIX = "/tremolo/window/"
    const val KEY_META = "meta"
    const val KEY_RAW = "raw"

    /** DataItems written by the watch when a scheduled capture was skipped. */
    const val SKIP_PREFIX = "/tremolo/skip/"
    const val KEY_SKIP = "skip"

    /** DataItems written by the watch when the wearer logs something from the wrist. */
    const val EVENT_PREFIX = "/tremolo/event/"
    const val KEY_EVENT = "event"

    /** Message phone -> watch: payload is the path of a watch DataItem the phone has stored. */
    const val ACK = "/tremolo/ack"

    /** Message phone -> watch: payload is an [ActiveTestRequest] json. */
    const val ACTIVE_TEST = "/tremolo/test"

    /** DataItem written by the watch: a [WatchInfo] json describing the watch and its sensors. */
    const val WATCH_INFO = "/tremolo/watch-info"
    const val KEY_INFO = "info"

    /** Message phone -> watch: record one labelled window now; payload is an optional label. */
    const val CAPTURE_NOW = "/tremolo/capture-now"
}

/**
 * Trial id used for everyday monitoring outside any trial. Captures tagged with it feed the
 * tremor timeline and belong to no trial report.
 */
const val BACKGROUND_TRIAL_ID = "background"

/** Something the wearer logged from the watch, e.g. "Tremor bad". */
@Serializable
data class WatchEvent(
    val eventId: String,
    val timeMillis: Long,
    val type: String,
    val note: String = "",
)

@Serializable
data class TrialConfig(
    val trialId: String,
    val startMillis: Long,
    val endMillis: Long,
    /** Captures only happen between these local times, in minutes after midnight. */
    val wakeStartMinute: Int = 7 * 60,
    val wakeEndMinute: Int = 23 * 60,
    /** The gap between captures is drawn uniformly from this range. */
    val minIntervalSeconds: Int = 8 * 60,
    val maxIntervalSeconds: Int = 22 * 60,
    val windowSeconds: Int = 20,
    val sampleRateHz: Int = 50,
)

@Serializable
enum class WindowKind { PASSIVE, TEST_REST, TEST_POSTURAL, DEBUG }

@Serializable
data class WindowMeta(
    val windowId: String,
    val trialId: String?,
    val kind: WindowKind,
    val startMillis: Long,
    val testId: String? = null,
    /** What the wearer said they were doing, for labelled debug recordings. */
    val label: String? = null,
    /** Watch charge when the recording was made, 0-100; -1 when unknown. */
    val batteryPercent: Int = -1,
    /** Average planned gap between hidden checks at that moment, in seconds; 0 when unknown. */
    val plannedGapSeconds: Int = 0,
)

@Serializable
enum class SkipReason { OFF_WRIST, CHARGING }

@Serializable
data class SkipEvent(
    val eventId: String,
    val trialId: String,
    val timeMillis: Long,
    val reason: SkipReason,
)

@Serializable
data class ActiveTestRequest(
    val testId: String,
    val trialId: String?,
    val kind: WindowKind,
    val seconds: Int = 30,
)

val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
