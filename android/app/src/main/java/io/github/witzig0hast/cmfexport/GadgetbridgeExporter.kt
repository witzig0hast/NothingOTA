package io.github.witzig0hast.cmfexport

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * Reads a Gadgetbridge database export (Gadgetbridge.db or the .zip export) and
 * extracts every CMF Watch table. Schema: Gadgetbridge GBDaoGenerator.java.
 */
class GadgetbridgeExporter(private val ctx: Context) {

    fun export(uri: Uri, out: File, progress: (String) -> Unit): ExportResult {
        progress("Lese Datei …")
        val dbFile = copyDatabase(uri)
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return exportDb(db, out, progress)
        } finally {
            db.close()
            dbFile.delete()
        }
    }

    /** Copies the picked file to cache; unpacks it if it is a zip export. */
    private fun copyDatabase(uri: Uri): File {
        val dst = File(ctx.cacheDir, "gb-import.db")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            val head = ByteArray(4)
            val buffered = input.buffered()
            buffered.mark(8); buffered.read(head); buffered.reset()
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                ZipInputStream(buffered).use { zip ->
                    var found = false
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val name = e.name.substringAfterLast('/')
                        if (!e.isDirectory && (name == "Gadgetbridge" || name == "Gadgetbridge.db" || (!found && name.endsWith(".db")))) {
                            dst.outputStream().use { zip.copyTo(it) }
                            found = true
                            if (name.startsWith("Gadgetbridge")) break
                        }
                    }
                    if (!found) error("Keine Gadgetbridge-Datenbank im ZIP gefunden")
                }
            } else dst.outputStream().use { buffered.copyTo(it) }
        }
        return dst
    }

    private fun exportDb(db: SQLiteDatabase, out: File, progress: (String) -> Unit): ExportResult {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        if (tables.none { it.startsWith("CMF_") }) error("Keine CMF-Tabellen in der Datenbank – ist das ein Gadgetbridge-Export mit gekoppelter CMF-Uhr?")

        val devices = StringBuilder()
        if ("DEVICE" in tables) db.rawQuery("SELECT * FROM DEVICE", null).use { c ->
            while (c.moveToNext()) devices.appendLine("  - " + (0 until c.columnCount).joinToString(", ") { "${c.getColumnName(it)}=${c.str(it)}" })
        }

        val days = Days()
        val nights = mutableListOf<SleepNight>()
        var sleep = Sleep.Grouped(emptyList(), emptyList())
        val gps = mutableListOf<Triple<Long, Double, Double>>()
        var lastBattery: Int? = null
        var lastBatteryTime: Instant? = null
        var lastHr: Pair<Instant, Int>? = null

        ExportZip(out).use { z ->
            if ("CMF_ACTIVITY_SAMPLE" in tables) {
                progress("Aktivität …")
                z.table("activity.csv", listOf("time", "timestamp", "steps", "distance_m", "calories", "heart_rate", "raw_kind", "raw_intensity", "device_id")) {
                    query(db, "CMF_ACTIVITY_SAMPLE", "TIMESTAMP") { c ->
                        val ts = c.long("TIMESTAMP")!!
                        val i = Instant.ofEpochSecond(ts)
                        val steps = c.long("STEPS") ?: 0; val dist = c.long("DISTANCE") ?: 0; val cal = c.long("CALORIES") ?: 0
                        val hr = c.long("HEART_RATE")
                        row(iso(i), ts, steps, dist, cal, hr, c.long("RAW_KIND"), c.long("RAW_INTENSITY"), c.long("DEVICE_ID"))
                        val d = days[i]; d.steps += steps; d.distanceM += dist; d.kcal += cal
                        hr?.let { d.hr(it.toInt()) }
                    }
                }
            }
            simple(z, db, tables, "CMF_HEART_RATE_SAMPLE", "heart_rate.csv", "HEART_RATE", progress) { i, v ->
                days[i].hr(v.toInt()); if (v in 30..230) lastHr = i to v.toInt()
            }
            simple(z, db, tables, "CMF_SPO2_SAMPLE", "spo2.csv", "SPO2", progress) { i, v -> days[i].spo2(v.toDouble()) }
            simple(z, db, tables, "CMF_STRESS_SAMPLE", "stress.csv", "STRESS", progress) { i, v -> days[i].stress(v.toInt()) }

            // Watch battery (seconds timestamps; BATTERY_INDEX 0 = main battery)
            if ("BATTERY_LEVEL" in tables) {
                z.table("battery.csv", listOf("time", "level", "device_id")) {
                    query(db, "BATTERY_LEVEL", "TIMESTAMP") { c ->
                        if ((c.long("BATTERY_INDEX") ?: 0L) != 0L) return@query
                        val ts = c.long("TIMESTAMP") ?: return@query
                        val lvl = c.long("LEVEL") ?: return@query
                        row(iso(Instant.ofEpochSecond(ts)), lvl, c.long("DEVICE_ID"))
                        lastBattery = lvl.toInt(); lastBatteryTime = Instant.ofEpochSecond(ts)
                    }
                }
            }

            // Sleep
            progress("Schlaf …")
            data class Stage(val deviceId: Long, val ts: Long, val dur: Long, val stage: Int)
            val stages = mutableListOf<Stage>()
            if ("CMF_SLEEP_STAGE_SAMPLE" in tables) {
                z.table("sleep_stages.csv", listOf("start", "end", "duration_s", "stage", "stage_raw", "device_id")) {
                    query(db, "CMF_SLEEP_STAGE_SAMPLE", "TIMESTAMP") { c ->
                        val s = Stage(c.long("DEVICE_ID")!!, c.long("TIMESTAMP")!!, c.long("DURATION")!!, c.long("STAGE")!!.toInt())
                        stages += s
                        row(isoMs(s.ts), isoMs(s.ts + s.dur * 1000), s.dur, stageName(s.stage), s.stage, s.deviceId)
                    }
                }
            }
            if ("CMF_SLEEP_SESSION_SAMPLE" in tables) {
                query(db, "CMF_SLEEP_SESSION_SAMPLE", "TIMESTAMP") { c ->
                    val dev = c.long("DEVICE_ID")!!
                    val start = c.long("TIMESTAMP")!!
                    val end = c.long("WAKEUP_TIME") ?: start
                    val mins = DoubleArray(4) // deep, light, rem, awake
                    stages.filter { it.deviceId == dev && it.ts >= start && it.ts < end }.forEach {
                        mins[when (it.stage) { 1 -> 0; 2 -> 1; 3 -> 2; else -> 3 }] += it.dur / 60.0
                    }
                    nights += SleepNight(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), mins[0], mins[1], mins[2], mins[3], "Gadgetbridge")
                }
            }
            sleep = Sleep.group(nights)
            z.writeSleep(nights, sleep)
            days.addSleep(sleep)

            // Workouts + GPS
            progress("Trainings …")
            if ("CMF_WORKOUT_GPS_SAMPLE" in tables) {
                z.table("workout_gps.csv", listOf("time", "lat", "lon", "device_id")) {
                    query(db, "CMF_WORKOUT_GPS_SAMPLE", "TIMESTAMP") { c ->
                        val ts = c.long("TIMESTAMP")!!
                        val lat = (c.long("LATITUDE") ?: 0) / 1e7; val lon = (c.long("LONGITUDE") ?: 0) / 1e7
                        gps += Triple(ts, lat, lon)
                        row(isoMs(ts), lat, lon, c.long("DEVICE_ID"))
                    }
                }
            }
            val gpxFiles = mutableListOf<Pair<String, String>>()
            if ("BASE_ACTIVITY_SUMMARY" in tables) {
                z.table("workouts.csv", listOf("id", "start", "end", "duration_min", "workout_type", "workout_type_code", "active_seconds", "has_gps", "gps_points", "raw_summary_hex", "device_id")) {
                    query(db, "BASE_ACTIVITY_SUMMARY", "START_TIME") { c ->
                        val start = c.long("START_TIME")!!; val end = c.long("END_TIME")!!
                        val raw = c.blob("RAW_SUMMARY_DATA")
                        var type: String? = c.str("NAME"); var code: Int? = null; var active: Int? = null; var hasGps: Boolean? = null
                        if (raw != null && raw.size >= 30) {
                            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                            active = b.getShort(4).toInt(); code = raw[6].toInt() and 0xFF
                            type = WORKOUT_TYPES[code] ?: "UNKNOWN_0x%02X".format(code)
                            hasGps = raw.size > 30 && raw[30].toInt() == 1
                        }
                        val pts = gps.filter { it.first in start..end }
                        if (pts.isNotEmpty()) gpxFiles += "gpx/workout_${c.long("_id")}_${iso(Instant.ofEpochMilli(start))!!.take(19).replace(':', '-')}.gpx" to gpx(type ?: "Workout", start, pts)
                        row(c.long("_id"), isoMs(start), isoMs(end), r1((end - start) / 60000.0), type, code, active, hasGps, pts.size,
                            raw?.joinToString("") { "%02x".format(it) }, c.long("DEVICE_ID"))
                    }
                }
            }
            gpxFiles.forEach { (p, x) -> z.text(p, x) }

            // Every CMF table 1:1 for anything not covered above
            progress("Rohdaten …")
            tables.filter { it.startsWith("CMF_") || it in setOf("DEVICE", "BASE_ACTIVITY_SUMMARY", "BATTERY_LEVEL") }.sorted().forEach { t ->
                db.rawQuery("SELECT * FROM \"$t\"", null).use { c ->
                    z.table("raw/$t.csv", c.columnNames.toList()) {
                        while (c.moveToNext()) row((0 until c.columnCount).map { c.str(it) })
                    }
                }
            }
            z.writeDaily(days)
            val html = Report.html("Gadgetbridge", sleep, days)
            z.text("report.html", html)
            val counts = z.counts.filterKeys { !it.startsWith("CMF_") && it !in setOf("DEVICE", "BASE_ACTIVITY_SUMMARY", "BATTERY_LEVEL") }
            val (steps, stepsDay) = Dashboard.today(days)
            val dash = Dashboard(sleep.nights.lastOrNull(), steps, stepsDay, lastHr?.second, lastHr?.first, lastBattery, lastBatteryTime, nap = Dashboard.napToday(sleep))
            return ExportResult(out, summaryText("Quelle: Gadgetbridge\nGeräte:\n$devices", counts, sleep), html, dash, TrendDay.of(days))
        }
    }

    private fun simple(
        z: ExportZip, db: SQLiteDatabase, tables: Set<String>, table: String, file: String, col: String,
        progress: (String) -> Unit, agg: (Instant, Long) -> Unit,
    ) {
        if (table !in tables) return
        progress(file.removeSuffix(".csv") + " …")
        z.table(file, listOf("time", "timestamp_ms", col.lowercase(), "device_id")) {
            query(db, table, "TIMESTAMP") { c ->
                val ts = c.long("TIMESTAMP")!!; val v = c.long(col) ?: return@query
                row(isoMs(ts), ts, v, c.long("DEVICE_ID"))
                agg(Instant.ofEpochMilli(ts), v)
            }
        }
    }

    private fun query(db: SQLiteDatabase, table: String, order: String, each: (Cursor) -> Unit) =
        db.rawQuery("SELECT * FROM \"$table\" ORDER BY \"$order\"", null).use { c -> while (c.moveToNext()) each(c) }

    private fun stageName(s: Int) = when (s) { 1 -> "deep"; 2 -> "light"; 3 -> "rem"; else -> "awake" }

    private fun gpx(name: String, start: Long, pts: List<Triple<Long, Double, Double>>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"CMF Export\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk><name>$name ${isoMs(start)}</name>\n    <trkseg>\n")
        pts.forEach { (t, lat, lon) ->
            append("      <trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time></trkpt>\n".format(java.util.Locale.US, lat, lon, Instant.ofEpochMilli(t)))
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }
}

private fun Cursor.idx(name: String) = getColumnIndex(name)
fun Cursor.long(name: String): Long? = idx(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
fun Cursor.str(name: String): String? = idx(name).let { if (it < 0 || isNull(it)) null else str(it) }
fun Cursor.blob(name: String): ByteArray? = idx(name).let { if (it < 0 || isNull(it) || getType(it) != Cursor.FIELD_TYPE_BLOB) null else getBlob(it) }
fun Cursor.str(i: Int): String? = when (getType(i)) {
    Cursor.FIELD_TYPE_NULL -> null
    Cursor.FIELD_TYPE_BLOB -> getBlob(i).joinToString("") { "%02x".format(it) }
    else -> getString(i)
}
