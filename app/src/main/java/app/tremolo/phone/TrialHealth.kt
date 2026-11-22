package app.tremolo.phone

import app.tremolo.phone.data.Trial
import app.tremolo.shared.protocol.CaptureSchedule
import app.tremolo.shared.protocol.WatchInfo
import java.time.ZoneId

/**
 * A warning about the trial's data collection, or null when all looks well. It never says
 * what was measured or when, only that measuring itself seems to have a problem.
 */
suspend fun trialHealthWarning(app: TremoloApp, trial: Trial, now: Long): String? {
    if (app.wear.watchInfo()?.exactAlarms == false) return WatchInfo.ALARMS_NOT_ALLOWED
    val zone = ZoneId.systemDefault()
    val threeHours = 3 * 3_600_000L
    val config = trial.toConfig()
    // Only judge once the trial has been measuring through three waking hours in a row.
    val awakeThroughout = (0..3).all { CaptureSchedule.isAwake(config, now - it * 3_600_000L, zone) }
    val lastData = maxOf(app.dao.lastWindowTime(trial.id) ?: 0L, trial.startMillis)
    if (awakeThroughout && now - lastData > threeHours) {
        return "No measurements have arrived from the watch for a while. Check that it is on " +
            "your wrist, charged, and near the phone."
    }
    val dayAgo = now - 24 * 3_600_000L
    val offWrist = app.dao.offWristCountSince(trial.id, dayAgo)
    if (offWrist >= 8 && offWrist > app.dao.windowCountSince(trial.id, dayAgo)) {
        return "The watch has been off your wrist for much of the last day. " +
            "The more you wear it, the more reliable the result."
    }
    return null
}
