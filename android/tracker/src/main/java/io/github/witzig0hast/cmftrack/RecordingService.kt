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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
)

object Recorder {
    val state = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> get() = state
}

class RecordingService : Service(), LocationListener, SensorEventListener {
    companion object {
        const val ACTION_START = "start"; const val ACTION_PAUSE = "pause"; const val ACTION_RESUME = "resume"; const val ACTION_STOP = "stop"
        const val EXTRA_SPORT = "sport"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1

        fun send(ctx: Context, action: String, sport: Sport? = null) {
            val i = Intent(ctx, RecordingService::class.java).setAction(action).apply { sport?.let { putExtra(EXTRA_SPORT, it.name) } }
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var store: Store
    private var stats: TrackStats? = null
    private var activityId = 0L
    private var startTime = 0L
    private var hrBle: HeartRateBle? = null
    private var hrStatus = ""
    private var steps = ArrayDeque<Long>()
    private var lastAccuracy: Float? = null
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { publish(); updateNotification(); main.postDelayed(this, 1000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Aufzeichnung", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (stats == null) start(Sport.valueOf(intent.getStringExtra(EXTRA_SPORT) ?: Sport.RUN.name))
            ACTION_PAUSE -> stats?.paused = true
            ACTION_RESUME -> stats?.paused = false
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
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIF_ID, n)

        getSystemService(LocationManager::class.java).requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 29)
            sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }

        if (Prefs(this).liveHr) {
            hrBle = HeartRateBle(this, { bpm -> main.post { onHeartRate(bpm) } }, { s -> hrStatus = s }).also { it.start() }
        } else hrStatus = "Puls wird nach dem Training von der Uhr geladen"
        main.post(tick)
    }

    private fun onHeartRate(bpm: Int) {
        val now = System.currentTimeMillis()
        stats?.addHeartRate(now, bpm)
        store.addHr(activityId, now, bpm)
    }

    override fun onLocationChanged(l: Location) {
        val s = stats ?: return
        lastAccuracy = if (l.hasAccuracy()) l.accuracy else null
        val before = s.points.size
        val ok = s.addFix(Fix(l.time, l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null,
            if (l.hasAccuracy()) l.accuracy else 10f, if (l.hasSpeed()) l.speed else null))
        if (ok && s.points.size > before) store.addPoint(activityId, s.points.last())
    }

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
        val profile = Prefs(this).profile
        return Activity(activityId, s.sport, startTime, System.currentTimeMillis(), s.distance, s.movingMs,
            s.elevationGain, s.elevationLoss, s.avgHr, s.maxHr, profile.calories(s.sport, s.movingMs / 1000.0, s.avgHr),
            s.maxSpeed, s.splits.toList(), if (s.heartRates.isNotEmpty()) "Bluetooth live" else null, finished)
    }

    private fun publish() {
        val s = stats
        Recorder.state.value = if (s == null) LiveState(hrStatus = hrStatus) else LiveState(
            true, s.paused, s.autoPaused, activityId, s.sport, startTime, s.distance, s.movingMs, s.currentSpeed, s.avgSpeed,
            s.lastHr?.takeIf { s.heartRates.last().first > System.currentTimeMillis() - 10_000 }, s.avgHr, cadence(),
            s.elevationGain, s.altitude, lastAccuracy, s.splits.lastOrNull(), s.points.toList(), hrStatus)
        if (s != null && System.currentTimeMillis() / 1000 % 30 == 0L) store.save(summary(false)) // crash safety
    }

    private fun stop() {
        main.removeCallbacksAndMessages(null)
        hrBle?.stop(); hrBle = null
        getSystemService(LocationManager::class.java).removeUpdates(this)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        stats?.let {
            val a = summary(true)
            if (it.points.size < 2) store.delete(activityId) else store.save(a)
            Recorder.state.value = LiveState(activityId = if (it.points.size < 2) 0 else activityId)
        }
        stats = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val s = stats
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
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
            .build()
    }

    private fun updateNotification() {
        if (stats != null) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        hrBle?.stop()
        super.onDestroy()
    }
}
