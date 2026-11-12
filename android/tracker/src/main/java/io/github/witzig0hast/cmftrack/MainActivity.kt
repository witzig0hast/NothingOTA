package io.github.witzig0hast.cmftrack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var store: Store
    private var lastSeenId = 0L

    private fun <T : View> v(id: Int): T = findViewById(id)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasLocation()) startRecording()
        else snack("Ohne genauen Standort kann keine Strecke aufgezeichnet werden")
    }
    private val hcPermission = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        snack(if (WatchHeartRate.PERMISSION in granted) "Puls von der Uhr freigegeben ✓" else "Puls nicht freigegeben")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); store = Store(this)
        setContentView(R.layout.activity_main)
        bindRecord(); bindProfile()
        v<BottomNavigationView>(R.id.nav).setOnItemSelectedListener { show(it.itemId); true }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { Recorder.live.collect(::render) } }
    }

    override fun onResume() { super.onResume(); renderHistory() }

    private fun show(tab: Int) {
        v<View>(R.id.pageRecord).visibility = if (tab == R.id.tab_record) View.VISIBLE else View.GONE
        v<View>(R.id.pageHistory).visibility = if (tab == R.id.tab_history) View.VISIBLE else View.GONE
        v<View>(R.id.pageProfile).visibility = if (tab == R.id.tab_profile) View.VISIBLE else View.GONE
        if (tab == R.id.tab_history) renderHistory()
    }

    // ---------------------------------------------------------------- record
    private fun bindRecord() {
        val chips = v<ChipGroup>(R.id.sportChips)
        Sport.entries.forEach { s ->
            chips.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = s.label; isCheckable = true; id = View.generateViewId(); tag = s
                isChecked = s == prefs.sport
                setOnCheckedChangeListener { _, c -> if (c) prefs.sport = s }
            })
        }
        v<MaterialButton>(R.id.mainButton).setOnClickListener {
            val st = Recorder.live.value
            when {
                !st.recording -> requestAndStart()
                st.paused -> RecordingService.send(this, RecordingService.ACTION_RESUME)
                else -> RecordingService.send(this, RecordingService.ACTION_PAUSE)
            }
        }
        v<MaterialButton>(R.id.stopButton).apply {
            setOnClickListener { snack("Zum Beenden lange drücken") }
            setOnLongClickListener { RecordingService.send(this@MainActivity, RecordingService.ACTION_STOP); true }
        }
    }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestAndStart() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 29) need += Manifest.permission.ACTIVITY_RECOGNITION
        if (prefs.liveHr) need += HeartRateBle.permissions()
        val missing = need.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startRecording() else permissions.launch(missing.toTypedArray())
    }

    private fun startRecording() {
        RecordingService.send(this, RecordingService.ACTION_START, prefs.sport)
    }

    private fun render(s: LiveState) {
        val sport = if (s.recording) s.sport else prefs.sport
        v<TextView>(R.id.stateLabel).text = when {
            !s.recording -> "Bereit · ${sport.label}"
            s.paused -> "Pausiert"
            s.autoPaused -> "Auto-Pause"
            else -> "Aufnahme · ${s.sport.label}"
        }
        v<TextView>(R.id.timeValue).text = Fmt.duration(s.movingMs / 1000.0)
        v<TextView>(R.id.distValue).text = Fmt.km(s.distance)
        v<TextView>(R.id.speedLabel).text = if (sport.showPace) "Tempo /km" else "km/h"
        v<TextView>(R.id.avgLabel).text = if (sport.showPace) "Ø Tempo" else "Ø km/h"
        v<TextView>(R.id.speedValue).text = if (sport.showPace) Fmt.pace(s.speed) else Fmt.kmh(s.speed)
        v<TextView>(R.id.avgValue).text = if (sport.showPace) Fmt.pace(s.avgSpeed) else Fmt.kmh(s.avgSpeed)
        v<TextView>(R.id.hrValue).text = s.hr?.toString() ?: "–"
        v<TextView>(R.id.elevValue).text = "%.0f".format(s.elevGain)
        v<TextView>(R.id.cadValue).text = s.cadence?.toString() ?: "–"
        v<TextView>(R.id.splitText).text = s.lastSplit?.let { "km ${it.km}: ${Fmt.duration(it.seconds)}" + (it.avgHr?.let { h -> " · ♥ $h" } ?: "") } ?: ""
        v<TextView>(R.id.hrStatus).text = if (s.recording) s.hrStatus else ""

        val acc = s.gpsAccuracy
        v<TextView>(R.id.gpsText).text = when { !s.recording -> "GPS"; acc == null -> "GPS sucht …"; else -> "GPS ±${acc.toInt()} m" }
        v<View>(R.id.gpsDot).backgroundTintList = ColorStateList.valueOf(when {
            !s.recording -> getColor(R.color.muted); acc == null -> getColor(R.color.stage_awake)
            acc <= 10 -> 0xFF2EBD59.toInt(); acc <= 25 -> getColor(R.color.stage_awake); else -> getColor(R.color.brand)
        })

        v<View>(R.id.liveRouteCard).visibility = if (s.recording && s.points.size > 1) View.VISIBLE else View.GONE
        if (s.recording) v<RouteView>(R.id.liveRoute).set(s.points)
        v<View>(R.id.sportChips).visibility = if (s.recording) View.GONE else View.VISIBLE

        v<MaterialButton>(R.id.mainButton).apply {
            text = when { !s.recording -> "Start"; s.paused -> "Weiter"; else -> "Pause" }
            setIconResource(if (s.recording && !s.paused) R.drawable.ic_pause else R.drawable.ic_play)
        }
        v<View>(R.id.stopButton).visibility = if (s.recording) View.VISIBLE else View.GONE

        // Recording just finished -> open the result
        if (!s.recording && s.activityId != 0L && s.activityId != lastSeenId) {
            lastSeenId = s.activityId
            Recorder.state.value = LiveState()
            startActivity(Intent(this, DetailActivity::class.java).putExtra("id", s.activityId).putExtra("fresh", true))
        }
    }

    // ---------------------------------------------------------------- history
    private fun renderHistory() {
        val list = v<LinearLayout>(R.id.historyList)
        list.removeAllViews()
        val all = store.list().filter { it.finished }
        val fmt = DateTimeFormatter.ofPattern("EE dd.MM.yyyy · HH:mm", Locale.GERMAN)
        val week = all.filter { it.start > System.currentTimeMillis() - 7 * 86_400_000L }
        v<TextView>(R.id.totals).text = if (all.isEmpty()) "Noch keine Aktivitäten – starte deine erste Aufnahme."
        else "Letzte 7 Tage: ${week.size} Aktivitäten · ${Fmt.km(week.sumOf { it.distance })} km · ${Fmt.duration(week.sumOf { it.movingMs } / 1000.0)}"
        for (a in all) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                setOnClickListener { startActivity(Intent(this@MainActivity, DetailActivity::class.java).putExtra("id", a.id)) }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = Instant.ofEpochMilli(a.start).atZone(ZoneId.systemDefault()).format(fmt)
                setTextAppearance(R.style.Label)
            })
            col.addView(TextView(this).apply { text = a.sport.label; setTextAppearance(R.style.SectionTitle) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            fun stat(value: String, label: String) = row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(TextView(context).apply { text = value; setTextAppearance(R.style.TileNumber) })
                addView(TextView(context).apply { text = label; setTextAppearance(R.style.Body); textSize = 12f })
            })
            stat(Fmt.km(a.distance), "km")
            stat(Fmt.duration(a.movingMs / 1000.0), "Zeit")
            stat(if (a.sport.showPace) Fmt.pace(a.avgSpeed) else Fmt.kmh(a.avgSpeed), if (a.sport.showPace) "/km" else "km/h")
            stat(a.avgHr?.toString() ?: "–", "Ø Puls")
            col.addView(row)
            card.addView(col)
            list.addView(card)
        }
    }

    // ---------------------------------------------------------------- profile
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
        v<TextInputEditText>(R.id.maxHrInput).hint = "${prefs.profile.hrMax}"
        v<MaterialSwitch>(R.id.femaleSwitch).apply { isChecked = !prefs.male; setOnCheckedChangeListener { _, c -> prefs.male = !c } }
        v<MaterialSwitch>(R.id.liveHrSwitch).apply { isChecked = prefs.liveHr; setOnCheckedChangeListener { _, c -> prefs.liveHr = c } }
        v<MaterialSwitch>(R.id.autoUploadSwitch).apply { isChecked = prefs.autoUpload; setOnCheckedChangeListener { _, c -> prefs.autoUpload = c } }
        v<View>(R.id.hcButton).setOnClickListener {
            if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) hcPermission.launch(setOf(WatchHeartRate.PERMISSION))
            else snack("Health Connect ist nicht verfügbar")
        }
    }

    override fun onPause() {
        currentFocus?.clearFocus() // saves the focused profile field
        super.onPause()
    }

    private fun snack(s: String) = Snackbar.make(v(R.id.root), s, Snackbar.LENGTH_LONG).setAnchorView(v<View>(R.id.nav)).show()
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
}
