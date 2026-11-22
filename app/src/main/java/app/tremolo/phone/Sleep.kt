package app.tremolo.phone

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads sleep from Health Connect, the on-phone store that Fitbit (Pixel Watch), Samsung
 * Health and others can write to. Read-only, read on demand, and nothing is copied out of
 * the phone.
 */
object SleepImport {
    val PERMISSIONS = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    fun available(context: Context): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }
            .getOrDefault(false)

    suspend fun granted(context: Context): Boolean = available(context) && runCatching {
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
            .containsAll(PERMISSIONS)
    }.getOrDefault(false)

    /**
     * Hours slept per night, keyed by the day the sleep ended on. Empty when Health Connect
     * is missing, not allowed, or holds no sleep.
     */
    suspend fun hoursByWakeDay(
        context: Context, fromMillis: Long, toMillis: Long, zone: ZoneId,
    ): Map<LocalDate, Double> {
        return hoursByDay(read(context, fromMillis, toMillis), zone).filterKeys {
            !it.isBefore(Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate())
        }
    }

    /** Sleep periods touching the given time span, as epoch-millisecond ranges. */
    suspend fun sessions(context: Context, fromMillis: Long, toMillis: Long): List<LongRange> =
        read(context, fromMillis, toMillis).map { it.first.toEpochMilli()..it.second.toEpochMilli() }

    private suspend fun read(context: Context, fromMillis: Long, toMillis: Long): List<Pair<Instant, Instant>> {
        if (!granted(context)) return emptyList()
        return runCatching {
            HealthConnectClient.getOrCreate(context).readRecords(
                ReadRecordsRequest(
                    SleepSessionRecord::class,
                    // A night that ends inside the period may have started the evening before.
                    TimeRangeFilter.between(
                        Instant.ofEpochMilli(fromMillis).minus(Duration.ofHours(18)),
                        Instant.ofEpochMilli(minOf(toMillis, System.currentTimeMillis())),
                    ),
                ),
            ).records.map { it.startTime to it.endTime }
        }.getOrDefault(emptyList())
    }

    /**
     * Hours of the main sleep ending on each day. Sessions that overlap (two apps reporting
     * the same night) or are separated by a short time awake are joined; of what remains,
     * only the longest stretch of a day counts, so naps and stray entries do not inflate it.
     */
    internal fun hoursByDay(sessions: List<Pair<Instant, Instant>>, zone: ZoneId): Map<LocalDate, Double> {
        val merged = ArrayList<Pair<Instant, Instant>>()
        for (session in sessions.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && !session.first.isAfter(last.second.plus(Duration.ofMinutes(45)))) {
                merged[merged.size - 1] = last.first to maxOf(last.second, session.second)
            } else {
                merged += session
            }
        }
        return merged.groupBy { it.second.atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.maxOf { Duration.between(it.first, it.second).toMinutes() } / 60.0 }
    }
}
