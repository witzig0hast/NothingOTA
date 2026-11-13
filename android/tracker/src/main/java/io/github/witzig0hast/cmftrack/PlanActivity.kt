package io.github.witzig0hast.cmftrack

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Plan a route like in Komoot: search or long-press a destination, round trips, out-and-back. */
class PlanActivity : AppCompatActivity(), LocationListener {
    private lateinit var prefs: Prefs
    private lateinit var map: MapHost
    private var here: Pair<Double, Double>? = null
    private var route: PlannedRoute? = null
    private var target: Pair<Pair<Double, Double>, String>? = null
    private var searchJob: Job? = null
    private var sport = Sport.RUN

    private fun <T : View> v(id: Int): T = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan)
        prefs = Prefs(this)
        sport = prefs.sport
        map = MapHost(v(R.id.mapContainer), prefs.layer, savedInstanceState)
        map.onLongPress = { lat, lon -> setTarget(lat to lon, "Markierter Punkt") }
        v<View>(R.id.centerButton).setOnClickListener { map.recenter() }
        v<View>(R.id.backButton).setOnClickListener { finish() }

        val chips = v<ChipGroup>(R.id.sportChips)
        listOf(Sport.RUN, Sport.WALK, Sport.HIKE, Sport.RIDE).forEach { s ->
            chips.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = s.label; isCheckable = true; id = View.generateViewId(); isChecked = s == sport
                setOnCheckedChangeListener { _, c -> if (c) { sport = s; recompute() } }
            })
        }
        v<ChipGroup>(R.id.kindChips).setOnCheckedStateChangeListener { _, _ ->
            v<View>(R.id.roundBox).visibility = if (v<Chip>(R.id.kindRound).isChecked) View.VISIBLE else View.GONE
            if (!v<Chip>(R.id.kindRound).isChecked) recompute()
        }
        val slider = v<Slider>(R.id.kmSlider)
        slider.addOnChangeListener { _, value, _ -> v<MaterialButton>(R.id.roundButton).text = "${value.toInt()} km planen" }
        v<View>(R.id.roundButton).setOnClickListener { planRound(slider.value.toDouble()) }
        v<View>(R.id.useButton).setOnClickListener { useRoute() }
        bindSearch()
        renderRecent()
        renderSummary()
    }

    // ------------------------------------------------------------------ location
    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume(); map.onResume()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(LocationManager::class.java)
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let(::onLocationChanged)
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper()) }
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this, Looper.getMainLooper()) }
        }
    }

    override fun onPause() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        map.onPause(); super.onPause()
    }
    override fun onStart() { super.onStart(); map.onStart() }
    override fun onStop() { map.onStop(); super.onStop() }
    override fun onDestroy() { map.onDestroy(); super.onDestroy() }

    override fun onLocationChanged(l: Location) {
        val first = here == null
        here = l.latitude to l.longitude
        if (route == null) map.setMyLocation(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 30f)
        else map.follow = false
        if (first) renderSummary()
    }

    // ------------------------------------------------------------------ search
    private fun bindSearch() {
        val input = v<EditText>(R.id.searchInput)
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                v<View>(R.id.clearButton).visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                searchJob?.cancel()
                val q = s.toString().trim()
                if (q.length < 3) { showResults(emptyList()); return }
                searchJob = lifecycleScope.launch { delay(400); search(q) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { lifecycleScope.launch { search(input.text.toString()) }; true } else false }
        v<View>(R.id.clearButton).setOnClickListener { input.setText(""); showResults(emptyList()) }
    }

    private suspend fun search(q: String) {
        val res = withContext(Dispatchers.IO) { runCatching { Routing.search(q, here) } }
        res.onSuccess(::showResults).onFailure { snack("Suche nicht möglich: ${it.message}") }
    }

    private fun showResults(places: List<Routing.Place>) {
        val box = v<LinearLayout>(R.id.results)
        box.removeAllViews()
        v<View>(R.id.hint).visibility = if (places.isEmpty() && route == null) View.VISIBLE else View.GONE
        for (p in places) box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = getDrawable(android.R.drawable.list_selector_background)
            addView(TextView(context).apply { text = p.name; setTextAppearance(R.style.SectionTitle) })
            addView(TextView(context).apply { text = p.detail; setTextAppearance(R.style.Body); textSize = 12f })
            setOnClickListener {
                hideKeyboard(); box.removeAllViews()
                v<EditText>(R.id.searchInput).setText(p.name)
                setTarget(p.lat to p.lon, p.name)
            }
        })
    }

    // ------------------------------------------------------------------ routing
    private fun setTarget(p: Pair<Double, Double>, name: String) {
        target = p to name
        if (v<Chip>(R.id.kindRound).isChecked) v<Chip>(R.id.kindTarget).isChecked = true
        recompute()
    }

    private fun recompute() {
        val t = target ?: return
        val start = here ?: return snack("Warte auf deinen Standort …")
        val back = v<Chip>(R.id.kindBack).isChecked
        compute {
            if (back) Routing.route(listOf(start, t.first, start), sport, "${t.second} hin & zurück", destination = start).copy(via = listOf(t.first))
            else Routing.route(listOf(start, t.first), sport, t.second)
        }
    }

    private fun planRound(km: Double) {
        val start = here ?: return snack("Warte auf deinen Standort …")
        compute { Routing.roundTrip(start, km, sport, Random.nextDouble(0.0, 360.0)) }
    }

    private fun compute(block: () -> PlannedRoute) {
        val progress = v<LinearProgressIndicator>(R.id.progress)
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            progress.visibility = View.GONE
            r.onSuccess { showRoute(it) }.onFailure { snack(it.message ?: "Keine Route gefunden") }
        }
    }

    private fun showRoute(r: PlannedRoute) {
        route = r
        map.setPlanned(r.points)
        map.fit(r.points, 160)
        v<View>(R.id.hint).visibility = View.GONE
        renderSummary()
    }

    private fun renderSummary() {
        val r = route
        val title = v<TextView>(R.id.summaryTitle); val sum = v<TextView>(R.id.summary)
        v<MaterialButton>(R.id.useButton).isEnabled = r != null
        v<View>(R.id.recentBox).visibility = if (r == null && RouteStore.recent(this).isNotEmpty()) View.VISIBLE else View.GONE
        if (r == null) {
            title.text = if (here == null) "Suche deinen Standort …" else "Wohin soll es gehen?"
            sum.text = "Ziel suchen, lange auf die Karte drücken oder eine Rundtour planen."
            return
        }
        title.text = "${Fmt.km(r.length)} km · ${r.name}"
        val pace = when (sport) { Sport.RUN -> 330.0; Sport.RIDE -> 150.0; Sport.HIKE -> 780.0; else -> 690.0 } // s per km
        val turns = r.maneuvers.count { !it.isArrival && it.type !in 1..3 }
        sum.text = "ca. ${Fmt.duration(r.length / 1000 * pace)} ${sport.label.lowercase()} · $turns Abbiegungen · Hinweise kommen aufs Handy und auf die Uhr"
    }

    private fun renderRecent() {
        val chips = v<ChipGroup>(R.id.recentChips)
        RouteStore.recent(this).take(6).forEach { r ->
            chips.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = "${r.name} · ${Fmt.km(r.length)} km"; chipIcon = getDrawable(R.drawable.ic_route)
                setOnClickListener { showRoute(r) }
            })
        }
    }

    private fun useRoute() {
        val r = route ?: return
        RouteStore.set(this, r)
        prefs.useRoute = true; prefs.sport = sport
        setResult(RESULT_OK)
        finish()
    }

    private fun hideKeyboard() = getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v<View>(R.id.searchInput).windowToken, 0)
    private fun snack(s: String) { Snackbar.make(v(R.id.root), s, Snackbar.LENGTH_LONG).show() }
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
}
