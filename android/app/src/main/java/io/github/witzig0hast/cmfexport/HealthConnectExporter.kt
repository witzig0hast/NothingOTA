package io.github.witzig0hast.cmfexport

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/** Packages whose data counts as "from the CMF watch". */
val CMF_PACKAGES = listOf("com.nothing.cmf.watch", "com.nothing.smartcenter", "com.nothing.cmf")

class HealthConnectExporter(private val client: HealthConnectClient) {

    companion object {
        val RECORD_TYPES: List<KClass<out Record>> = listOf(
            SleepSessionRecord::class, HeartRateRecord::class, RestingHeartRateRecord::class,
            HeartRateVariabilityRmssdRecord::class, OxygenSaturationRecord::class, RespiratoryRateRecord::class,
            StepsRecord::class, DistanceRecord::class, FloorsClimbedRecord::class, SpeedRecord::class,
            ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class, ExerciseSessionRecord::class,
            Vo2MaxRecord::class, WeightRecord::class,
        )
        const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
        val PERMISSIONS: Set<String> = RECORD_TYPES.map { HealthPermission.getReadPermission(it) }.toSet() + HISTORY
        const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    }

    private var onlyCmf = false
    private val origins = sortedMapOf<String, Int>()
    private val notes = mutableListOf<String>()

    private fun keep(r: Record): Boolean {
        val pkg = r.metadata.dataOrigin.packageName
        origins[pkg] = (origins[pkg] ?: 0) + 1
        return !onlyCmf || CMF_PACKAGES.any { pkg.startsWith(it) }
    }

    private fun meta(r: Record) = listOf(
        r.metadata.dataOrigin.packageName,
        r.metadata.device?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ") },
        r.metadata.id,
    )
    private val metaCols = listOf("app", "device", "record_id")

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter, each: (T) -> Unit) {
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(type, range, pageSize = 1000, pageToken = token))
            resp.records.forEach { if (keep(it)) each(it) }
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
    }

    /** Runs [block] for one record type, skipping it (with a note) if permission is missing. */
    private suspend fun section(
        z: ExportZip, granted: Set<String>, type: KClass<out Record>, progress: (String) -> Unit,
        block: suspend () -> Unit,
    ) {
        val name = type.simpleName!!.removeSuffix("Record")
        if (HealthPermission.getReadPermission(type) !in granted) {
            notes += "übersprungen (keine Berechtigung): $name"; return
        }
        progress("Lese $name …")
        try { block() } catch (e: Exception) { notes += "Fehler bei $name: ${e.message}" }
    }

    suspend fun export(daysBack: Long, onlyCmf: Boolean, out: File, progress: (String) -> Unit): ExportResult {
        this.onlyCmf = onlyCmf
        val granted = client.permissionController.getGrantedPermissions()
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(daysBack))
        val range = TimeRangeFilter.between(start, end)
        if (HISTORY !in granted && daysBack > 30) notes += "Hinweis: ohne Berechtigung „Vergangene Daten lesen“ liefert Health Connect nur die letzten 30 Tage vor der ersten Freigabe."

        val days = Days()
        val nights = mutableListOf<SleepNight>()
        var lastHr: Pair<Instant, Int>? = null

        ExportZip(out).use { z ->
            section(z, granted, SleepSessionRecord::class, progress) {
                val stageRows = mutableListOf<List<Any?>>()
                readAll(SleepSessionRecord::class, range) { r ->
                    val m = DoubleArray(4)
                    r.stages.forEach { s ->
                        val min = Duration.between(s.startTime, s.endTime).toMillis() / 60000.0
                        val bucket = when (s.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP -> 0
                            SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> 1
                            SleepSessionRecord.STAGE_TYPE_REM -> 2
                            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> 3
                            else -> -1
                        }
                        if (bucket >= 0) m[bucket] += min
                        stageRows += listOf(iso(s.startTime), iso(s.endTime), r1(min), stageName(s.stage), s.stage, r.metadata.id)
                    }
                    nights += SleepNight(r.startTime, r.endTime, m[0], m[1], m[2], m[3], r.metadata.dataOrigin.packageName, r.stages.isNotEmpty())
                }
                z.table("sleep_sessions.csv", SLEEP_COLUMNS) { nights.forEach { sleepRow(it) } }
                z.table("sleep_stages.csv", listOf("start", "end", "duration_min", "stage", "stage_raw", "session_id")) { stageRows.forEach { row(it) } }
                days.addNights(nights)
            }
            section(z, granted, HeartRateRecord::class, progress) {
                z.table("heart_rate.csv", listOf("time", "bpm") + metaCols) {
                    readAll(HeartRateRecord::class, range) { r ->
                        r.samples.forEach { s ->
                            row(listOf(iso(s.time), s.beatsPerMinute) + meta(r)); days[s.time].hr(s.beatsPerMinute.toInt())
                            if (lastHr == null || s.time >= lastHr!!.first) lastHr = s.time to s.beatsPerMinute.toInt()
                        }
                    }
                }
            }
            section(z, granted, RestingHeartRateRecord::class, progress) {
                z.table("resting_heart_rate.csv", listOf("time", "bpm") + metaCols) {
                    readAll(RestingHeartRateRecord::class, range) { r -> row(listOf(iso(r.time), r.beatsPerMinute) + meta(r)); days[r.time].restingHr = r.beatsPerMinute }
                }
            }
            section(z, granted, HeartRateVariabilityRmssdRecord::class, progress) {
                z.table("hrv_rmssd.csv", listOf("time", "rmssd_ms") + metaCols) {
                    readAll(HeartRateVariabilityRmssdRecord::class, range) { r -> row(listOf(iso(r.time), r.heartRateVariabilityMillis) + meta(r)) }
                }
            }
            section(z, granted, OxygenSaturationRecord::class, progress) {
                z.table("spo2.csv", listOf("time", "spo2_pct") + metaCols) {
                    readAll(OxygenSaturationRecord::class, range) { r -> row(listOf(iso(r.time), r.percentage.value) + meta(r)); days[r.time].spo2(r.percentage.value) }
                }
            }
            section(z, granted, RespiratoryRateRecord::class, progress) {
                z.table("respiratory_rate.csv", listOf("time", "breaths_per_min") + metaCols) {
                    readAll(RespiratoryRateRecord::class, range) { r -> row(listOf(iso(r.time), r.rate) + meta(r)) }
                }
            }
            section(z, granted, StepsRecord::class, progress) {
                z.table("steps.csv", listOf("start", "end", "steps") + metaCols) {
                    readAll(StepsRecord::class, range) { r -> row(listOf(iso(r.startTime), iso(r.endTime), r.count) + meta(r)); days[r.startTime].steps += r.count }
                }
            }
            section(z, granted, DistanceRecord::class, progress) {
                z.table("distance.csv", listOf("start", "end", "meters") + metaCols) {
                    readAll(DistanceRecord::class, range) { r -> row(listOf(iso(r.startTime), iso(r.endTime), r1(r.distance.inMeters)) + meta(r)); days[r.startTime].distanceM += r.distance.inMeters }
                }
            }
            section(z, granted, FloorsClimbedRecord::class, progress) {
                z.table("floors.csv", listOf("start", "end", "floors") + metaCols) {
                    readAll(FloorsClimbedRecord::class, range) { r -> row(listOf(iso(r.startTime), iso(r.endTime), r.floors) + meta(r)) }
                }
            }
            section(z, granted, SpeedRecord::class, progress) {
                z.table("speed.csv", listOf("time", "m_per_s") + metaCols) {
                    readAll(SpeedRecord::class, range) { r -> r.samples.forEach { s -> row(listOf(iso(s.time), s.speed.inMetersPerSecond) + meta(r)) } }
                }
            }
            section(z, granted, ActiveCaloriesBurnedRecord::class, progress) {
                z.table("active_calories.csv", listOf("start", "end", "kcal") + metaCols) {
                    readAll(ActiveCaloriesBurnedRecord::class, range) { r -> row(listOf(iso(r.startTime), iso(r.endTime), r1(r.energy.inKilocalories)) + meta(r)); days[r.startTime].kcal += r.energy.inKilocalories }
                }
            }
            section(z, granted, TotalCaloriesBurnedRecord::class, progress) {
                z.table("total_calories.csv", listOf("start", "end", "kcal") + metaCols) {
                    readAll(TotalCaloriesBurnedRecord::class, range) { r -> row(listOf(iso(r.startTime), iso(r.endTime), r1(r.energy.inKilocalories)) + meta(r)) }
                }
            }
            section(z, granted, ExerciseSessionRecord::class, progress) {
                val gpx = mutableListOf<Pair<String, String>>()
                z.table("workouts.csv", listOf("start", "end", "duration_min", "exercise_type", "title", "notes", "route") + metaCols) {
                    readAll(ExerciseSessionRecord::class, range) { r ->
                        val route = when (val rr = r.exerciseRouteResult) {
                            is ExerciseRouteResult.Data -> {
                                gpx += "gpx/workout_${iso(r.startTime)!!.take(19).replace(':', '-')}.gpx" to gpx(r, rr)
                                "${rr.exerciseRoute.route.size} Punkte"
                            }
                            is ExerciseRouteResult.ConsentRequired -> "vorhanden (Freigabe nötig)"
                            else -> ""
                        }
                        row(listOf(iso(r.startTime), iso(r.endTime), r1(Duration.between(r.startTime, r.endTime).toMillis() / 60000.0),
                            r.exerciseType, r.title, r.notes, route) + meta(r))
                    }
                }
                gpx.forEach { (p, x) -> z.text(p, x) }
            }
            section(z, granted, Vo2MaxRecord::class, progress) {
                z.table("vo2max.csv", listOf("time", "ml_per_min_kg") + metaCols) {
                    readAll(Vo2MaxRecord::class, range) { r -> row(listOf(iso(r.time), r.vo2MillilitersPerMinuteKilogram) + meta(r)) }
                }
            }
            section(z, granted, WeightRecord::class, progress) {
                z.table("weight.csv", listOf("time", "kg") + metaCols) {
                    readAll(WeightRecord::class, range) { r -> row(listOf(iso(r.time), r.weight.inKilograms) + meta(r)) }
                }
            }

            z.writeDaily(days)
            val html = Report.html("Health Connect" + if (onlyCmf) " (nur CMF-App)" else "", nights, days)
            z.text("report.html", html)
            val header = buildString {
                appendLine("Quelle: Health Connect, letzte $daysBack Tage" + if (onlyCmf) ", nur CMF-App" else "")
                appendLine("Apps mit Daten in Health Connect:")
                origins.forEach { (pkg, n) -> appendLine("  - $pkg ($n)" + if (CMF_PACKAGES.any { pkg.startsWith(it) }) "  ← CMF" else "") }
                if (origins.isEmpty()) appendLine("  (keine – CMF-App → Health Connect Sync aktiviert?)")
                notes.forEach { appendLine(it) }
            }
            val counts = z.counts.filterKeys { it != "daily_summary" }
            val (steps, stepsDay) = Dashboard.today(days)
            val dash = Dashboard(nights.maxByOrNull { it.end }, steps, stepsDay, lastHr?.second, lastHr?.first)
            return ExportResult(out, summaryText(header, counts, nights), html, dash)
        }
    }

    private fun stageName(s: Int) = when (s) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
        else -> "unknown"
    }

    private fun gpx(r: ExerciseSessionRecord, d: ExerciseRouteResult.Data) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"CMF Export\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk><name>${r.title ?: "Workout"} ${iso(r.startTime)}</name>\n    <trkseg>\n")
        d.exerciseRoute.route.forEach { p ->
            append("      <trkpt lat=\"%.7f\" lon=\"%.7f\">".format(java.util.Locale.US, p.latitude, p.longitude))
            p.altitude?.let { append("<ele>%.1f</ele>".format(java.util.Locale.US, it.inMeters)) }
            append("<time>${p.time}</time></trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }
}
