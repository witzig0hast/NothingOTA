package io.github.witzig0hast.cmftrack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var prefs: Prefs
    private lateinit var store: Store
    private lateinit var map: MapHost
    private var locating = false
    private var lastSeenId = 0L
    private var lastPointCount = -1
    private val metricViews = mutableMapOf<String, Pair<TextView, TextView>>()
    private var pendingImports = ArrayDeque<Watch.WatchWorkout>()

    private fun <T : View> v(id: Int): T = findViewById(id)

    // ---------------------------------------------------------------- permissions & pickers
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) { startLocating(); afterPermissions?.invoke() } else snack("Ohne genauen Standort kann keine Strecke aufgezeichnet werden")
        afterPermissions = null
    }
    private var afterPermissions: (() -> Unit)? = null
    private val hcPermission = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        renderProfileStatus()
        snack(if (Watch.HR in granted) "Puls von der Uhr freigegeben ✓" else "Puls von der Uhr nicht freigegeben")
    }
    private val pickRoute = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)!!.use { PlannedRoute.parseGpx(it, "Route") } // validate
            contentResolver.openInputStream(uri)!!.use { RouteStore.save(this, it) }
            prefs.useRoute = true
        }.onFailure { snack("Keine gültige GPX-Route: ${it.message}") }
        renderRoute()
    }
    private val planRoute = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        renderRoute(); renderReadyInfo()
        v<ChipGroup>(R.id.sportChips).let { g -> for (i in 0 until g.childCount) (g.getChildAt(i) as Chip).isChecked = g.getChildAt(i).tag == prefs.sport }
    }
    private val routeConsent = registerForActivityResult(ExerciseRouteRequestContract()) { route -> importNext(route) }

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); store = Store(this)
        setContentView(R.layout.activity_main)
        setupMap(savedInstanceState); bindRecord(); bindProfile()
        handleConnectLink(intent)
        v<BottomNavigationView>(R.id.nav).setOnItemSelectedListener { show(it.itemId); true }
        v<View>(R.id.importButton).setOnClickListener { importFromWatch() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { Recorder.live.collect(::render) } }
        if (!prefs.onboarded) onboarding()
        HeartRateBackfill.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        map.onResume(); startLocating()
        renderHistory(); renderStats(); renderProfileStatus()
        lifecycleScope.launch { if (withContext(Dispatchers.IO) { runCatching { HeartRateBackfill.run(this@MainActivity) }.getOrDefault(0) } > 0) renderHistory() }
    }

    override fun onPause() {
        currentFocus?.clearFocus() // saves the focused profile field
        map.onPause(); stopLocating()
        super.onPause()
    }

    override fun onStart() { super.onStart(); map.onStart() }
    override fun onStop() { map.onStop(); super.onStop() }
    override fun onDestroy() { map.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); map.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); map.onSaveInstanceState(outState) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleConnectLink(intent) }

    /** cmftrack://connect?server=…&token=… from the website: fill in the server connection. */
    private fun handleConnectLink(i: Intent?) {
        val uri = i?.data ?: return
        if (uri.scheme != "cmftrack" || uri.host != "connect") return
        val server = uri.getQueryParameter("server") ?: return
        val token = uri.getQueryParameter("token") ?: return
        prefs.serverUrl = server; prefs.token = token; prefs.autoUpload = true
        v<TextInputEditText>(R.id.serverInput).setText(server); v<TextInputEditText>(R.id.tokenInput).setText(token)
        v<MaterialSwitch>(R.id.autoUploadSwitch).isChecked = true
        MaterialAlertDialogBuilder(this).setTitle("Mit Server verbunden ✓")
            .setMessage("Deine Trainings werden ab jetzt an $server gesendet und erscheinen dort mit Karte und Puls.")
            .setPositiveButton("OK", null).show()
        i.data = null
    }

    // ---------------------------------------------------------------- own position before/without recording
    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocating() {
        if (locating || !hasLocation() || Recorder.live.value.recording) return
        val lm = getSystemService(LocationManager::class.java)
        (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let(::onLocationChanged)
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, android.os.Looper.getMainLooper()) }
        runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this, android.os.Looper.getMainLooper()) }
        locating = true
    }

    private fun stopLocating() {
        if (!locating) return
        getSystemService(LocationManager::class.java).removeUpdates(this); locating = false
    }

    override fun onLocationChanged(l: Location) {
        if (!Recorder.live.value.recording) map.setMyLocation(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 30f)
    }

    private fun show(tab: Int) {
        mapOf(R.id.tab_record to R.id.pageRecord, R.id.tab_history to R.id.pageHistory, R.id.tab_stats to R.id.pageStats, R.id.tab_profile to R.id.pageProfile)
            .forEach { (t, page) -> v<View>(page).visibility = if (t == tab) View.VISIBLE else View.GONE }
        when (tab) { R.id.tab_history -> renderHistory(); R.id.tab_stats -> renderStats(); R.id.tab_profile -> renderProfileStatus() }
    }

    private fun onboarding() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Willkommen bei CMF Track")
            .setMessage("Die Strecke kommt per GPS vom Handy, der Puls von deiner CMF Watch.\n\n" +
                "Dafür braucht die App zwei Freigaben:\n• Standort (für GPS)\n• Health Connect → Herzfrequenz (dort speichert die CMF-App den Puls der Uhr)\n\n" +
                "Tipp: Starte das Training zusätzlich auf der Uhr – dann misst sie den Puls jede Sekunde statt nur alle paar Minuten.")
            .setPositiveButton("Freigeben") { _, _ -> prefs.onboarded = true; requestAll(null) }
            .setNegativeButton("Später") { _, _ -> prefs.onboarded = true }
            .show()
    }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Location, notifications, step sensor (+ Bluetooth) – then Health Connect – then [then]. */
    private fun requestAll(then: (() -> Unit)?) {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 29) need += Manifest.permission.ACTIVITY_RECOGNITION
        if (prefs.liveHr) need += HeartRateBle.permissions()
        val missing = need.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val continueWithHc = {
            lifecycleScope.launch {
                val hcMissing = hcMissing()
                if (hcMissing.isNotEmpty() && !prefs.hcAsked) { prefs.hcAsked = true; this@MainActivity.hcPermission.launch(hcMissing) }
                then?.invoke()
            }
        }
        if (missing.isEmpty()) continueWithHc() else { afterPermissions = { continueWithHc() }; permissions.launch(missing.toTypedArray()) }
    }

    private suspend fun hcMissing(): Set<String> {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) return emptySet()
        val wanted = Watch.PERMISSIONS + if (prefs.saveToHealthConnect) HealthWrite.PERMISSIONS else emptySet()
        return wanted - HealthConnectClient.getOrCreate(this).permissionController.getGrantedPermissions()
    }

    // ---------------------------------------------------------------- map
    private fun setupMap(saved: Bundle?) {
        map = MapHost(v(R.id.mapContainer), prefs.layer, saved)
        renderRoute()
        v<View>(R.id.centerButton).setOnClickListener { if (hasLocation()) map.recenter() else requestAll(null) }
        v<View>(R.id.layerButton).setOnClickListener {
            val layers = Layer.entries
            MaterialAlertDialogBuilder(this).setTitle("Kartenansicht")
                .setSingleChoiceItems(layers.map { it.label }.toTypedArray(), layers.indexOf(prefs.layer)) { d, i ->
                    prefs.layer = layers[i]; map.setLayer(layers[i]); d.dismiss()
                }.show()
        }
        renderVoiceButton()
        v<View>(R.id.voiceButton).setOnClickListener {
            prefs.voice = !prefs.voice; renderVoiceButton(); v<MaterialSwitch>(R.id.voiceSwitch).isChecked = prefs.voice
            snack(if (prefs.voice) "Sprachansage an (ab dem nächsten Training)" else "Sprachansage aus")
        }
    }

    private fun renderVoiceButton() = v<MaterialButton>(R.id.voiceButton).setIconResource(if (prefs.voice) R.drawable.ic_voice else R.drawable.ic_voice_off)

    private fun renderRoute() {
        val r = if (prefs.useRoute) RouteStore.load(this) else null
        val chip = v<Chip>(R.id.routeChip)
        map.setPlanned(r?.points ?: emptyList())
        v<Chip>(R.id.planChip).setOnClickListener { planRoute.launch(Intent(this, PlanActivity::class.java)) }
        if (r == null) {
            chip.text = "GPX importieren"; chip.isCloseIconVisible = false
            chip.setOnClickListener { pickRoute.launch(arrayOf("*/*")) }
        } else {
            chip.text = "${r.name} · ${Fmt.km(r.length)} km"; chip.isCloseIconVisible = true
            chip.setOnCloseIconClickListener { prefs.useRoute = false; RouteStore.clear(this); renderRoute() }
            chip.setOnClickListener { map.fit(r.points) }
            map.fit(r.points)
        }
    }

    // ---------------------------------------------------------------- record
    private fun bindRecord() {
        val chips = v<ChipGroup>(R.id.sportChips)
        Sport.entries.forEach { s ->
            chips.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = s.label; isCheckable = true; id = View.generateViewId(); tag = s
                isChecked = s == prefs.sport
                setOnCheckedChangeListener { _, c -> if (c) { prefs.sport = s; renderReadyInfo() } }
            })
        }
        renderReadyInfo()
        val grid = v<GridLayout>(R.id.metrics)
        for (key in listOf("Distanz", "Tempo", "Ø Tempo", "Puls", "Höhenmeter", "kcal")) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(6))
                layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }
            }
            val label = TextView(this).apply { text = key.uppercase(); setTextAppearance(R.style.Label) }
            val value = TextView(this).apply { text = "–"; setTextAppearance(R.style.TileNumber) }
            box.addView(label); box.addView(value); grid.addView(box)
            metricViews[key] = label to value
        }
        v<MaterialButton>(R.id.mainButton).setOnClickListener {
            val st = Recorder.live.value
            if (!st.recording) requestAll { startWithCountdown() }
            else RecordingService.send(this, if (st.paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE)
        }
        v<View>(R.id.lapButton).setOnClickListener { RecordingService.send(this, RecordingService.ACTION_LAP); snack("Runde gesetzt") }
        v<View>(R.id.stopButton).setOnClickListener { confirmStop() }
        v<View>(R.id.stopButton).setOnLongClickListener { RecordingService.send(this, RecordingService.ACTION_STOP); true }
    }

    private var lastRoute: List<Pair<Double, Double>>? = null

    /** Turn-by-turn banner on top of the map while navigating. */
    private fun renderNav(s: LiveState) {
        val card = v<View>(R.id.navCard)
        val active = s.recording && (s.navText != null || (s.offRoute ?: 0.0) > 50 || s.rerouting)
        card.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) return
        when {
            s.rerouting -> { v<TextView>(R.id.navArrow).text = "↻"; v<TextView>(R.id.navDist).text = "Neue Route …"; v<TextView>(R.id.navText).text = "Berechne den Weg von hier" }
            (s.offRoute ?: 0.0) > 50 -> { v<TextView>(R.id.navArrow).text = "⚠"; v<TextView>(R.id.navDist).text = "${s.offRoute!!.toInt()} m"; v<TextView>(R.id.navText).text = "neben der Route – zurück zur gestrichelten Linie" }
            else -> {
                v<TextView>(R.id.navArrow).text = s.navArrow ?: "↑"
                v<TextView>(R.id.navDist).text = s.navDistance?.let { if (it >= 1000) "${Fmt.km(it)} km" else "${(Math.round(it / 10) * 10).toInt()} m" } ?: ""
                v<TextView>(R.id.navText).text = s.navText
            }
        }
        v<TextView>(R.id.navRemain).text = s.routeRemaining?.let { "Noch ${Fmt.km(it)} km" } ?: ""
    }

    private fun renderReadyInfo() {
        val last = store.list().firstOrNull { it.finished && it.sport == prefs.sport }
        v<TextView>(R.id.readyInfo).text = last?.let { "Zuletzt: ${it.title} · ${Fmt.km(it.distance)} km · ${Fmt.ago(it.start)}" } ?: "Bereit für deine erste Aufnahme"
    }

    private fun confirmStop() {
        MaterialAlertDialogBuilder(this).setTitle("Training beenden?")
            .setPositiveButton("Beenden & speichern") { _, _ -> RecordingService.send(this, RecordingService.ACTION_STOP) }
            .setNeutralButton("Weiter trainieren", null)
            .show()
    }

    private fun startWithCountdown() {
        if (!hasLocation()) return
        if (!prefs.countdown) { RecordingService.send(this, RecordingService.ACTION_START, prefs.sport); return }
        val cd = v<TextView>(R.id.countdown)
        lifecycleScope.launch {
            cd.visibility = View.VISIBLE
            for (n in 3 downTo 1) {
                cd.text = "$n"; cd.scaleX = 1.3f; cd.scaleY = 1.3f
                cd.animate().scaleX(1f).scaleY(1f).setDuration(400).start()
                delay(1000)
            }
            cd.visibility = View.GONE
            RecordingService.send(this@MainActivity, RecordingService.ACTION_START, prefs.sport)
        }
    }

    private fun metric(key: String, value: String, label: String? = null) {
        metricViews[key]?.let { (l, v) -> v.text = value; if (label != null) l.text = label.uppercase() }
    }

    private fun render(s: LiveState) {
        val sport = if (s.recording) s.sport else prefs.sport
        v<View>(R.id.readyBox).visibility = if (s.recording) View.GONE else View.VISIBLE
        v<View>(R.id.liveBox).visibility = if (s.recording) View.VISIBLE else View.GONE
        v<TextView>(R.id.stateLabel).text = when { s.paused -> "PAUSIERT"; s.autoPaused -> "AUTO-PAUSE"; else -> sport.label.uppercase() }
        v<TextView>(R.id.timeValue).text = Fmt.duration(s.movingMs / 1000.0)
        metric("Distanz", Fmt.km(s.distance), "km")
        metric("Tempo", if (sport.showPace) Fmt.pace(s.speed) else Fmt.kmh(s.speed), if (sport.showPace) "Tempo /km" else "km/h")
        metric("Ø Tempo", if (sport.showPace) Fmt.pace(s.avgSpeed) else Fmt.kmh(s.avgSpeed), if (sport.showPace) "Ø /km" else "Ø km/h")
        metric("Puls", s.hr?.toString() ?: "–", if (s.hrFromWatch && s.hrTime != null) "Puls · Uhr" else "Puls")
        metric("Höhenmeter", "%.0f".format(s.elevGain), "Höhe ↑ m")
        metric("kcal", if (s.cadence != null && sport.showPace) "${s.cadence}" else "%.0f".format(s.calories), if (s.cadence != null && sport.showPace) "Schritte/min" else "kcal")
        metricViews["Puls"]?.second?.setTextColor(getColor(R.color.brand))
        v<TextView>(R.id.hrStatus).text = buildString {
            if (s.hrFromWatch && s.hrTime != null) append("Puls der Uhr ${Fmt.ago(s.hrTime)} · ")
            append(s.hrStatus)
            s.lastSplit?.let { append("\nkm ${it.km}: ${Fmt.duration(it.seconds)}" + (it.avgHr?.let { h -> " · ♥ $h" } ?: "")) }
            if (s.laps.isNotEmpty()) append(" · Runde ${s.laps.size}")
        }
        v<TextView>(R.id.routeInfo).apply {
            visibility = if (s.recording && s.routeRemaining != null) View.VISIBLE else View.GONE
            text = s.routeRemaining?.let { r ->
                "Route: noch ${Fmt.km(r)} km" + (s.offRoute?.takeIf { it > 60 }?.let { " · ⚠ ${it.toInt()} m neben der Route" } ?: "")
            } ?: ""
            setTextColor(if ((s.offRoute ?: 0.0) > 60) getColor(R.color.brand) else getColor(R.color.on_surface))
        }

        val acc = s.gpsAccuracy
        v<TextView>(R.id.gpsText).text = when { !s.recording -> if (hasLocation()) "GPS bereit" else "Standort aus"; acc == null -> "GPS sucht …"; else -> "GPS ±${acc.toInt()} m" }
        v<View>(R.id.gpsDot).backgroundTintList = ColorStateList.valueOf(when {
            !s.recording -> if (hasLocation()) 0xFF2EBD59.toInt() else getColor(R.color.muted); acc == null -> getColor(R.color.stage_awake)
            acc <= 10 -> 0xFF2EBD59.toInt(); acc <= 25 -> getColor(R.color.stage_awake); else -> getColor(R.color.brand)
        })

        if (s.points.size != lastPointCount) {
            lastPointCount = s.points.size
            map.setTrack(s.points)
            if (s.recording) { stopLocating(); s.points.lastOrNull()?.let { map.setMyLocation(it.lat, it.lon, s.gpsAccuracy ?: 10f) } }
        }
        if (s.recording && s.routePoints.isNotEmpty() && s.routePoints !== lastRoute) { lastRoute = s.routePoints; map.setPlanned(s.routePoints) }
        renderNav(s)
        v<MaterialButton>(R.id.mainButton).apply {
            text = when { !s.recording -> "Start"; s.paused -> "Weiter"; else -> "Pause" }
            setIconResource(if (s.recording && !s.paused) R.drawable.ic_pause else R.drawable.ic_play)
        }
        v<View>(R.id.stopButton).visibility = if (s.recording) View.VISIBLE else View.GONE
        v<View>(R.id.lapButton).visibility = if (s.recording) View.VISIBLE else View.GONE

        if (!s.recording && s.activityId != 0L && s.activityId != lastSeenId) {
            lastSeenId = s.activityId
            Recorder.state.value = LiveState()
            map.setTrack(emptyList()); startLocating()
            startActivity(Intent(this, DetailActivity::class.java).putExtra("id", s.activityId).putExtra("fresh", true))
        }
    }

    // ---------------------------------------------------------------- activities
    private fun renderHistory() {
        val list = v<LinearLayout>(R.id.historyList)
        list.removeAllViews()
        val all = store.list().filter { it.finished }
        val fmt = DateTimeFormatter.ofPattern("EE, dd. MMM · HH:mm", Locale.GERMAN)
        v<TextView>(R.id.totals).text = if (all.isEmpty()) "Noch keine Aktivitäten – starte deine erste Aufnahme." else
            "${all.size} Aktivitäten · ${Fmt.km(all.sumOf { it.distance })} km gesamt"
        val records = BestEfforts.records(all.filter { it.sport == Sport.RUN })
        for (a in all) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                setOnClickListener { startActivity(Intent(this@MainActivity, DetailActivity::class.java).putExtra("id", a.id)) }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(TextView(context).apply {
                    text = Instant.ofEpochMilli(a.start).atZone(ZoneId.systemDefault()).format(fmt) + (if (a.origin == "watch") " · ⌚ Uhr" else "")
                    setTextAppearance(R.style.Label)
                })
                addView(TextView(context).apply { text = a.title; setTextAppearance(R.style.SectionTitle) })
            })
            val prs = records.count { it.value.second.id == a.id }
            if (prs > 0) head.addView(TextView(this).apply {
                text = "🏆 $prs PB"; setTextAppearance(R.style.Label); setTextColor(getColor(R.color.brand))
            })
            col.addView(head)
            val pts = store.points(a.id)
            if (pts.size > 1) col.addView(RouteView(this).apply { set(pts); layoutParams = LinearLayout.LayoutParams(-1, dp(130)).apply { topMargin = dp(8) } })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            fun stat(value: String, label: String) = row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(TextView(context).apply { text = label.uppercase(); setTextAppearance(R.style.Label) })
                addView(TextView(context).apply { text = value; setTextAppearance(R.style.TileNumber) })
            })
            stat(Fmt.km(a.distance), "km")
            stat(Fmt.duration(a.movingMs / 1000.0), "Zeit")
            stat(if (a.sport.showPace) Fmt.pace(a.avgSpeed) else Fmt.kmh(a.avgSpeed), if (a.sport.showPace) "/km" else "km/h")
            stat(a.avgHr?.toString() ?: "–", "♥")
            col.addView(row)
            card.addView(col)
            list.addView(card)
        }
    }

    private fun importFromWatch() {
        lifecycleScope.launch {
            if (HealthConnectClient.getSdkStatus(this@MainActivity) != HealthConnectClient.SDK_AVAILABLE) return@launch snack("Health Connect ist nicht verfügbar")
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val missing = Watch.PERMISSIONS - client.permissionController.getGrantedPermissions()
            if (missing.isNotEmpty()) return@launch this@MainActivity.hcPermission.launch(missing)
            val workouts = withContext(Dispatchers.IO) { runCatching { Watch.workouts(client, 60) }.getOrElse { emptyList() } }
                .filter { store.byWatchId(it.id) == null }
            if (workouts.isEmpty()) return@launch snack("Keine neuen Trainings der Uhr in Health Connect (letzte 60 Tage)")
            val fmt = DateTimeFormatter.ofPattern("dd.MM. HH:mm")
            val labels = workouts.map {
                "${it.sport.label} · ${Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).format(fmt)} · ${Fmt.duration((it.end - it.start) / 1000.0)}" +
                    (it.distance?.let { d -> " · ${Fmt.km(d)} km" } ?: "") + if (it.hasRoute) " · GPS" else ""
            }.toTypedArray()
            val checked = BooleanArray(workouts.size) { true }
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("Trainings der Uhr")
                .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
                .setPositiveButton("Übernehmen") { _, _ ->
                    pendingImports = ArrayDeque(workouts.filterIndexed { i, _ -> checked[i] })
                    importNext(null, first = true)
                }.setNegativeButton("Abbrechen", null).show()
        }
    }

    private var importing: Watch.WatchWorkout? = null

    /** Imports the queue one by one; workouts whose GPS route needs consent open Health Connect's dialog first. */
    private fun importNext(route: ExerciseRoute?, first: Boolean = false) {
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            importing?.let { w -> withContext(Dispatchers.IO) { runCatching { WatchImport.import(this@MainActivity, client, w, route) } }; importing = null }
            if (!first && pendingImports.isEmpty()) { renderHistory(); renderStats(); return@launch snack("Trainings übernommen ✓") }
            val next = pendingImports.removeFirstOrNull() ?: return@launch
            importing = next
            if (next.record.exerciseRouteResult is ExerciseRouteResult.ConsentRequired) this@MainActivity.routeConsent.launch(next.id) else importNext(null)
        }
    }

    // ---------------------------------------------------------------- stats
    private fun renderStats() {
        val all = store.list().filter { it.finished }
        val weeks = Stats.weeks(all)
        val thisWeek = weeks.last()
        val goal = prefs.weeklyGoalKm
        v<TextView>(R.id.weekValue).text = "${Fmt.km(thisWeek.distance)} km"
        v<LinearProgressIndicator>(R.id.goalProgress).apply { max = 100; setProgressCompat((thisWeek.distance / 10.0 / goal).toInt().coerceIn(0, 100), true) }
        v<TextView>(R.id.goalText).text = if (thisWeek.distance / 1000 >= goal) "Wochenziel von $goal km erreicht 🎉" else
            "Noch ${Fmt.km(goal * 1000 - thisWeek.distance)} km bis zum Wochenziel ($goal km) · ${thisWeek.count} Aktivitäten · ${Fmt.duration(thisWeek.movingMs / 1000.0)}"
        v<BarChartView>(R.id.weekChart).set(weeks.map { it.distance / 1000 }, weeks.map { it.start.format(DateTimeFormatter.ofPattern("d.M.")) }, goal.toDouble())

        val grid = v<GridLayout>(R.id.totalsGrid)
        grid.removeAllViews()
        val now = LocalDate.now()
        fun inMonth(a: Activity) = Instant.ofEpochMilli(a.start).atZone(ZoneId.systemDefault()).toLocalDate().let { it.year == now.year && it.month == now.month }
        fun inYear(a: Activity) = Instant.ofEpochMilli(a.start).atZone(ZoneId.systemDefault()).year == now.year
        fun tile(v: String, l: String) = grid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(10))
            layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }
            addView(TextView(context).apply { text = l.uppercase(); setTextAppearance(R.style.Label) })
            addView(TextView(context).apply { text = v; setTextAppearance(R.style.TileNumber) })
        })
        tile(Fmt.km(all.filter(::inMonth).sumOf { it.distance }), "km ${now.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.GERMAN)}")
        tile(Fmt.km(all.filter(::inYear).sumOf { it.distance }), "km ${now.year}")
        tile(Fmt.km(all.sumOf { it.distance }), "km gesamt")
        tile("${all.size}", "Aktivitäten")
        tile("%.0f".format(all.filter(::inYear).sumOf { it.elevGain }), "Höhenmeter ${now.year}")
        tile("${Stats.streak(all)} Wo.", "Serie")

        val box = v<LinearLayout>(R.id.records)
        box.removeAllViews()
        val recs = BestEfforts.records(all.filter { it.sport == Sport.RUN })
        if (recs.isEmpty()) box.addView(TextView(this).apply { text = "Lauf mindestens 400 m, dann erscheinen hier deine Bestzeiten."; setTextAppearance(R.style.Body) })
        recs.forEach { (label, pair) ->
            val (sec, a) = pair
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { startActivity(Intent(this@MainActivity, DetailActivity::class.java).putExtra("id", a.id)) }
                addView(TextView(context).apply { text = label; setTextAppearance(R.style.SectionTitle); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                addView(TextView(context).apply {
                    val km = BestEfforts.TARGETS.first { it.second == label }.first / 1000
                    text = "${Fmt.duration(sec)}  ·  ${Fmt.duration(sec / km)} /km  ·  ${Instant.ofEpochMilli(a.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yy"))}"
                    setTextAppearance(R.style.Body)
                })
            })
        }
    }

    // ---------------------------------------------------------------- profile
    private fun renderProfileStatus() {
        lifecycleScope.launch {
            val status = v<TextView>(R.id.hcStatus)
            if (HealthConnectClient.getSdkStatus(this@MainActivity) != HealthConnectClient.SDK_AVAILABLE) { status.text = "Health Connect ist nicht installiert."; return@launch }
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = client.permissionController.getGrantedPermissions()
            if (Watch.HR !in granted) {
                status.text = "⚠ Noch nicht freigegeben – ohne Freigabe kann CMF Track den Puls der Uhr nicht lesen."
                v<MaterialButton>(R.id.hcButton).text = "Health Connect freigeben"
                return@launch
            }
            val r = withContext(Dispatchers.IO) { runCatching { Watch.heartRate(client, System.currentTimeMillis() - 3_600_000, System.currentTimeMillis()) }.getOrNull() }
            status.text = "✓ Freigegeben. " + when {
                r?.lastWatchSample != null -> "Letzter Puls der Uhr ${Fmt.ago(r.lastWatchSample)} (Quelle: ${r.source ?: "CMF"})."
                else -> "Noch kein Puls der Uhr in Health Connect – in der CMF-App die Health-Connect-Synchronisierung einschalten."
            }
            v<MaterialButton>(R.id.hcButton).text = "Freigaben prüfen"
        }
    }

    private fun bindProfile() {
        fun field(id: Int, value: String, save: (String) -> Unit) = v<TextInputEditText>(id).apply {
            setText(value)
            setOnFocusChangeListener { _, has -> if (!has) save(text.toString()) }
        }
        field(R.id.weightInput, "%.0f".format(prefs.weight)) { it.replace(',', '.').toDoubleOrNull()?.let { w -> prefs.weight = w } }
        field(R.id.ageInput, prefs.age.toString()) { it.toIntOrNull()?.let { a -> prefs.age = a } }
        field(R.id.maxHrInput, prefs.maxHr?.toString() ?: "") { prefs.maxHr = it.toIntOrNull() }
        field(R.id.serverInput, prefs.serverUrl) { prefs.serverUrl = it }
        field(R.id.tokenInput, prefs.token) { prefs.token = it }
        field(R.id.goalInput, prefs.weeklyGoalKm.toString()) { it.toIntOrNull()?.takeIf { g -> g > 0 }?.let { g -> prefs.weeklyGoalKm = g; renderStats() } }
        v<TextInputEditText>(R.id.maxHrInput).hint = "${prefs.profile.hrMax}"
        fun switch(id: Int, value: Boolean, set: (Boolean) -> Unit) = v<MaterialSwitch>(id).apply { isChecked = value; setOnCheckedChangeListener { _, c -> set(c) } }
        switch(R.id.femaleSwitch, !prefs.male) { prefs.male = !it }
        switch(R.id.liveHrSwitch, prefs.liveHr) { prefs.liveHr = it }
        switch(R.id.autoUploadSwitch, prefs.autoUpload) { prefs.autoUpload = it }
        switch(R.id.voiceSwitch, prefs.voice) { prefs.voice = it; renderVoiceButton() }
        switch(R.id.countdownSwitch, prefs.countdown) { prefs.countdown = it }
        switch(R.id.hcWriteSwitch, prefs.saveToHealthConnect) { on ->
            prefs.saveToHealthConnect = on
            if (on) lifecycleScope.launch { hcMissing().takeIf { it.isNotEmpty() }?.let { this@MainActivity.hcPermission.launch(it) } }
        }
        v<View>(R.id.hcButton).setOnClickListener {
            lifecycleScope.launch {
                val missing = hcMissing()
                if (missing.isNotEmpty()) this@MainActivity.hcPermission.launch(missing) else { snack("Alle Freigaben sind erteilt ✓"); renderProfileStatus() }
            }
        }
    }

    private fun snack(s: String) = Snackbar.make(v(R.id.root), s, Snackbar.LENGTH_LONG).setAnchorView(v<View>(R.id.nav)).show()
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
}
