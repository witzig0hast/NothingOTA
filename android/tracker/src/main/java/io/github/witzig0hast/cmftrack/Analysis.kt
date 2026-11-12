package io.github.witzig0hast.cmftrack

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Analysis {
    /** Heart rate at time t: nearest sample within 15 s. */
    fun hrAt(hr: List<Pair<Long, Int>>, t: Long): Int? {
        if (hr.isEmpty()) return null
        var lo = 0; var hi = hr.size - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (hr[m].first < t) lo = m + 1 else hi = m }
        val best = listOfNotNull(hr.getOrNull(lo), hr.getOrNull(lo - 1)).minByOrNull { kotlin.math.abs(it.first - t) } ?: return null
        return best.second.takeIf { kotlin.math.abs(best.first - t) <= 15_000 }
    }

    /** km splits from stored points (moving time only) with average heart rate per split. */
    fun splits(points: List<TrackPoint>, hr: List<Pair<Long, Int>>): List<Split> {
        val out = mutableListOf<Split>()
        var startT = points.firstOrNull()?.time ?: return out
        var moving = 0.0; var startAlt = points.first().alt
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            if (b.moving) moving += ((b.time - a.time) / 1000.0).coerceAtMost(15.0)
            while (b.distance >= (out.size + 1) * 1000.0) {
                val hrs = hr.filter { it.first in startT..b.time }.map { it.second }
                out += Split(out.size + 1, moving, hrs.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                    if (b.alt != null && startAlt != null) b.alt - startAlt!! else 0.0)
                moving = 0.0; startT = b.time; startAlt = b.alt
            }
        }
        return out
    }

    /** Recomputes heart-rate dependent values (e.g. after loading the watch's heart rate). */
    fun withHeartRate(a: Activity, points: List<TrackPoint>, hr: List<Pair<Long, Int>>, profile: Profile, source: String?): Activity {
        val inRange = hr.filter { it.first in a.start..a.end }
        val avg = inRange.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.toInt()
        return a.copy(
            avgHr = avg, maxHr = inRange.maxOfOrNull { it.second },
            kcal = profile.calories(a.sport, a.movingMs / 1000.0, avg),
            splits = splits(points, inRange).ifEmpty { a.splits }, hrSource = source ?: a.hrSource,
        )
    }
}

object Exporters {
    private fun t(ms: Long) = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()
    private fun f(v: Double, d: Int = 7) = "%.${d}f".format(Locale.US, v)

    fun gpx(a: Activity, pts: List<TrackPoint>, hr: List<Pair<Long, Int>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"CMF Track\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        append("  <metadata><time>${t(a.start)}</time></metadata>\n")
        append("  <trk><name>${a.title}</name><type>${tcxSport(a.sport).lowercase()}</type><trkseg>\n")
        for (p in pts) {
            append("    <trkpt lat=\"${f(p.lat)}\" lon=\"${f(p.lon)}\">")
            p.alt?.let { append("<ele>${f(it, 1)}</ele>") }
            append("<time>${t(p.time)}</time>")
            Analysis.hrAt(hr, p.time)?.let { append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>$it</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>") }
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    private fun tcxSport(s: Sport) = when (s) { Sport.RUN -> "Running"; Sport.RIDE -> "Biking"; else -> "Other" }

    /** TCX (imports into Strava, Garmin Connect, Runalyze … with heart rate and distance). */
    fun tcx(a: Activity, pts: List<TrackPoint>, hr: List<Pair<Long, Int>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">\n")
        append("  <Activities><Activity Sport=\"${tcxSport(a.sport)}\"><Id>${t(a.start)}</Id>\n")
        append("    <Lap StartTime=\"${t(a.start)}\"><TotalTimeSeconds>${a.movingMs / 1000}</TotalTimeSeconds>")
        append("<DistanceMeters>${f(a.distance, 1)}</DistanceMeters><MaximumSpeed>${f(a.maxSpeed, 2)}</MaximumSpeed>")
        append("<Calories>${(a.kcal ?: 0.0).toInt()}</Calories>")
        a.avgHr?.let { append("<AverageHeartRateBpm><Value>$it</Value></AverageHeartRateBpm>") }
        a.maxHr?.let { append("<MaximumHeartRateBpm><Value>$it</Value></MaximumHeartRateBpm>") }
        append("<Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod>\n      <Track>\n")
        for (p in pts) {
            append("        <Trackpoint><Time>${t(p.time)}</Time><Position><LatitudeDegrees>${f(p.lat)}</LatitudeDegrees>")
            append("<LongitudeDegrees>${f(p.lon)}</LongitudeDegrees></Position>")
            p.alt?.let { append("<AltitudeMeters>${f(it, 1)}</AltitudeMeters>") }
            append("<DistanceMeters>${f(p.distance, 1)}</DistanceMeters>")
            Analysis.hrAt(hr, p.time)?.let { append("<HeartRateBpm><Value>$it</Value></HeartRateBpm>") }
            append("</Trackpoint>\n")
        }
        append("      </Track>\n    </Lap>\n    <Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Name>CMF Track</Name></Creator>\n")
        append("  </Activity></Activities>\n</TrainingCenterDatabase>\n")
    }

    /** ZIP in the cmfserver export layout (workouts.csv + heart_rate.csv). */
    fun serverZip(a: Activity, hr: List<Pair<Long, Int>>, out: File): File {
        val z = ZoneId.systemDefault()
        fun iso(ms: Long) = Instant.ofEpochMilli(ms).atZone(z).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).toString()
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("workouts.csv"))
            zip.write(("start,end,duration_min,workout_type,distance_km,avg_hr,max_hr,elevation_gain_m,calories,app\n" +
                "${iso(a.start)},${iso(a.end)},${f(a.movingMs / 60000.0, 1)},${a.sport.label},${f(a.distance / 1000, 2)}," +
                "${a.avgHr ?: ""},${a.maxHr ?: ""},${f(a.elevGain, 0)},${a.kcal?.toInt() ?: ""},cmftrack\n").toByteArray())
            zip.closeEntry()
            if (hr.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("heart_rate.csv"))
                zip.write(("time,bpm\n" + hr.joinToString("") { "${iso(it.first)},${it.second}\n" }).toByteArray())
                zip.closeEntry()
            }
        }
        return out
    }

    fun upload(serverUrl: String, token: String, file: File): String {
        val c = (URL(serverUrl.trimEnd('/') + "/api/upload?name=" + URLEncoder.encode(file.name, "UTF-8")).openConnection() as HttpURLConnection)
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 60_000
            if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/zip")
            c.setFixedLengthStreamingMode(file.length())
            c.outputStream.use { o -> file.inputStream().use { it.copyTo(o) } }
            val code = c.responseCode
            if (code != 200) throw IOException("Server: HTTP $code")
            return "OK"
        } finally { c.disconnect() }
    }
}
