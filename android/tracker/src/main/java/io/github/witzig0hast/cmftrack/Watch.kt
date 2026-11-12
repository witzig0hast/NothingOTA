package io.github.witzig0hast.cmftrack

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/**
 * Everything the watch stored in Health Connect (written there by the CMF app):
 * heart rate samples and workouts recorded on the watch itself.
 */
object Watch {
    val HR = HealthPermission.getReadPermission(HeartRateRecord::class)
    val PERMISSIONS = setOf(
        HR,
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )
    private val CMF = listOf("com.nothing.cmf.watch", "com.nothing.smartcenter", "com.nothing.cmf")
    fun isCmf(pkg: String) = CMF.any { pkg.startsWith(it) }

    data class HrResult(
        val samples: List<Pair<Long, Int>>,
        val source: String?,
        val lastWatchSample: Long?, // newest heart rate the watch delivered at all (diagnostics)
    ) {
        fun explain(start: Long, end: Long): String = when {
            samples.isNotEmpty() -> "${samples.size} Pulswerte von der Uhr" + if (samples.size < (end - start) / 120_000) " (Uhr hat nur alle paar Minuten gemessen – für jede Sekunde auf der Uhr auch ein Training starten)" else ""
            lastWatchSample == null -> "Health Connect enthält keinen Puls der Uhr. In der CMF-App: Profil → Health Connect → Herzfrequenz freigeben."
            lastWatchSample < end -> "Die Uhr hat noch nicht synchronisiert (letzter Puls ${Fmt.ago(lastWatchSample)}). CMF-App öffnen und synchronisieren – CMF Track versucht es automatisch weiter."
            else -> "Die Uhr hat in diesem Zeitraum keinen Puls gespeichert."
        }
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAll(
        client: HealthConnectClient, type: kotlin.reflect.KClass<T>, from: Long, to: Long,
    ): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val r = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)), pageToken = token))
            out += r.records
            token = r.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    /** Heart rate in [start, end]; prefers the CMF app, otherwise the source with most samples. */
    suspend fun heartRate(client: HealthConnectClient, start: Long, end: Long): HrResult {
        val bySource = mutableMapOf<String, MutableList<Pair<Long, Int>>>()
        readAll(client, HeartRateRecord::class, start - 600_000, end + 600_000).forEach { rec ->
            val list = bySource.getOrPut(rec.metadata.dataOrigin.packageName) { mutableListOf() }
            rec.samples.forEach { s -> list += s.time.toEpochMilli() to s.beatsPerMinute.toInt() }
        }
        // newest watch sample of the last 2 days, to tell "not synced yet" from "nothing measured"
        val recent = readAll(client, HeartRateRecord::class, System.currentTimeMillis() - 2 * 86_400_000L, System.currentTimeMillis())
        val last = recent.filter { isCmf(it.metadata.dataOrigin.packageName) }.flatMap { it.samples }.maxOfOrNull { it.time.toEpochMilli() }
            ?: recent.flatMap { it.samples }.maxOfOrNull { it.time.toEpochMilli() }
        val src = bySource.keys.firstOrNull(::isCmf) ?: bySource.maxByOrNull { it.value.size }?.key
            ?: return HrResult(emptyList(), null, last)
        // keep a few minutes before/after so sparse all-day samples can still be interpolated at the edges
        val samples = bySource[src]!!.filter { it.first in (start - 300_000)..(end + 300_000) }.distinctBy { it.first }.sortedBy { it.first }
        return HrResult(samples, src, last)
    }

    data class WatchWorkout(val record: ExerciseSessionRecord, val distance: Double?, val kcal: Double?, val hasRoute: Boolean) {
        val id: String get() = record.metadata.id
        val start get() = record.startTime.toEpochMilli()
        val end get() = record.endTime.toEpochMilli()
        val sport get() = sportOf(record.exerciseType)
    }

    fun sportOf(type: Int): Sport = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> Sport.RUN
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> Sport.WALK
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> Sport.HIKE
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> Sport.RIDE
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> Sport.INLINE
        else -> Sport.OTHER
    }

    /** Workouts recorded on the watch (CMF app) in the last [days] days. */
    suspend fun workouts(client: HealthConnectClient, days: Int = 30): List<WatchWorkout> {
        val now = System.currentTimeMillis()
        val from = now - days * 86_400_000L
        val sessions = readAll(client, ExerciseSessionRecord::class, from, now).filter { isCmf(it.metadata.dataOrigin.packageName) }
        if (sessions.isEmpty()) return emptyList()
        val dist = runCatching { readAll(client, DistanceRecord::class, from, now) }.getOrDefault(emptyList())
        val kcal = runCatching { readAll(client, TotalCaloriesBurnedRecord::class, from, now) }.getOrDefault(emptyList())
        return sessions.map { s ->
            val inside = { a: Instant, b: Instant -> a >= s.startTime && b <= s.endTime }
            WatchWorkout(
                s,
                dist.filter { inside(it.startTime, it.endTime) && isCmf(it.metadata.dataOrigin.packageName) }.sumOf { it.distance.inMeters }.takeIf { it > 0 },
                kcal.filter { inside(it.startTime, it.endTime) && isCmf(it.metadata.dataOrigin.packageName) }.sumOf { it.energy.inKilocalories }.takeIf { it > 0 },
                s.exerciseRouteResult !is ExerciseRouteResult.NoData,
            )
        }.sortedByDescending { it.start }
    }
}
