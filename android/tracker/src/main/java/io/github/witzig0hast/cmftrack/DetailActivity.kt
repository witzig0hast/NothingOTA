package io.github.witzig0hast.cmftrack

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class DetailActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var prefs: Prefs
    private lateinit var activity: Activity
    private var points: List<TrackPoint> = emptyList()
    private var hr: List<Pair<Long, Int>> = emptyList()

    private fun <T : View> v(id: Int): T = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        store = Store(this); prefs = Prefs(this)
        activity = store.get(intent.getLongExtra("id", 0)) ?: return finish()
        points = store.points(activity.id); hr = store.heartRates(activity.id)
        render()

        v<View>(R.id.loadHrButton).setOnClickListener { loadWatchHr(manual = true) }
        v<View>(R.id.tcxButton).setOnClickListener { share("tcx", "application/vnd.garmin.tcx+xml", Exporters.tcx(activity, points, hr)) }
        v<View>(R.id.gpxButton).setOnClickListener { share("gpx", "application/gpx+xml", Exporters.gpx(activity, points, hr)) }
        v<View>(R.id.uploadButton).setOnClickListener { upload(manual = true) }
        v<View>(R.id.deleteButton).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Aktivität löschen?").setNegativeButton("Abbrechen", null)
                .setPositiveButton("Löschen") { _, _ -> store.delete(activity.id); finish() }.show()
        }

        if (intent.getBooleanExtra("fresh", false)) lifecycleScope.launch {
            // Right after recording: fetch the watch's heart rate if none was recorded live, then upload.
            if (hr.isEmpty()) loadWatchHr(manual = false)
            if (prefs.autoUpload && prefs.serverUrl.startsWith("http")) upload(manual = false)
        }
    }

    private fun render() {
        val a = activity
        val z = ZoneId.systemDefault()
        v<TextView>(R.id.date).text = Instant.ofEpochMilli(a.start).atZone(z).format(DateTimeFormatter.ofPattern("EEEE, dd. MMMM yyyy · HH:mm", Locale.GERMAN))
        v<TextView>(R.id.title).text = a.sport.label
        v<RouteView>(R.id.route).set(points)

        val grid = v<GridLayout>(R.id.stats)
        grid.removeAllViews()
        fun stat(value: String, label: String) = grid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
            layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }
            addView(TextView(context).apply { text = label.uppercase(); setTextAppearance(R.style.Label) })
            addView(TextView(context).apply { text = value; setTextAppearance(R.style.TileNumber) })
        })
        stat(Fmt.km(a.distance), "km")
        stat(Fmt.duration(a.movingMs / 1000.0), "Bewegt")
        stat(Fmt.duration(a.elapsedMs / 1000.0), "Gesamt")
        stat(if (a.sport.showPace) Fmt.pace(a.avgSpeed) else Fmt.kmh(a.avgSpeed), if (a.sport.showPace) "Ø /km" else "Ø km/h")
        val bestKm = a.splits.minOfOrNull { it.seconds }
        stat(when {
            bestKm == null -> "–"
            a.sport.showPace -> Fmt.duration(bestKm)
            else -> Fmt.kmh(1000 / bestKm)
        }, if (a.sport.showPace) "Bester km" else "Bester km km/h")
        stat("%.0f m".format(a.elevGain), "Aufstieg")
        stat(a.avgHr?.toString() ?: "–", "Ø Puls")
        stat(a.maxHr?.toString() ?: "–", "Max Puls")
        stat(a.kcal?.let { "%.0f".format(it) } ?: "–", "kcal")

        // Heart rate
        v<TextView>(R.id.hrInfo).text = if (hr.isEmpty()) "Keine Pulsdaten – tippe auf „Puls von der Uhr laden“. Die CMF-App muss vorher mit der Uhr synchronisiert haben."
        else "${hr.size} Messwerte · Quelle: ${a.hrSource ?: "–"}"
        val brand = getColor(R.color.brand)
        v<ChartView>(R.id.hrChart).apply {
            visibility = if (hr.size > 1) View.VISIBLE else View.GONE
            set(hr.map { distanceAt(it.first) to it.second.toDouble() }, brand) { "%.0f".format(it) }
        }
        renderZones()

        // Speed & elevation over distance (smoothed over ~30 s)
        val speed = mutableListOf<Pair<Double, Double>>()
        var j = 0
        for (i in points.indices) {
            while (points[i].time - points[j].time > 30_000) j++
            val dt = (points[i].time - points[j].time) / 1000.0
            if (dt >= 10) speed += points[i].distance to (points[i].distance - points[j].distance) / dt
        }
        v<TextView>(R.id.speedTitle).text = if (a.sport.showPace) "Tempo (min/km)" else "Geschwindigkeit (km/h)"
        v<ChartView>(R.id.speedChart).set(speed.filter { it.second > 0.5 }, getColor(R.color.stage_deep), invert = false) {
            if (a.sport.showPace) Fmt.pace(it) else Fmt.kmh(it)
        }
        v<ChartView>(R.id.elevChart).set(points.mapNotNull { p -> p.alt?.let { p.distance to it } }, 0xFF2EBD59.toInt()) { "%.0f m".format(it) }

        // Splits
        val splits = v<LinearLayout>(R.id.splits)
        splits.removeAllViews()
        v<View>(R.id.splitsCard).visibility = if (a.splits.isEmpty()) View.GONE else View.VISIBLE
        val best = a.splits.minOfOrNull { it.seconds } ?: 0.0
        val worst = a.splits.maxOfOrNull { it.seconds } ?: 1.0
        for (s in a.splits) splits.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
            addView(TextView(context).apply { text = "${s.km}"; width = dp(32); setTextAppearance(R.style.Body) })
            addView(TextView(context).apply {
                text = if (a.sport.showPace) Fmt.duration(s.seconds) else Fmt.kmh(1000 / s.seconds) + " km/h"
                width = dp(84); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            })
            addView(View(context).apply {
                // bar: fastest km = longest bar
                val frac = if (worst > best) 0.35 + 0.65 * (worst - s.seconds) / (worst - best) else 1.0
                layoutParams = LinearLayout.LayoutParams(0, dp(10), frac.toFloat()).apply { topMargin = dp(6) }
                setBackgroundColor(if (s.seconds == best) brand else getColor(R.color.stage_light))
            })
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, (1.0 - (if (worst > best) 0.35 + 0.65 * (worst - s.seconds) / (worst - best) else 1.0)).toFloat() + 0.001f) })
            addView(TextView(context).apply {
                text = listOfNotNull(s.avgHr?.let { "♥ $it" }, "%+.0f m".format(s.elevation)).joinToString("  ")
                setTextAppearance(R.style.Body); textSize = 12f; setPadding(dp(8), 0, 0, 0)
            })
        })
    }

    private fun renderZones() {
        val box = v<LinearLayout>(R.id.zones)
        box.removeAllViews()
        if (hr.size < 2) return
        val profile = prefs.profile
        val z = profile.zones(hr)
        val total = z.sum().takeIf { it > 0 } ?: return
        val names = listOf("Z1 Locker", "Z2 Grundlage", "Z3 Tempo", "Z4 Schwelle", "Z5 Maximal")
        val colors = listOf(0xFF9E9E9E, 0xFF4F8EF7, 0xFF2EBD59, 0xFFF2A541, 0xFFD71921).map { it.toInt() }
        for (i in 4 downTo 0) box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2))
            addView(TextView(context).apply { text = names[i]; width = dp(110); setTextAppearance(R.style.Body); textSize = 12f })
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(10), (z[i] / total).toFloat().coerceAtLeast(0.005f)).apply { topMargin = dp(4) }
                setBackgroundColor(colors[i])
            })
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, (1 - z[i] / total).toFloat() + 0.001f) })
            addView(TextView(context).apply { text = Fmt.duration(z[i]); width = dp(56); setTextAppearance(R.style.Body); textSize = 12f; textAlignment = View.TEXT_ALIGNMENT_VIEW_END })
        })
    }

    private fun distanceAt(t: Long): Double {
        if (points.isEmpty()) return 0.0
        var lo = 0; var hi = points.size - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (points[m].time < t) lo = m + 1 else hi = m }
        return points[lo].distance
    }

    private suspend fun loadWatchHrInternal(): String {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) return "Health Connect ist nicht verfügbar"
        val client = HealthConnectClient.getOrCreate(this)
        if (WatchHeartRate.PERMISSION !in client.permissionController.getGrantedPermissions())
            return "Bitte im Profil „Puls lesen erlauben“ antippen"
        val (samples, src) = withContext(Dispatchers.IO) { WatchHeartRate.load(client, activity.start, activity.end) }
        if (samples.isEmpty()) return "Keine Pulsdaten der Uhr für diesen Zeitraum. Erst in der CMF-App synchronisieren, dann erneut versuchen."
        store.replaceHr(activity.id, samples)
        hr = samples
        activity = Analysis.withHeartRate(activity, points, samples, prefs.profile, src)
        store.save(activity)
        render()
        return "${samples.size} Pulswerte von der Uhr übernommen"
    }

    private fun loadWatchHr(manual: Boolean) = lifecycleScope.launch {
        busy(true)
        val msg = runCatching { loadWatchHrInternal() }.getOrElse { "Fehler: ${it.message}" }
        busy(false)
        if (manual || msg.contains("übernommen")) snack(msg)
    }

    private fun upload(manual: Boolean) = lifecycleScope.launch {
        if (!prefs.serverUrl.startsWith("http")) { snack("Bitte im Profil die Server-Adresse eintragen"); return@launch }
        busy(true)
        val msg = runCatching {
            withContext(Dispatchers.IO) {
                val f = Exporters.serverZip(activity, hr, File(cacheDir, "workout_${activity.id}.zip"))
                Exporters.upload(prefs.serverUrl, prefs.token, f)
            }
            "An Home Assistant gesendet ✓"
        }.getOrElse { "Senden fehlgeschlagen: ${it.message}" }
        busy(false)
        if (manual || !msg.contains("✓")) snack(msg)
    }

    private fun share(ext: String, mime: String, content: String) {
        val name = "CMF_${activity.sport.name.lowercase()}_" +
            Instant.ofEpochMilli(activity.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + ".$ext"
        val f = File(File(cacheDir, "share").apply { mkdirs() }, name).apply { writeText(content) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "$ext teilen"))
    }

    private fun busy(b: Boolean) { v<LinearProgressIndicator>(R.id.progress).visibility = if (b) View.VISIBLE else View.INVISIBLE }
    private fun snack(s: String) = Snackbar.make(v(R.id.root), s, Snackbar.LENGTH_LONG).show()
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
}
