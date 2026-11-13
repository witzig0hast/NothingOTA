package io.github.witzig0hast.cmftrack

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private lateinit var map: MapHost
    private var points: List<TrackPoint> = emptyList()
    private var hr: List<Pair<Long, Int>> = emptyList()
    private var hrExplain: String? = null

    private fun <T : View> v(id: Int): T = findViewById(id)

    private val hcPermission: ActivityResultLauncher<Set<String>> = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { loadWatchHr(manual = true) }
    private val hcWritePermission: ActivityResultLauncher<Set<String>> = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { saveToHealthConnect(manual = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        store = Store(this); prefs = Prefs(this)
        activity = store.get(intent.getLongExtra("id", 0)) ?: return finish()
        points = store.points(activity.id); hr = store.heartRates(activity.id)
        if (activity.efforts.isEmpty() && points.size > 1) { activity = activity.copy(efforts = BestEfforts.compute(points)); store.save(activity) }
        setupMap(savedInstanceState)
        render()

        v<View>(R.id.loadHrButton).setOnClickListener { loadWatchHr(manual = true) }
        v<View>(R.id.shareButton).setOnClickListener { shareImage() }
        v<View>(R.id.tcxButton).setOnClickListener { share("tcx", "application/vnd.garmin.tcx+xml", Exporters.tcx(activity, points, hr)) }
        v<View>(R.id.gpxButton).setOnClickListener { share("gpx", "application/gpx+xml", Exporters.gpx(activity, points, hr)) }
        v<View>(R.id.routeButton).setOnClickListener {
            if (points.size < 2) return@setOnClickListener snack("Keine GPS-Strecke vorhanden")
            RouteStore.save(this, Exporters.gpx(activity, points, emptyList()).byteInputStream()); prefs.useRoute = true
            snack("Als Route für das nächste Training gesetzt")
        }
        v<View>(R.id.uploadButton).setOnClickListener { upload(manual = true) }
        v<View>(R.id.hcButton).setOnClickListener { saveToHealthConnect(manual = true) }
        v<View>(R.id.editButton).setOnClickListener { edit() }
        v<View>(R.id.deleteButton).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Aktivität löschen?").setNegativeButton("Abbrechen", null)
                .setPositiveButton("Löschen") { _, _ -> store.delete(activity.id); finish() }.show()
        }

        lifecycleScope.launch {
            // No heart rate yet: try the watch right away (also for older activities).
            if (hr.isEmpty()) loadWatchHr(manual = false).join()
            if (intent.getBooleanExtra("fresh", false)) {
                if (prefs.saveToHealthConnect) saveToHealthConnect(manual = false)
                if (prefs.autoUpload && prefs.serverUrl.startsWith("http")) upload(manual = false)
            }
        }
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onStart() { super.onStart(); map.onStart() }
    override fun onStop() { map.onStop(); super.onStop() }
    override fun onDestroy() { map.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); map.onSaveInstanceState(outState) }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap(saved: Bundle?) {
        val container = v<android.widget.FrameLayout>(R.id.mapContainer)
        map = MapHost(container, prefs.layer, saved)
        map.follow = false
        map.setTrack(points, paceColors = true)
        map.showKmMarkers(true)
        val pts = points.map { it.lat to it.lon }
        container.post { map.fit(pts, 90) }
        // let the map pan inside the scroll view
        container.setOnTouchListener { view, e ->
            view.parent.requestDisallowInterceptTouchEvent(e.action != MotionEvent.ACTION_UP && e.action != MotionEvent.ACTION_CANCEL); false
        }
        v<View>(R.id.fullButton).setOnClickListener { map.fit(pts, 90) }
        v<View>(R.id.layerButton).setOnClickListener {
            val layers = Layer.entries
            MaterialAlertDialogBuilder(this).setTitle("Kartenansicht")
                .setSingleChoiceItems(layers.map { it.label }.toTypedArray(), layers.indexOf(prefs.layer)) { d, i ->
                    prefs.layer = layers[i]; map.setLayer(layers[i]); d.dismiss()
                }.show()
        }
    }

    private fun render() {
        val a = activity
        val z = ZoneId.systemDefault()
        v<TextView>(R.id.date).text = Instant.ofEpochMilli(a.start).atZone(z).format(DateTimeFormatter.ofPattern("EEEE, dd. MMMM yyyy · HH:mm", Locale.GERMAN)) +
            (if (a.origin == "watch") " · ⌚ von der Uhr" else "")
        v<TextView>(R.id.title).text = a.title
        v<TextView>(R.id.notes).apply { text = a.notes ?: ""; visibility = if (a.notes.isNullOrBlank()) View.GONE else View.VISIBLE }

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
        stat(when { bestKm == null -> "–"; a.sport.showPace -> Fmt.duration(bestKm); else -> Fmt.kmh(1000 / bestKm) }, if (a.sport.showPace) "Bester km" else "Bester km km/h")
        stat("%.0f m".format(a.elevGain), "Aufstieg")
        stat(a.avgHr?.toString() ?: "–", "Ø Puls")
        stat(a.maxHr?.toString() ?: "–", "Max Puls")
        stat(a.kcal?.let { "%.0f".format(it) } ?: "–", "kcal")
        val cad = store.cadence(a.id)
        if (cad.isNotEmpty()) stat("${cad.map { it.second }.average().toInt()}", "Ø Schritte/min")
        if (a.elevLoss > 0) stat("%.0f m".format(a.elevLoss), "Abstieg")
        // stride length = speed / step rate
        val avgCad = cad.map { it.second }.average()
        if (cad.isNotEmpty() && a.sport.showPace && avgCad > 0) stat("%.0f cm".format(a.avgSpeed / (avgCad / 60) * 100), "Schrittlänge")

        renderEfforts()
        renderHeartRate()

        // Speed & elevation over distance (smoothed over ~30 s)
        val speed = mutableListOf<Pair<Double, Double>>()
        var j = 0
        for (i in points.indices) {
            while (points[i].time - points[j].time > 30_000) j++
            val dt = (points[i].time - points[j].time) / 1000.0
            if (dt >= 10) speed += points[i].distance to (points[i].distance - points[j].distance) / dt
        }
        v<TextView>(R.id.speedTitle).text = if (a.sport.showPace) "Tempo (min/km)" else "Geschwindigkeit (km/h)"
        v<ChartView>(R.id.speedChart).set(speed.filter { it.second > 0.5 }, getColor(R.color.stage_deep)) { if (a.sport.showPace) Fmt.pace(it) else Fmt.kmh(it) }
        v<ChartView>(R.id.elevChart).set(points.mapNotNull { p -> p.alt?.let { p.distance to it } }, 0xFF2EBD59.toInt()) { "%.0f m".format(it) }
        val cadPts = cad.map { (t, c) -> distanceAt(t) to c.toDouble() }
        v<View>(R.id.cadTitle).visibility = if (cadPts.size > 1) View.VISIBLE else View.GONE
        v<ChartView>(R.id.cadChart).apply { visibility = if (cadPts.size > 1) View.VISIBLE else View.GONE; set(cadPts, getColor(R.color.stage_rem)) { "%.0f".format(it) } }

        renderSplits(); renderLaps()
    }

    private fun renderEfforts() {
        val box = v<LinearLayout>(R.id.efforts)
        box.removeAllViews()
        v<View>(R.id.effortsCard).visibility = if (activity.efforts.isEmpty() || !activity.sport.showPace) View.GONE else View.VISIBLE
        val records = BestEfforts.records(store.list().filter { it.finished && it.sport == activity.sport })
        activity.efforts.forEach { (label, sec) ->
            val pr = records[label]?.second?.id == activity.id
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
                addView(TextView(context).apply { text = label; setTextAppearance(R.style.Body); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                addView(TextView(context).apply {
                    text = Fmt.duration(sec) + if (pr) "  🏆 Bestzeit" else records[label]?.let { "  (PB ${Fmt.duration(it.first)})" } ?: ""
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    if (pr) setTextColor(getColor(R.color.brand))
                })
            })
        }
    }

    private fun renderHeartRate() {
        val inRange = hr.filter { it.first in activity.start..activity.end }
        v<TextView>(R.id.hrInfo).text = when {
            inRange.isNotEmpty() -> "${inRange.size} Messwerte · Quelle: ${activity.hrSource ?: "–"}" + (hrExplain?.takeIf { "nur alle" in it }?.let { "\n$it" } ?: "")
            hrExplain != null -> hrExplain
            else -> "Noch kein Puls – wird automatisch nachgeladen, sobald die CMF-App die Uhr synchronisiert hat."
        }
        v<ChartView>(R.id.hrChart).apply {
            visibility = if (inRange.size > 1) View.VISIBLE else View.GONE
            set(inRange.map { distanceAt(it.first) to it.second.toDouble() }, getColor(R.color.brand)) { "%.0f".format(it) }
        }
        val box = v<LinearLayout>(R.id.zones)
        box.removeAllViews()
        if (inRange.size < 2) return
        val z = prefs.profile.zones(inRange)
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

    private fun renderSplits() {
        val a = activity
        val splits = v<LinearLayout>(R.id.splits)
        splits.removeAllViews()
        v<View>(R.id.splitsCard).visibility = if (a.splits.isEmpty()) View.GONE else View.VISIBLE
        val best = a.splits.minOfOrNull { it.seconds } ?: 0.0
        val worst = a.splits.maxOfOrNull { it.seconds } ?: 1.0
        for (s in a.splits) {
            val frac = if (worst > best) 0.35 + 0.65 * (worst - s.seconds) / (worst - best) else 1.0
            splits.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
                addView(TextView(context).apply { text = "${s.km}"; width = dp(32); setTextAppearance(R.style.Body) })
                addView(TextView(context).apply {
                    text = if (a.sport.showPace) Fmt.duration(s.seconds) else Fmt.kmh(1000 / s.seconds) + " km/h"
                    width = dp(84); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                })
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(10), frac.toFloat()).apply { topMargin = dp(6) }
                    setBackgroundColor(if (s.seconds == best) getColor(R.color.brand) else getColor(R.color.stage_light))
                })
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, (1.0 - frac).toFloat() + 0.001f) })
                addView(TextView(context).apply {
                    text = listOfNotNull(s.avgHr?.let { "♥ $it" }, "%+.0f m".format(s.elevation)).joinToString("  ")
                    setTextAppearance(R.style.Body); textSize = 12f; setPadding(dp(8), 0, 0, 0)
                })
            })
        }
    }

    private fun renderLaps() {
        val laps = store.laps(activity.id)
        v<View>(R.id.lapsCard).visibility = if (laps.isEmpty()) View.GONE else View.VISIBLE
        val box = v<LinearLayout>(R.id.laps)
        box.removeAllViews()
        var prevD = 0.0; var prevT = 0L
        for (l in laps) {
            val d = l.distance - prevD; val t = (l.movingMs - prevT) / 1000.0
            box.addView(TextView(this).apply {
                text = "Runde ${l.n}:  ${"%.2f".format(d / 1000)} km  ·  ${Fmt.duration(t)}  ·  " + if (activity.sport.showPace && d > 0) "${Fmt.pace(d / t)} /km" else "${Fmt.kmh(d / t)} km/h"
                setTextAppearance(R.style.Body); setPadding(0, dp(3), 0, dp(3))
            })
            prevD = l.distance; prevT = l.movingMs
        }
    }

    private fun distanceAt(t: Long): Double {
        if (points.isEmpty()) return (t - activity.start) / 1000.0
        var lo = 0; var hi = points.size - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (points[m].time < t) lo = m + 1 else hi = m }
        return points[lo].distance
    }

    private fun edit() {
        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
        val name = TextInputEditText(this).apply { setText(activity.title) }
        val notes = TextInputEditText(this).apply { setText(activity.notes ?: ""); minLines = 2 }
        col.addView(TextInputLayout(this).apply { hint = "Titel"; addView(name) })
        col.addView(TextInputLayout(this).apply { hint = "Notiz (wie lief es?)"; addView(notes) })
        val sports = Sport.entries
        var sport = activity.sport
        MaterialAlertDialogBuilder(this).setTitle("Aktivität bearbeiten").setView(col)
            .setNeutralButton("Sportart: ${sport.label}") { _, _ ->
                MaterialAlertDialogBuilder(this).setTitle("Sportart").setSingleChoiceItems(sports.map { it.label }.toTypedArray(), sports.indexOf(sport)) { d, i ->
                    sport = sports[i]; activity = activity.copy(sport = sport, name = activity.name); store.save(activity); render(); d.dismiss()
                }.show()
            }
            .setPositiveButton("Speichern") { _, _ ->
                activity = activity.copy(name = name.text.toString().trim().ifEmpty { null }, notes = notes.text.toString().trim().ifEmpty { null })
                store.save(activity); render()
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun loadWatchHr(manual: Boolean): Job = lifecycleScope.launch {
        if (HealthConnectClient.getSdkStatus(this@DetailActivity) != HealthConnectClient.SDK_AVAILABLE) { if (manual) snack("Health Connect ist nicht verfügbar"); return@launch }
        val client = HealthConnectClient.getOrCreate(this@DetailActivity)
        if (Watch.HR !in client.permissionController.getGrantedPermissions()) {
            hrExplain = "Health Connect ist für CMF Track noch nicht freigegeben."
            renderHeartRate()
            if (manual) this@DetailActivity.hcPermission.launch(Watch.PERMISSIONS)
            return@launch
        }
        busy(true)
        val r = runCatching { withContext(Dispatchers.IO) { Watch.heartRate(client, activity.start, activity.end) } }.getOrNull()
        busy(false)
        if (r == null) return@launch
        hrExplain = r.explain(activity.start, activity.end)
        val inRange = r.samples.filter { it.first in activity.start..activity.end }
        if (inRange.isNotEmpty() && inRange.size >= hr.count { it.first in activity.start..activity.end }) {
            store.replaceHr(activity.id, r.samples)
            hr = r.samples
            activity = Analysis.withHeartRate(activity, points, r.samples, prefs.profile, r.source)
            store.save(activity)
            render()
            if (manual || inRange.isNotEmpty()) snack("${inRange.size} Pulswerte von der Uhr übernommen")
        } else {
            renderHeartRate()
            if (manual) snack(hrExplain ?: "Keine Pulswerte gefunden")
        }
    }

    private fun saveToHealthConnect(manual: Boolean): Job = lifecycleScope.launch {
        if (HealthConnectClient.getSdkStatus(this@DetailActivity) != HealthConnectClient.SDK_AVAILABLE) return@launch
        val client = HealthConnectClient.getOrCreate(this@DetailActivity)
        val missing = HealthWrite.PERMISSIONS - client.permissionController.getGrantedPermissions()
        if (HealthPermissionCompat.SESSION !in client.permissionController.getGrantedPermissions()) {
            if (manual) this@DetailActivity.hcWritePermission.launch(missing)
            return@launch
        }
        val msg = runCatching { withContext(Dispatchers.IO) { HealthWrite.save(client, activity, points) }; "In Health Connect gespeichert ✓" }
            .getOrElse { "Health Connect: ${it.message}" }
        if (manual || !msg.contains("✓")) snack(msg)
    }

    private fun upload(manual: Boolean): Job = lifecycleScope.launch {
        if (!prefs.serverUrl.startsWith("http")) { snack("Bitte im Profil die Server-Adresse eintragen"); return@launch }
        busy(true)
        val msg = runCatching {
            withContext(Dispatchers.IO) {
                val f = Exporters.serverZip(activity, hr, File(cacheDir, "workout_${activity.id}.zip"), points)
                Exporters.upload(prefs.serverUrl, prefs.token, f)
            }
            "An Home Assistant gesendet ✓"
        }.getOrElse { "Senden fehlgeschlagen: ${it.message}" }
        busy(false)
        if (manual || !msg.contains("✓")) snack(msg)
    }

    private fun shareImage() {
        val bmp = ShareCard.render(this, activity, points)
        val f = File(File(cacheDir, "share").apply { mkdirs() }, "CMF_Track_${activity.id}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Aktivität teilen"))
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

private object HealthPermissionCompat {
    val SESSION = androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.ExerciseSessionRecord::class)
}
