package io.github.witzig0hast.cmfexport

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val zone: ZoneId get() = ZoneId.systemDefault()

fun iso(i: Instant?): String? =
    i?.atZone(zone)?.toOffsetDateTime()?.truncatedTo(ChronoUnit.SECONDS)?.toString()

fun isoMs(ms: Long?): String? = ms?.let { iso(Instant.ofEpochMilli(it)) }

fun day(i: Instant): LocalDate = i.atZone(zone).toLocalDate()

fun hm(min: Double): String {
    val m = Math.round(min).toInt()
    return "%dh%02d".format(m / 60, m % 60)
}

/** One night of sleep. Minutes per stage. */
data class SleepNight(
    val start: Instant,
    val end: Instant,
    val deep: Double,
    val light: Double,
    val rem: Double,
    val awake: Double,
    val source: String?,
    val hasStages: Boolean = true,
) {
    val inBed: Double get() = (end.toEpochMilli() - start.toEpochMilli()) / 60000.0
    val asleep: Double get() = if (hasStages) deep + light + rem else inBed - awake
    val efficiency: Double? get() = if (inBed > 0) 100 * asleep / inBed else null
}

/** Per-day aggregation filled while streaming samples. */
class Day {
    var steps = 0L
    var distanceM = 0.0
    var kcal = 0.0
    var hrSum = 0L; var hrN = 0; var hrMin = Int.MAX_VALUE; var hrMax = 0
    var spo2Sum = 0.0; var spo2N = 0
    var stressSum = 0L; var stressN = 0
    var restingHr: Long? = null
    var sleepMin = 0.0; var deepMin = 0.0; var lightMin = 0.0; var remMin = 0.0; var awakeMin = 0.0; var nights = 0

    fun hr(v: Int) { if (v in 1..254) { hrSum += v; hrN++; hrMin = minOf(hrMin, v); hrMax = maxOf(hrMax, v) } }
    fun spo2(v: Double) { if (v > 0 && v <= 100) { spo2Sum += v; spo2N++ } }
    fun stress(v: Int) { if (v in 1..100) { stressSum += v; stressN++ } }
}

class Days {
    val map = TreeMap<LocalDate, Day>()
    operator fun get(i: Instant): Day = map.getOrPut(day(i)) { Day() }

    fun addNights(nights: List<SleepNight>) = nights.forEach { n ->
        val d = this[n.end] // a night belongs to the day you wake up
        d.nights++; d.sleepMin += n.asleep; d.deepMin += n.deep; d.lightMin += n.light; d.remMin += n.rem; d.awakeMin += n.awake
    }

    val columns = listOf(
        "date", "steps", "distance_m", "calories_kcal", "hr_avg", "hr_min", "hr_max", "resting_hr",
        "spo2_avg", "stress_avg", "sleep_min", "deep_min", "light_min", "rem_min", "awake_min",
    )

    fun row(date: LocalDate, d: Day): List<Any?> = listOf(
        date, d.steps, r1(d.distanceM), r1(d.kcal),
        if (d.hrN > 0) r1(d.hrSum.toDouble() / d.hrN) else null,
        if (d.hrN > 0) d.hrMin else null, if (d.hrN > 0) d.hrMax else null, d.restingHr,
        if (d.spo2N > 0) r1(d.spo2Sum / d.spo2N) else null,
        if (d.stressN > 0) r1(d.stressSum.toDouble() / d.stressN) else null,
        if (d.nights > 0) r1(d.sleepMin) else null, if (d.nights > 0) r1(d.deepMin) else null,
        if (d.nights > 0) r1(d.lightMin) else null, if (d.nights > 0) r1(d.remMin) else null,
        if (d.nights > 0) r1(d.awakeMin) else null,
    )
}

fun r1(v: Double): Double = Math.round(v * 10) / 10.0

/** Streams CSV tables (and other files) into one ZIP archive. */
class ExportZip(val file: File) : AutoCloseable {
    @PublishedApi internal val zip = ZipOutputStream(file.outputStream().buffered())
    @PublishedApi internal val w = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
    val counts = LinkedHashMap<String, Int>()

    inner class Table(private val name: String) {
        var n = 0
        fun row(values: List<Any?>) {
            w.write(values.joinToString(",") { esc(it) }); w.write("\n"); n++
            counts[name] = n
        }
        fun row(vararg values: Any?) = row(values.toList())
    }

    inline fun table(path: String, columns: List<String>, block: Table.() -> Unit): Int {
        zip.putNextEntry(ZipEntry(path))
        w.write(columns.joinToString(",") { esc(it) }); w.write("\n")
        val t = Table(path.substringAfterLast('/').removeSuffix(".csv"))
        counts.putIfAbsent(path.substringAfterLast('/').removeSuffix(".csv"), 0)
        try { t.block() } finally { w.flush(); zip.closeEntry() }
        return t.n
    }

    fun text(path: String, content: String) {
        zip.putNextEntry(ZipEntry(path)); w.write(content); w.flush(); zip.closeEntry()
    }

    override fun close() { w.flush(); zip.finish(); zip.close() }

    @PublishedApi internal fun esc(v: Any?): String {
        if (v == null) return ""
        val s = v.toString()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

val SLEEP_COLUMNS = listOf(
    "night_of", "fell_asleep", "woke_up", "time_in_bed_min", "asleep_min", "deep_min", "light_min",
    "rem_min", "awake_min", "efficiency_pct", "source",
)

fun ExportZip.Table.sleepRow(n: SleepNight) = row(
    day(n.start), iso(n.start), iso(n.end), r1(n.inBed), r1(n.asleep), r1(n.deep), r1(n.light),
    r1(n.rem), r1(n.awake), n.efficiency?.let { r1(it) }, n.source,
)

fun ExportZip.writeDaily(days: Days) = table("daily_summary.csv", days.columns) {
    days.map.forEach { (date, d) -> row(days.row(date, d)) }
}

data class ExportResult(val zip: File, val summary: String, val reportHtml: String, val dash: Dashboard = Dashboard())

/** What the start screen shows; merged across exports/syncs and persisted as JSON. */
data class Dashboard(
    val night: SleepNight? = null,
    val stepsToday: Long? = null,
    val stepsDay: String? = null,
    val hr: Int? = null,
    val hrTime: Instant? = null,
    val battery: Int? = null,
    val batteryTime: Instant? = null,
) {
    fun merge(o: Dashboard) = Dashboard(
        newer(o.night, night) { it.end }, o.stepsToday ?: stepsToday, o.stepsDay ?: stepsDay,
        if (o.hrTime != null && (hrTime == null || o.hrTime >= hrTime)) o.hr else hr,
        if (o.hrTime != null && (hrTime == null || o.hrTime >= hrTime)) o.hrTime else hrTime,
        if (o.batteryTime != null && (batteryTime == null || o.batteryTime >= batteryTime)) o.battery else battery,
        if (o.batteryTime != null && (batteryTime == null || o.batteryTime >= batteryTime)) o.batteryTime else batteryTime,
    )

    private fun <T> newer(a: T?, b: T?, key: (T) -> Instant): T? =
        if (a == null) b else if (b == null) a else if (key(a) >= key(b)) a else b

    fun toJson(): String = org.json.JSONObject().apply {
        night?.let { n ->
            put("night", org.json.JSONObject().put("start", n.start.toEpochMilli()).put("end", n.end.toEpochMilli())
                .put("deep", n.deep).put("light", n.light).put("rem", n.rem).put("awake", n.awake)
                .put("source", n.source ?: "").put("stages", n.hasStages))
        }
        stepsToday?.let { put("steps", it) }; stepsDay?.let { put("stepsDay", it) }
        hr?.let { put("hr", it) }; hrTime?.let { put("hrTime", it.toEpochMilli()) }
        battery?.let { put("battery", it) }; batteryTime?.let { put("batteryTime", it.toEpochMilli()) }
    }.toString()

    companion object {
        fun fromJson(s: String?): Dashboard = runCatching {
            val o = org.json.JSONObject(s!!)
            val n = o.optJSONObject("night")?.let {
                SleepNight(Instant.ofEpochMilli(it.getLong("start")), Instant.ofEpochMilli(it.getLong("end")),
                    it.getDouble("deep"), it.getDouble("light"), it.getDouble("rem"), it.getDouble("awake"),
                    it.optString("source"), it.optBoolean("stages", true))
            }
            Dashboard(n, o.optLongOrNull("steps"), o.optString("stepsDay").ifEmpty { null },
                o.optLongOrNull("hr")?.toInt(), o.optLongOrNull("hrTime")?.let(Instant::ofEpochMilli),
                o.optLongOrNull("battery")?.toInt(), o.optLongOrNull("batteryTime")?.let(Instant::ofEpochMilli))
        }.getOrDefault(Dashboard())

        private fun org.json.JSONObject.optLongOrNull(k: String) = if (has(k)) getLong(k) else null

        /** Today's steps from the per-day aggregation. */
        fun today(days: Days): Pair<Long?, String> {
            val d = LocalDate.now(zone)
            return days.map[d]?.steps to d.toString()
        }
    }
}

fun summaryText(header: String, counts: Map<String, Int>, nights: List<SleepNight>): String = buildString {
    appendLine(header)
    appendLine()
    appendLine("Exportierte Datensätze:")
    counts.forEach { (k, v) -> appendLine("  %-28s %d".format(k, v)) }
    if (nights.isNotEmpty()) {
        appendLine()
        appendLine("Letzte Nächte:")
        nights.takeLast(7).forEach { n ->
            val s = iso(n.start)!!
            appendLine(
                "  ${s.substring(0, 16).replace('T', ' ')} → ${iso(n.end)!!.substring(11, 16)}  " +
                    "Schlaf ${hm(n.asleep)} (Tief ${hm(n.deep)}, Leicht ${hm(n.light)}, REM ${hm(n.rem)}, Wach ${hm(n.awake)})"
            )
        }
    }
}

object Report {
    private val colors = linkedMapOf("Tief" to "#3b4cc0", "Leicht" to "#7aa6f0", "REM" to "#b06ad9", "Wach" to "#f2a541")

    private fun e(s: Any?) = (s ?: "").toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun html(source: String, nights: List<SleepNight>, days: Days): String {
        val longest = nights.maxOfOrNull { it.inBed }?.coerceAtLeast(1.0) ?: 1.0
        val sleepRows = nights.takeLast(120).reversed().joinToString("") { n ->
            val parts = listOf(n.deep, n.light, n.rem, n.awake)
            val bars = colors.values.zip(parts).zip(colors.keys) { (c, v), k ->
                "<span style=\"width:${"%.2f".format(java.util.Locale.US, 100 * v / longest)}%;background:$c\" title=\"$k ${hm(v)}\"></span>"
            }.joinToString("")
            "<tr><td>${e(iso(n.start)!!.substring(0, 16).replace('T', ' '))}</td><td>${e(iso(n.end)!!.substring(11, 16))}</td>" +
                "<td>${hm(n.asleep)}</td><td>${hm(n.deep)}</td><td>${hm(n.light)}</td><td>${hm(n.rem)}</td><td>${hm(n.awake)}</td>" +
                "<td class=bar>$bars</td></tr>"
        }
        val dayRows = days.map.entries.toList().takeLast(120).reversed().joinToString("") { (d, v) ->
            "<tr>" + days.row(d, v).joinToString("") { "<td>${e(it)}</td>" } + "</tr>"
        }
        val legend = colors.entries.joinToString(" ") { "<i style=\"background:${it.value}\"></i>${it.key}" }
        return """<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>CMF Watch Daten</title>
<style>
:root{--bg:#fff;--fg:#222;--line:#ddd}
@media (prefers-color-scheme:dark){:root{--bg:#151515;--fg:#eee;--line:#333}}
body{background:var(--bg);color:var(--fg);font:14px system-ui,sans-serif;margin:16px}
table{border-collapse:collapse;margin-bottom:24px}td,th{border-bottom:1px solid var(--line);padding:4px 6px;text-align:left;white-space:nowrap}
td.bar{min-width:160px;width:40%}td.bar span{display:inline-block;height:12px}
.legend i{display:inline-block;width:10px;height:10px;margin:0 4px 0 12px}.wrap{overflow-x:auto}
</style></head><body>
<h1>CMF Watch – Datenexport</h1><p>Quelle: ${e(source)} · erstellt ${e(iso(Instant.now()))}</p>
<h2>Schlaf</h2><p class="legend">$legend</p>
<div class="wrap"><table><tr><th>Eingeschlafen</th><th>Aufgewacht</th><th>Schlaf</th><th>Tief</th><th>Leicht</th><th>REM</th><th>Wach</th><th></th></tr>$sleepRows</table></div>
<h2>Tageswerte</h2>
<div class="wrap"><table><tr>${days.columns.joinToString("") { "<th>$it</th>" }}</tr>$dayRows</table></div>
</body></html>"""
    }
}
