package io.github.witzig0hast.cmftrack

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Sport settings that steer filtering, auto-pause and calorie estimate. */
enum class Sport(
    val label: String,
    val autoPauseSpeed: Double, // m/s below which you count as standing still
    val maxSpeed: Double,       // m/s above which a GPS fix is treated as a jump
    val met: Double,            // energy when no heart rate is available
    val showPace: Boolean,      // min/km (true) or km/h (false)
) {
    RUN("Laufen", 0.9, 12.0, 9.8, true),
    WALK("Gehen", 0.4, 4.0, 3.5, true),
    HIKE("Wandern", 0.3, 4.0, 6.0, true),
    RIDE("Radfahren", 1.5, 30.0, 7.5, false),
    INLINE("Inlineskaten", 1.2, 15.0, 7.0, false),
    OTHER("Sonstiges", 0.4, 40.0, 5.0, false);
}

/** Raw GPS fix from the phone. time in epoch ms, accuracy in m. */
data class Fix(
    val time: Long, val lat: Double, val lon: Double,
    val alt: Double? = null, val accuracy: Float = 5f, val speed: Float? = null,
)

/** Accepted, smoothed point as stored in the activity. */
data class TrackPoint(
    val time: Long, val lat: Double, val lon: Double, val alt: Double?,
    val distance: Double,   // cumulative m
    val moving: Boolean,
)

data class Split(val km: Int, val seconds: Double, val avgHr: Int?, val elevation: Double)

object Geo {
    private const val R = 6_371_008.8

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
        val dp = p2 - p1; val dl = (lon2 - lon1) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }
}

/**
 * Constant-velocity Kalman filter in a local metric frame (x east, y north).
 * State per axis: position and velocity; measurement noise = reported GPS accuracy;
 * process noise = expected acceleration of the sport.
 */
class KalmanCV(private val accel: Double) {
    private class Axis { var p = 0.0; var v = 0.0; var pp = 0.0; var pv = 0.0; var vv = 0.0 }
    private val ax = Axis(); private val ay = Axis()
    private var t = -1L
    private var lat0 = 0.0; private var lon0 = 0.0; private var kx = 0.0
    private val ky = 6_371_008.8 * PI / 180

    val speed get() = sqrt(ax.v * ax.v + ay.v * ay.v)

    private fun predict(a: Axis, dt: Double) {
        a.p += a.v * dt
        val q = accel * accel
        val pp = a.pp + dt * (2 * a.pv + dt * a.vv) + q * dt * dt * dt * dt / 4
        val pv = a.pv + dt * a.vv + q * dt * dt * dt / 2
        val vv = a.vv + q * dt * dt
        a.pp = pp; a.pv = pv; a.vv = vv
    }

    private fun update(a: Axis, z: Double, r: Double) {
        val s = a.pp + r
        val k0 = a.pp / s; val k1 = a.pv / s
        val y = z - a.p
        a.p += k0 * y; a.v += k1 * y
        val pp = (1 - k0) * a.pp
        val pv = (1 - k0) * a.pv
        val vv = a.vv - k1 * a.pv
        a.pp = pp; a.pv = pv; a.vv = vv
    }

    /** Returns the filtered lat/lon. */
    fun process(f: Fix): Pair<Double, Double> {
        val r = max(f.accuracy.toDouble(), 2.0).let { it * it }
        if (t < 0) {
            lat0 = f.lat; lon0 = f.lon; kx = ky * cos(lat0 * PI / 180)
            for (a in listOf(ax, ay)) { a.p = 0.0; a.v = 0.0; a.pp = r; a.pv = 0.0; a.vv = 4.0 }
            t = f.time
        } else {
            val dt = ((f.time - t) / 1000.0).coerceIn(0.0, 30.0)
            t = f.time
            if (dt > 0) { predict(ax, dt); predict(ay, dt) }
            update(ax, (f.lon - lon0) * kx, r); update(ay, (f.lat - lat0) * ky, r)
        }
        return (lat0 + ay.p / ky) to (lon0 + ax.p / kx)
    }
}

/** Exponential smoothing of a noisy value (GPS altitude). */
class Smoother(private val alpha: Double) {
    private var v: Double? = null
    fun add(x: Double): Double = (v?.let { it + alpha * (x - it) } ?: x).also { v = it }
}

/** Elevation gain/loss with hysteresis so that sensor noise does not add up. */
class Elevation(private val threshold: Double) {
    var gain = 0.0; private set
    var loss = 0.0; private set
    private var ref: Double? = null
    var current: Double? = null; private set

    fun add(alt: Double) {
        current = alt
        val r = ref ?: run { ref = alt; return }
        when {
            alt - r >= threshold -> { gain += alt - r; ref = alt }
            r - alt >= threshold -> { loss += r - alt; ref = alt }
        }
    }
}

/**
 * Incremental statistics of one recording. Feed fixes, heart rate and (optionally)
 * barometric altitude in time order.
 */
class TrackStats(val sport: Sport, val maxAccuracy: Float = 25f, accel: Double = if (sport.maxSpeed > 15) 1.0 else 0.3) {
    val points = mutableListOf<TrackPoint>()
    val splits = mutableListOf<Split>()
    var distance = 0.0; private set
    var movingMs = 0L; private set
    var maxSpeed = 0.0; private set
    var currentSpeed = 0.0; private set
    var autoPaused = true; private set
    var paused = false
    var rejected = 0; private set

    private val kalman = KalmanCV(accel)
    private val gpsAlt = Smoother(0.1)
    private val gpsElevation = Elevation(3.0)
    private var baro: Elevation? = null
    private var baroOffset: Double? = null
    private var last: TrackPoint? = null
    private var lastFix: Fix? = null
    private var stillSince: Long? = null
    private var movingSince: Long? = null
    private var pendingDist = 0.0
    private var pendingMs = 0L
    private var stillDist = 0.0
    private var stillMs = 0L

    private val hr = mutableListOf<Pair<Long, Int>>()
    private var splitStartMoving = 0L
    private var splitStartElevation: Double? = null
    private var splitHrStart = 0

    val elevationGain get() = (baro ?: gpsElevation).gain
    val elevationLoss get() = (baro ?: gpsElevation).loss
    val altitude: Double? get() = baro?.current?.let { it + (baroOffset ?: 0.0) } ?: gpsElevation.current
    val heartRates: List<Pair<Long, Int>> get() = hr
    val lastHr: Int? get() = hr.lastOrNull()?.second
    val avgHr: Int? get() = if (hr.isEmpty()) null else hr.map { it.second }.average().toInt()
    val maxHr: Int? get() = hr.maxOfOrNull { it.second }

    /** Average speed over moving time in m/s. */
    val avgSpeed get() = if (movingMs > 0) distance / (movingMs / 1000.0) else 0.0

    /** Returns true if the fix was accepted. */
    fun addFix(f: Fix): Boolean {
        if (f.accuracy > maxAccuracy) { rejected++; return false }
        val prev = lastFix
        if (prev != null) {
            val dt = (f.time - prev.time) / 1000.0
            if (dt <= 0) return false
            // reject jumps that would need an impossible speed
            val raw = Geo.distance(prev.lat, prev.lon, f.lat, f.lon)
            if (raw / dt > sport.maxSpeed * 1.5 && raw > f.accuracy * 2) { rejected++; return false }
        }
        lastFix = f
        val (lat, lon) = kalman.process(f)
        f.alt?.let { a ->
            val sm = gpsAlt.add(a)
            gpsElevation.add(sm)
            if (baro != null && baroOffset == null && f.accuracy < 15) baroOffset = sm - (baro!!.current ?: sm)
        }

        val l = last
        val dt = if (l != null) (f.time - l.time) / 1000.0 else 0.0
        currentSpeed = kalman.speed
        val speedNow = f.speed?.toDouble()?.takeIf { it >= 0 } ?: currentSpeed
        val v = speedNow
        val step = dt.coerceAtMost(15.0)
        val stepMs = (step * 1000).toLong()
        // Auto-pause with hysteresis: the state only flips after 3 s of consistent evidence.
        if (autoPaused) {
            if (v >= sport.autoPauseSpeed * 1.3) {
                if (movingSince == null) movingSince = f.time
                pendingDist += v * step; pendingMs += stepMs
                if (f.time - movingSince!! >= 5000) {   // movement confirmed: credit the confirmation window
                    autoPaused = false
                    if (!paused) { distance += pendingDist; movingMs += pendingMs }
                    movingSince = null; pendingDist = 0.0; pendingMs = 0
                }
            } else { movingSince = null; pendingDist = 0.0; pendingMs = 0 }
        } else {
            if (v < sport.autoPauseSpeed) { if (stillSince == null) stillSince = f.time } else { stillSince = null; stillDist = 0.0; stillMs = 0 }
            if (!paused) {
                distance += v * step; movingMs += stepMs
                maxSpeed = max(maxSpeed, min(v, sport.maxSpeed))
                if (stillSince != null) { stillDist += v * step; stillMs += stepMs }
            }
            if (stillSince != null && f.time - stillSince!! >= 3000) {  // standing confirmed: take back that window
                autoPaused = true
                distance -= stillDist; movingMs -= stillMs
                stillSince = null; stillDist = 0.0; stillMs = 0
            }
        }
        val moving = !paused && !autoPaused && l != null
        val p = TrackPoint(f.time, lat, lon, altitude, distance, moving)
        points += p; last = p
        checkSplit()
        return true
    }

    fun addHeartRate(time: Long, bpm: Int) {
        if (bpm in 30..230) hr += time to bpm
    }

    /** Barometric altitude in m (relative; aligned to GPS once a good fix exists). */
    fun addBarometricAltitude(alt: Double) {
        val b = baro ?: Elevation(1.0).also { baro = it }
        b.add(alt)
    }

    private fun checkSplit() {
        val km = (distance / 1000).toInt()
        while (splits.size < km) {
            val hrs = hr.drop(splitHrStart).map { it.second }
            splitHrStart = hr.size
            val alt = altitude
            splits += Split(splits.size + 1, (movingMs - splitStartMoving) / 1000.0,
                hrs.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                if (alt != null && splitStartElevation != null) alt - splitStartElevation!! else 0.0)
            splitStartMoving = movingMs
            splitStartElevation = alt
        }
        if (splitStartElevation == null) splitStartElevation = altitude
    }
}

/** User profile for calories and heart-rate zones. */
data class Profile(val weightKg: Double = 75.0, val age: Int = 35, val male: Boolean = true, val maxHr: Int? = null) {
    val hrMax get() = maxHr ?: (208 - 0.7 * age).toInt() // Tanaka formula

    /** kcal; Keytel et al. (2005) when heart rate is known, otherwise MET. */
    fun calories(sport: Sport, seconds: Double, avgHr: Int?): Double {
        val minutes = seconds / 60
        if (avgHr != null && avgHr > 60) {
            val perMin = if (male) (-55.0969 + 0.6309 * avgHr + 0.1988 * weightKg + 0.2017 * age) / 4.184
            else (-20.4022 + 0.4472 * avgHr - 0.1263 * weightKg + 0.074 * age) / 4.184
            if (perMin > 0) return perMin * minutes
        }
        return sport.met * weightKg * minutes / 60
    }

    /** Seconds per zone 1..5 (50-60-70-80-90-100 % HFmax). */
    fun zones(hr: List<Pair<Long, Int>>): DoubleArray {
        val out = DoubleArray(5)
        for (i in 1 until hr.size) {
            val dt = ((hr[i].first - hr[i - 1].first) / 1000.0).coerceAtMost(30.0)
            val pct = hr[i - 1].second.toDouble() / hrMax
            val z = ((pct - 0.5) * 10).toInt()
            if (z >= 0) out[min(z, 4)] += dt
        }
        return out
    }
}

object Fmt {
    fun duration(sec: Double): String {
        val s = sec.toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
    fun km(m: Double) = "%.2f".format(m / 1000)
    /** min/km from m/s */
    fun pace(mps: Double): String = if (mps < 0.3) "–:––" else duration(1000 / mps)
    fun kmh(mps: Double) = "%.1f".format(mps * 3.6)
    fun speedOrPace(sport: Sport, mps: Double) = if (sport.showPace) pace(mps) + " /km" else kmh(mps) + " km/h"
    fun near(a: Double, b: Double, eps: Double) = abs(a - b) <= eps
}
