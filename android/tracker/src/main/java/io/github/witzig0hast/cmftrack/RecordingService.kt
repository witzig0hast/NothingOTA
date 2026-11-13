package io.github.witzig0hast.cmftrack

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Live state shared between the service and the UI. */
data class LiveState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val autoPaused: Boolean = false,
    val activityId: Long = 0,
    val sport: Sport = Sport.RUN,
    val start: Long = 0,
    val distance: Double = 0.0,
    val movingMs: Long = 0,
    val speed: Double = 0.0,
    val avgSpeed: Double = 0.0,
    val hr: Int? = null,
    val avgHr: Int? = null,
    val cadence: Int? = null,
    val elevGain: Double = 0.0,
    val altitude: Double? = null,
    val gpsAccuracy: Float? = null,
    val lastSplit: Split? = null,
    val points: List<TrackPoint> = emptyList(),
    val hrStatus: String = "",
    val hrFromWatch: Boolean = false,
    val hrTime: Long? = null,
    val laps: List<Lap> = emptyList(),
    val routeName: String? = null,
    val routeRemaining: Double? = null,
    val offRoute: Double? = null,
    val calories: Double = 0.0,
    val routePoints: List<Pair<Double, Double>> = emptyList(),
    val navArrow: String? = null,
    val navText: String? = null,
    val navDistance: Double? = null,
    val rerouting: Boolean = false,
)

object Recorder {
    val state = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> get() = state
}

class RecordingService : Service(), LocationListener, SensorEventListener {
    companion object {
        const val ACTION_START = "start"; const val ACTION_PAUSE = "pause"; const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"; const val ACTION_LAP = "lap"; const val ACTION_TOGGLE = "toggle"
        const val EXTRA_SPORT = "sport"
        private const val CHANNEL = "recording"
        private const val NAV_CHANNEL = "navigation"
        private const val NAV_ID = 2
        private const val NOTIF_ID = 1

        fun send(ctx: Context, action: String, sport: Sport? = null) {
            val i = Intent(ctx, RecordingService::class.java).setAction(action).apply { sport?.let { putExtra(EXTRA_SPORT, it.name) } }
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var store: Store
    private lateinit var prefs: Prefs
    private var stats: TrackStats? = null
    private var activityId = 0L
    private var startTime = 0L
    private var hrBle: HeartRateBle? = null
    private var hrStatus = ""
    private var bleHrAt = 0L
    private var watchHrAt: Long? = null
    private val steps = ArrayDeque<Long>()
    private var lastAccuracy: Float? = null
    private val laps = mutableListOf<Lap>()
    private var voice: Voice? = null
    private var announcedKm = 0
    private var route: PlannedRoute? = null
    private var routeRemaining: Double? = null
    private var offRoute: Double? = null
    private var offRouteSince: Long? = null
    private var offRouteWarned = false
    private var routeAlong: Double? = null
    private var announced = mutableSetOf<String>()   // "<maneuver index>:<far|near>"
    private var nav: Pair<Maneuver, Double>? = null
    private var rerouting = false
    private var lastReroute = 0L
    private var lastFix: Location? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { publish(); updateNotification(); main.postDelayed(this, 1000) }
    }
    private val pollWatch = object : Runnable {
        override fun run() { fetchWatchHr(); main.postDelayed(this, 60_000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this); prefs = Prefs(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Aufzeichnung", NotificationManager.IMPORTANCE_LOW))
        // Turn instructions: a separate, alerting notification - the CMF Watch shows it (enable CMF Track in the CMF app)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NAV_CHANNEL, "Navigation (auf der Uhr)", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); vibrationPattern = longArrayOf(0, 250, 120, 250)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (stats == null) start(Sport.valueOf(intent.getStringExtra(EXTRA_SPORT) ?: Sport.RUN.name))
            ACTION_PAUSE -> pause(true)
            ACTION_RESUME -> pause(false)
            ACTION_TOGGLE -> stats?.let { pause(!it.paused) }
            ACTION_LAP -> lap()
            ACTION_STOP -> stop()
        }
        publish()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start(sport: Sport) {
        startTime = System.currentTimeMillis()
        activityId = store.create(sport, startTime)
        stats = TrackStats(sport)
        laps.clear(); announcedKm = 0
        route = if (prefs.useRoute) RouteStore.load(this) else null
        routeAlong = null; announced.clear(); finishedRoute = false
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIF_ID, n)

        getSystemService(LocationManager::class.java).requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (Build.VERSION.SDK_INT < 29 || checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }

        hrStatus = "Puls: warte auf die Uhr (über Health Connect)"
        if (prefs.liveHr) hrBle = HeartRateBle(this, { bpm -> main.post { onBleHeartRate(bpm) } }, { s -> if (bleHrAt == 0L) hrStatus = s }).also { it.start() }
        if (prefs.voice) voice = Voice(this).also { v -> main.postDelayed({ v.say("${sport.label} gestartet") }, 800) }
        main.post(tick)
        main.postDelayed(pollWatch, 20_000)
    }

    private fun pause(p: Boolean) {
        val s = stats ?: return
        if (s.paused == p) return
        s.paused = p
        voice?.say(if (p) "Pausiert" else "Weiter")
    }

    private fun lap() {
        val s = stats ?: return
        val prev = laps.lastOrNull()
        val l = Lap(laps.size + 1, System.currentTimeMillis(), s.distance, s.movingMs)
        laps += l; store.addLap(activityId, l)
        val d = s.distance - (prev?.distance ?: 0.0); val t = (s.movingMs - (prev?.movingMs ?: 0)) / 1000.0
        voice?.say("Runde ${l.n}. ${"%.2f".format(java.util.Locale.GERMAN, d / 1000)} Kilometer in ${Voice.duration(t)}.")
        vibrate(80)
    }

    private fun onBleHeartRate(bpm: Int) {
        val now = System.currentTimeMillis()
        bleHrAt = now
        stats?.addHeartRate(now, bpm)
        store.addHr(activityId, now, bpm)
    }

    /** The CMF app syncs the watch every now and then: show whatever heart rate already arrived. */
    private fun fetchWatchHr() {
        if (System.currentTimeMillis() - bleHrAt < 30_000) return // a live sensor is connected
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) return
        val s = stats ?: return
        scope.launch {
            runCatching {
                val client = HealthConnectClient.getOrCreate(this@RecordingService)
                if (Watch.HR !in client.permissionController.getGrantedPermissions()) {
                    hrStatus = "Puls: Health Connect nicht freigegeben (Profil → Puls von der Uhr)"; return@launch
                }
                val r = Watch.heartRate(client, startTime, System.currentTimeMillis())
                val known = s.heartRates.map { it.first }.toHashSet()
                val fresh = r.samples.filter { it.first >= startTime && it.first !in known }
                main.post {
                    fresh.forEach { (t, b) -> s.addHeartRate(t, b); store.addHr(activityId, t, b) }
                    watchHrAt = s.heartRates.lastOrNull()?.first ?: watchHrAt
                    hrStatus = if (s.heartRates.isEmpty()) r.explain(startTime, System.currentTimeMillis())
                    else "Puls von der Uhr · ${s.heartRates.size} Werte"
                }
            }
        }
    }

    override fun onLocationChanged(l: Location) {
        val s = stats ?: return
        lastAccuracy = if (l.hasAccuracy()) l.accuracy else null
        val before = s.points.size
        val ok = s.addFix(Fix(l.time, l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null,
            if (l.hasAccuracy()) l.accuracy else 10f, if (l.hasSpeed()) l.speed else null))
        if (ok && s.points.size > before) store.addPoint(activityId, s.points.last(), cadence())
        followRoute(l)
        val km = (s.distance / 1000).toInt()
        if (km > announcedKm && s.splits.size >= km) {
            announcedKm = km
            voice?.say(Voice.split(s.sport, km, s.splits[km - 1], s.movingMs / 1000.0, s.avgSpeed, s.lastHr?.takeIf { s.heartRates.last().first > System.currentTimeMillis() - 120_000 }))
        }
    }

    private fun followRoute(l: Location) {
        val r = route ?: return
        lastFix = l
        val (off, along) = r.locate(l.latitude, l.longitude, routeAlong)
        offRoute = off
        if (off < 60) routeAlong = along
        routeRemaining = (r.length - (routeAlong ?: along)).coerceAtLeast(0.0)
        val now = System.currentTimeMillis()
        val bike = r.mode == "bicycle"
        if (off > 50) {
            if (offRouteSince == null) offRouteSince = now
            val since = now - offRouteSince!!
            if (!offRouteWarned && since > 8000) {
                offRouteWarned = true; vibrate(400)
                voice?.say("Du bist von der Route abgekommen")
                navNotify("⚠", "Von der Route abgekommen", "${off.toInt()} m neben der Route")
            }
            // planned routes (not imported GPX) get recalculated from here to the destination
            if (since > 15_000 && r.destination != null && r.mode != null && !rerouting && now - lastReroute > 30_000) reroute(l, r)
            nav = null
        } else {
            if (offRouteWarned) { voice?.say("Wieder auf der Route"); cancelNav() }
            offRouteSince = null; offRouteWarned = false
            guide(r, routeAlong ?: along, bike)
        }
        if (routeRemaining!! < 30 && (routeAlong ?: along) > r.length * 0.8 && !finishedRoute) {
            finishedRoute = true; voice?.say("Ziel erreicht"); navNotify("🏁", "Ziel erreicht", r.name); vibrate(600)
        }
    }

    /** Announces the next turn twice: early ("in 150 m …") and right at it. */
    private fun guide(r: PlannedRoute, along: Double, bike: Boolean) {
        val next = r.nextManeuver(along)
        nav = next
        val (m, dist) = next ?: return
        if (m.isArrival) return
        val far = if (bike) 250.0 else 150.0
        val near = if (bike) 50.0 else 30.0
        val key = m.index.toString()
        when {
            dist <= near && announced.add("$key:near") -> {
                announced.add("$key:far")
                voice?.say(m.verbal.substringBefore(" Dann").ifEmpty { m.text })
                navNotify(m.arrow, m.text, "Jetzt" + (m.street?.let { " · $it" } ?: ""))
                vibrate(300)
            }
            dist <= far && dist > near * 1.5 && announced.add("$key:far") -> {
                val d = (Math.round(dist / 10) * 10).toInt()
                voice?.say("In $d Metern ${m.text.replaceFirstChar { it.lowercase() }}")
                navNotify(m.arrow, "In $d m: ${m.text}", r.name)
            }
        }
    }

    private fun reroute(l: Location, r: PlannedRoute) {
        rerouting = true; lastReroute = System.currentTimeMillis()
        val dest = r.destination!!
        val sport = stats?.sport ?: Sport.RUN
        scope.launch {
            val res = runCatching {
                // remaining via points (round trips) are kept, already passed ones dropped
                val remainingVia = r.via.filter { v -> r.locate(v.first, v.second).second > (routeAlong ?: 0.0) }
                Routing.route(listOf(l.latitude to l.longitude) + remainingVia + listOf(dest), sport, r.name, dest)
            }
            main.post {
                rerouting = false
                res.onSuccess { nr ->
                    route = nr; routeAlong = 0.0; announced.clear(); offRouteSince = null; offRouteWarned = false
                    voice?.say("Neue Route berechnet")
                    navNotify("↻", "Neue Route", "Noch ${Fmt.km(nr.length)} km")
                }
            }
        }
    }

    private fun navNotify(arrow: String, title: String, text: String) {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, NAV_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$arrow $title")
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(90_000)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NAV_ID, n) }
    }

    private fun cancelNav() = runCatching { getSystemService(NotificationManager::class.java).cancel(NAV_ID) }

    private var finishedRoute = false

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> stats?.addBarometricAltitude(SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0]).toDouble())
            Sensor.TYPE_STEP_DETECTOR -> {
                val now = System.currentTimeMillis()
                steps.addLast(now)
                while (steps.isNotEmpty() && now - steps.first() > 15_000) steps.removeFirst()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun cadence(): Int? {
        if (steps.size < 5) return null
        val span = (steps.last() - steps.first()) / 60000.0
        return if (span > 0 && System.currentTimeMillis() - steps.last() < 3000) ((steps.size - 1) / span).toInt() else null
    }

    private fun summary(finished: Boolean): Activity {
        val s = stats!!
        val profile = prefs.profile
        val bleHr = bleHrAt > 0
        return Activity(activityId, s.sport, startTime, System.currentTimeMillis(), s.distance, s.movingMs,
            s.elevationGain, s.elevationLoss, s.avgHr, s.maxHr, profile.calories(s.sport, s.movingMs / 1000.0, s.avgHr),
            s.maxSpeed, s.splits.toList(), when { bleHr -> "Bluetooth live"; s.heartRates.isNotEmpty() -> "Uhr (Health Connect)"; else -> null }, finished,
            efforts = if (finished) BestEfforts.compute(s.points) else emptyMap())
    }

    private fun publish() {
        val s = stats
        Recorder.state.value = if (s == null) LiveState(hrStatus = hrStatus) else {
            val lastHr = s.heartRates.lastOrNull()
            val fromWatch = lastHr != null && System.currentTimeMillis() - bleHrAt > 30_000
            LiveState(
                true, s.paused, s.autoPaused, activityId, s.sport, startTime, s.distance, s.movingMs, s.currentSpeed, s.avgSpeed,
                lastHr?.second?.takeIf { fromWatch || lastHr.first > System.currentTimeMillis() - 10_000 }, s.avgHr, cadence(),
                s.elevationGain, s.altitude, lastAccuracy, s.splits.lastOrNull(), s.points.toList(),
                if (bleHrAt > 0 && System.currentTimeMillis() - bleHrAt < 30_000) "Puls live per Bluetooth" else hrStatus,
                fromWatch, lastHr?.first, laps.toList(), route?.name, routeRemaining, offRoute,
                prefs.profile.calories(s.sport, s.movingMs / 1000.0, s.avgHr),
                route?.points ?: emptyList(), nav?.first?.arrow, nav?.first?.text, nav?.second, rerouting,
            )
        }
        if (s != null && System.currentTimeMillis() / 1000 % 30 == 0L) store.save(summary(false)) // crash safety
    }

    private fun stop() {
        main.removeCallbacksAndMessages(null)
        hrBle?.stop(); hrBle = null
        cancelNav()
        getSystemService(LocationManager::class.java).removeUpdates(this)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        stats?.let {
            val a = summary(true)
            if (it.points.size < 2) store.delete(activityId) else store.save(a)
            voice?.say("Training beendet. ${Fmt.km(a.distance).replace('.', ',')} Kilometer in ${Voice.duration(a.movingMs / 1000.0)}.")
            Recorder.state.value = LiveState(activityId = if (it.points.size < 2) 0 else activityId)
            if (it.points.size >= 2) HeartRateBackfill.schedule(this)
        }
        stats = null
        main.postDelayed({ voice?.shutdown(); voice = null }, 8000)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun vibrate(ms: Long) = runCatching {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun action(icon: Int, title: String, act: String, code: Int) = Notification.Action.Builder(
        android.graphics.drawable.Icon.createWithResource(this, icon), title,
        PendingIntent.getService(this, code, Intent(this, RecordingService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun notification(): Notification {
        val s = stats
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val text = if (s == null) "" else buildString {
            append("${Fmt.km(s.distance)} km · ${Fmt.duration(s.movingMs / 1000.0)} · ${Fmt.speedOrPace(s.sport, s.avgSpeed)}")
            s.lastHr?.let { append(" · ♥ $it") }
            if (s.paused) append(" · pausiert") else if (s.autoPaused) append(" · Auto-Pause")
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${s?.sport?.label ?: "Aktivität"} läuft")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_pause_n, if (s?.paused == true) "Weiter" else "Pause", ACTION_TOGGLE, 1))
            .addAction(action(R.drawable.ic_flag_n, "Runde", ACTION_LAP, 2))
            .build()
    }

    private fun updateNotification() {
        if (stats != null) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        hrBle?.stop()
        voice?.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
