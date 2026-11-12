package io.github.witzig0hast.cmftrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos

class TrackMathTest {
    private val lat0 = 52.52
    private val lon0 = 13.405
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * cos(lat0 * PI / 180)

    /** Simulates running due north at [speed] m/s with gaussian GPS noise, 1 Hz. */
    private fun simulate(
        stats: TrackStats, meters: Double, speed: Double, noise: Double, seed: Long = 1,
        stopAtM: Double? = null, stopSec: Int = 0, jumpAtSec: Int? = null,
    ) {
        val rnd = Random(seed)
        var t = 0L; var pos = 0.0; var stopped = false
        var sec = 0
        while (pos < meters) {
            if (stopAtM != null && !stopped && pos >= stopAtM) {
                repeat(stopSec) { t += 1000; sec++; fix(stats, t, pos, noise, rnd) }
                stopped = true
            }
            t += 1000; sec++; pos += speed
            if (sec == jumpAtSec) {
                stats.addFix(Fix(t, lat0 + (pos + 400) / mPerDegLat, lon0, accuracy = 8f)); continue
            }
            fix(stats, t, pos, noise, rnd)
            stats.addHeartRate(t, 150 + (pos / 1000).toInt())
        }
    }

    private fun fix(stats: TrackStats, t: Long, pos: Double, noise: Double, rnd: Random) {
        val n = rnd.nextGaussian() * noise; val e = rnd.nextGaussian() * noise
        stats.addFix(Fix(t, lat0 + (pos + n) / mPerDegLat, lon0 + e / mPerDegLon, 34.0 + rnd.nextGaussian() * 2, accuracy = (noise * 1.5).toFloat()))
    }

    @Test
    fun distanceAndMovingTimeAreAccurateWithNoise() {
        val s = TrackStats(Sport.RUN)
        simulate(s, 5050.0, 3.333, noise = 3.0, stopAtM = 2500.0, stopSec = 90, jumpAtSec = 600)
        val err = (s.distance - 5050) / 5050
        println("distance=${s.distance} moving=${s.movingMs / 1000}s splits=${s.splits.map { it.seconds.toInt() }} rejected=${s.rejected} gain=${s.elevationGain}")
        assertTrue("distance error ${err * 100}%", kotlin.math.abs(err) < 0.015)
        // 5050 m / 3.333 m/s = 1515 s of moving time; the 90 s stop must not count
        assertTrue("moving ${s.movingMs}", kotlin.math.abs(s.movingMs / 1000.0 - 1515) < 20)
        assertEquals(5, s.splits.size)
        s.splits.forEach { assertTrue("split ${it.seconds}", it.seconds in 270.0..330.0) }
        assertTrue("GPS jump rejected", s.rejected >= 1)
        assertTrue("noise must not create climbing: ${s.elevationGain}", s.elevationGain < 15)
        assertEquals(152.0, s.avgHr!!.toDouble(), 1.0)
    }

    @Test
    fun standingStillAddsNoDistance() {
        val s = TrackStats(Sport.WALK)
        val rnd = Random(7)
        for (t in 1..600) fix(s, t * 1000L, 0.0, 4.0, rnd)
        assertTrue("distance while standing ${s.distance}", s.distance < 15)
        assertTrue(s.autoPaused)
    }

    @Test
    fun inaccurateFixesAreIgnored() {
        val s = TrackStats(Sport.RUN)
        assertTrue(!s.addFix(Fix(1000, lat0, lon0, accuracy = 80f)))
        assertEquals(0, s.points.size)
    }

    @Test
    fun barometerHysteresis() {
        val e = Elevation(1.0)
        listOf(100.0, 100.4, 99.7, 100.3, 101.2, 102.5, 102.1, 103.6, 101.0).forEach(e::add)
        assertEquals(3.6, e.gain, 0.01)
        assertEquals(2.6, e.loss, 0.01)
    }

    @Test
    fun caloriesAndZones() {
        val p = Profile(weightKg = 75.0, age = 35, male = true)
        val kcal = p.calories(Sport.RUN, 3600.0, 150)
        assertTrue("kcal $kcal", kcal in 700.0..1000.0)
        assertEquals(Sport.RUN.met * 75, p.calories(Sport.RUN, 3600.0, null), 0.1)
        val z = p.zones(listOf(0L to 100, 10_000L to 140, 20_000L to 170, 30_000L to 170))
        assertEquals(30.0, z.sum(), 0.01)
        assertEquals("1:00:00", Fmt.duration(3600.0)); assertEquals("5:00", Fmt.pace(1000 / 300.0))
    }
}
