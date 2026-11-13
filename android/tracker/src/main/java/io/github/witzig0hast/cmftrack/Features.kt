package io.github.witzig0hast.cmftrack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.speech.tts.TextToSpeech
import android.util.Xml
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ------------------------------------------------------------------------------------------ best efforts
object BestEfforts {
    val TARGETS = listOf(400.0 to "400 m", 1000.0 to "1 km", 1609.34 to "1 Meile", 5000.0 to "5 km",
        10_000.0 to "10 km", 21_097.5 to "Halbmarathon", 42_195.0 to "Marathon")

    /** Fastest moving time (s) for each standard distance contained in the track. */
    fun compute(points: List<TrackPoint>): Map<String, Double> {
        if (points.size < 2) return emptyMap()
        val mt = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val dt = ((points[i].time - points[i - 1].time) / 1000.0).coerceAtMost(15.0)
            mt[i] = mt[i - 1] + if (points[i].moving) dt else 0.0
        }
        val out = linkedMapOf<String, Double>()
        for ((d, label) in TARGETS) {
            if (points.last().distance < d) break
            var j = 0; var best = Double.MAX_VALUE
            for (i in points.indices) {
                while (j + 1 < i && points[i].distance - points[j + 1].distance >= d) j++
                val span = points[i].distance - points[j].distance
                if (span >= d && mt[i] > mt[j]) best = min(best, (mt[i] - mt[j]) * d / span)
            }
            if (best < Double.MAX_VALUE) out[label] = best
        }
        return out
    }

    /** Personal record per distance over all activities of a sport: label -> (seconds, activity). */
    fun records(all: List<Activity>): Map<String, Pair<Double, Activity>> {
        val out = linkedMapOf<String, Pair<Double, Activity>>()
        for ((_, label) in TARGETS) {
            all.mapNotNull { a -> a.efforts[label]?.let { it to a } }.minByOrNull { it.first }?.let { out[label] = it }
        }
        return out
    }
}

// ------------------------------------------------------------------------------------------ weekly stats
object Stats {
    data class Week(val start: LocalDate, val distance: Double, val movingMs: Long, val count: Int, val elev: Double)

    private val wf = WeekFields.of(Locale.GERMANY)
    fun weekStart(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().with(wf.dayOfWeek(), 1)

    fun weeks(all: List<Activity>, n: Int = 12): List<Week> {
        val thisWeek = weekStart(System.currentTimeMillis())
        val byWeek = all.groupBy { weekStart(it.start) }
        return (n - 1 downTo 0).map { k ->
            val ws = thisWeek.minusWeeks(k.toLong())
            val list = byWeek[ws].orEmpty()
            Week(ws, list.sumOf { it.distance }, list.sumOf { it.movingMs }, list.size, list.sumOf { it.elevGain })
        }
    }

    /** Consecutive weeks (up to now) with at least one activity. */
    fun streak(all: List<Activity>): Int {
        val weeks = all.map { weekStart(it.start) }.toSet()
        var w = weekStart(System.currentTimeMillis()); var n = 0
        if (w !in weeks) w = w.minusWeeks(1) // the current week may still come
        while (w in weeks) { n++; w = w.minusWeeks(1) }
        return n
    }
}

// ------------------------------------------------------------------------------------------ voice coach
class Voice(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false
    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.GERMAN
    }
    fun say(text: String) { if (ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString()) }
    fun shutdown() = tts.shutdown()

    companion object {
        fun duration(sec: Double): String {
            val s = sec.toLong(); val h = s / 3600; val m = s / 60 % 60; val r = s % 60
            return listOfNotNull(if (h > 0) "$h Stunde${if (h > 1) "n" else ""}" else null, if (m > 0) "$m Minute${if (m != 1L) "n" else ""}" else null,
                if (h == 0L && r > 0) "$r Sekunde${if (r != 1L) "n" else ""}" else null).joinToString(" ")
        }
        fun pace(sport: Sport, mps: Double): String = if (sport.showPace) {
            val s = (1000 / mps).toLong(); "${s / 60} ${if (s % 60 > 0) "${s % 60}" else ""} pro Kilometer"
        } else "%.1f Kilometer pro Stunde".format(Locale.GERMAN, mps * 3.6)

        fun split(sport: Sport, km: Int, split: Split, total: Double, avgSpeed: Double, hr: Int?): String =
            "Kilometer $km. Zeit ${duration(total)}. Letzter Kilometer ${duration(split.seconds)}. " +
                "Durchschnitt ${pace(sport, avgSpeed)}." + (hr?.let { " Puls $it." } ?: "")
    }
}

// ------------------------------------------------------------------------------------------ Health Connect: write activities
object HealthWrite {
    val PERMISSIONS = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
    )

    fun exerciseType(s: Sport) = when (s) {
        Sport.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING; Sport.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        Sport.HIKE -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING; Sport.RIDE -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        Sport.INLINE -> ExerciseSessionRecord.EXERCISE_TYPE_SKATING; Sport.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    /** Saves the activity (session with GPS route, distance, active calories) to Health Connect. */
    suspend fun save(client: HealthConnectClient, a: Activity, points: List<TrackPoint>) {
        val granted = client.permissionController.getGrantedPermissions()
        val start = Instant.ofEpochMilli(a.start); val end = Instant.ofEpochMilli(max(a.end, a.start + 1000))
        val off = ZoneId.systemDefault().rules.getOffset(start)
        val phone = Device(type = Device.TYPE_PHONE)
        val route = if (HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE in granted && points.size > 1)
            ExerciseRoute(points.filter { it.time in a.start..end.toEpochMilli() }.distinctBy { it.time }.map {
                ExerciseRoute.Location(Instant.ofEpochMilli(it.time), it.lat, it.lon, null, null, it.alt?.let(Length::meters))
            }) else null
        val records = buildList {
            add(ExerciseSessionRecord(start, off, end, off, Metadata.activelyRecorded(phone), exerciseType(a.sport), a.title, a.notes,
                emptyList(), emptyList(), route, null))
            if (a.distance > 0 && HealthPermission.getWritePermission(DistanceRecord::class) in granted)
                add(DistanceRecord(start, off, end, off, Length.meters(a.distance), Metadata.activelyRecorded(phone)))
            if ((a.kcal ?: 0.0) > 0 && HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class) in granted)
                add(ActiveCaloriesBurnedRecord(start, off, end, off, Energy.kilocalories(a.kcal!!), Metadata.activelyRecorded(phone)))
        }
        client.insertRecords(records)
    }
}

// ------------------------------------------------------------------------------------------ import workouts recorded on the watch
object WatchImport {
    /** Creates an activity from a watch workout. [route] may come from the per-session consent dialog. */
    suspend fun import(ctx: Context, client: HealthConnectClient, w: Watch.WatchWorkout, route: ExerciseRoute?): Long {
        val store = Store(ctx)
        store.byWatchId(w.id)?.let { return it.id }
        val id = store.createImported(w.sport, w.start, w.id)
        val locations = route?.route ?: (w.record.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.route
        val stats = TrackStats(w.sport)
        locations?.forEach { l ->
            val fix = Fix(l.time.toEpochMilli(), l.latitude, l.longitude, l.altitude?.inMeters, l.horizontalAccuracy?.inMeters?.toFloat() ?: 8f)
            if (stats.addFix(fix)) store.addPoint(id, stats.points.last())
        }
        val hr = Watch.heartRate(client, w.start, w.end)
        store.replaceHr(id, hr.samples)
        val durationMs = w.end - w.start
        val distance = w.distance ?: stats.distance
        var a = Activity(id, w.sport, w.start, w.end, distance,
            if (stats.movingMs > 0) stats.movingMs else durationMs, stats.elevationGain, stats.elevationLoss,
            null, null, w.kcal, stats.maxSpeed, stats.splits, null, true,
            name = w.record.title?.takeIf { it.isNotBlank() }, notes = w.record.notes, origin = "watch", watchId = w.id)
        val pts = store.points(id)
        a = Analysis.withHeartRate(a, pts, hr.samples, Prefs(ctx).profile, hr.source).let { if (w.kcal != null) it.copy(kcal = w.kcal) else it }
        a = a.copy(efforts = BestEfforts.compute(pts))
        store.save(a)
        return id
    }
}

// ------------------------------------------------------------------------------------------ background: fetch the watch's heart rate later
/** The CMF app syncs heart rate only every now and then: retry for recent activities without heart rate. */
class HeartRateBackfill(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        fun schedule(ctx: Context) = runCatching { // never let background scheduling break a recording
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("hr-backfill", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HeartRateBackfill>(15, TimeUnit.MINUTES).setConstraints(Constraints.NONE).build())
        }

        /** Tries all finished activities of the last 12 h that have no heart rate yet. Returns updated count. */
        suspend fun run(ctx: Context): Int {
            if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) return 0
            val client = HealthConnectClient.getOrCreate(ctx)
            if (Watch.HR !in client.permissionController.getGrantedPermissions()) return 0
            val store = Store(ctx); val prefs = Prefs(ctx)
            var n = 0
            for (a in store.list().filter { it.finished && it.avgHr == null && it.end > System.currentTimeMillis() - 12 * 3_600_000L }) {
                val r = Watch.heartRate(client, a.start, a.end)
                if (r.samples.none { it.first in a.start..a.end }) continue
                store.replaceHr(a.id, r.samples)
                val updated = Analysis.withHeartRate(a, store.points(a.id), r.samples, prefs.profile, r.source)
                store.save(updated); n++
                if (prefs.autoUpload && prefs.serverUrl.startsWith("http")) runCatching {
                    Exporters.upload(prefs.serverUrl, prefs.token, Exporters.serverZip(updated, r.samples, java.io.File(ctx.cacheDir, "w${a.id}.zip"), store.points(a.id)))
                }
            }
            return n
        }
    }

    override suspend fun doWork(): Result { runCatching { run(applicationContext) }; return Result.success() }
}

// ------------------------------------------------------------------------------------------ share image
object ShareCard {
    /** 1080x1350 picture with route and key numbers, like the big tracking apps. */
    fun render(ctx: Context, a: Activity, points: List<TrackPoint>): Bitmap {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0E0E0E.toInt())
        val route = RouteView(ctx).apply { dark = true; set(points); measure(0, 0); layout(0, 0, w, 820) }
        c.save(); c.translate(0f, 120f); route.draw(c); c.restore()

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 64f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A9A9A.toInt(); textSize = 34f; letterSpacing = 0.08f }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 92f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt(); textSize = 34f; typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = 0.1f }
        val date = Instant.ofEpochMilli(a.start).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd. MMMM yyyy", Locale.GERMAN))
        c.drawText(a.title, 64f, 80f, title)
        c.drawText(date.uppercase(), 64f, 120f + 12f, small.apply { textSize = 28f })
        small.textSize = 34f
        val stats = listOf(
            "DISTANZ" to "${Fmt.km(a.distance)} km",
            "ZEIT" to Fmt.duration(a.movingMs / 1000.0),
            (if (a.sport.showPace) "TEMPO" else "SPEED") to (if (a.sport.showPace) Fmt.pace(a.avgSpeed) + " /km" else Fmt.kmh(a.avgSpeed) + " km/h"),
            (if (a.avgHr != null) "Ø PULS" else "HÖHENMETER") to (a.avgHr?.let { "$it bpm" } ?: "%.0f m".format(a.elevGain)),
        )
        stats.forEachIndexed { i, (k, v) ->
            val x = 64f + (i % 2) * 500f; val y = 1010f + (i / 2) * 170f
            c.drawText(k, x, y, small); c.drawText(v, x, y + 92f, big)
        }
        c.drawText("CMF TRACK", 64f, h - 48f, brand)
        return bmp
    }
}
