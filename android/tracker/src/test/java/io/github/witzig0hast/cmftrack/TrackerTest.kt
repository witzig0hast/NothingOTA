package io.github.witzig0hast.cmftrack

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.location.LocationManager
import android.view.View
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1500dp-xxhdpi")
class TrackerTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val t0 = 1_762_930_000_000L

    /** 3 km loop at 5:20/km with GPS noise and Doppler speed, as a phone would deliver it. */
    private fun loop(): List<Location> {
        val rnd = Random(3)
        val r = 3000 / (2 * PI)
        val v = 1000 / 320.0
        val n = (3000 / v).toInt()
        return (0..n).map { i ->
            val a = i * v / r
            val north = r * sin(a) + rnd.nextGaussian() * 3; val east = r * (1 - cos(a)) + rnd.nextGaussian() * 3
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 52.52 + north / 111_195.0; longitude = 13.40 + east / (111_195.0 * cos(52.52 * PI / 180))
                altitude = 40 + 10 * sin(a) + rnd.nextGaussian() * 3; accuracy = 4.5f
                speed = (v + rnd.nextGaussian() * 0.15).toFloat(); time = t0 + i * 1000L
            }
        }
    }

    @Test
    fun recordingServiceEndToEnd() {
        shadowOf(ctx).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        Prefs(ctx).liveHr = false
        val ctl = Robolectric.buildService(RecordingService::class.java, Intent().setAction(RecordingService.ACTION_START).putExtra(RecordingService.EXTRA_SPORT, "RUN")).create()
        val svc = ctl.get()
        svc.onStartCommand(Intent().setAction(RecordingService.ACTION_START).putExtra(RecordingService.EXTRA_SPORT, "RUN"), 0, 1)
        loop().forEach(svc::onLocationChanged)
        val live = Recorder.live.value
        assertTrue(live.recording)
        svc.onStartCommand(Intent().setAction(RecordingService.ACTION_STOP), 0, 2)

        val store = Store(ctx)
        val a = store.list().single()
        println("distance=${a.distance} moving=${a.movingMs / 1000}s splits=${a.splits.map { it.seconds.toInt() }} gain=${a.elevGain}")
        assertTrue("distance ${a.distance}", kotlin.math.abs(a.distance - 3000) < 30)       // < 1 %
        assertTrue("moving ${a.movingMs}", kotlin.math.abs(a.movingMs / 1000.0 - 960) < 15)
        assertEquals(2, a.splits.size)                                                     // full km only
        a.splits.forEach { assertTrue("split ${it.seconds}", it.seconds in 310.0..330.0) }
        assertTrue("elevation ${a.elevGain}", a.elevGain in 10.0..35.0)                     // one 20 m hill + noise
        assertTrue(a.finished)
        assertTrue(store.points(a.id).size > 900)
    }

    @Test
    fun watchHeartRateAndExports() = runTest {
        val store = Store(ctx)
        val id = store.create(Sport.RUN, t0)
        val stats = TrackStats(Sport.RUN)
        loop().forEach { l -> if (stats.addFix(Fix(l.time, l.latitude, l.longitude, l.altitude, l.accuracy, l.speed))) store.addPoint(id, stats.points.last()) }
        val end = t0 + 960_000
        store.save(Activity(id, Sport.RUN, t0, end, stats.distance, stats.movingMs, stats.elevationGain, 0.0, null, null, null, stats.maxSpeed, stats.splits, null, true))

        // CMF app put the watch's heart rate into Health Connect
        val hc = FakeHealthConnectClient(packageName = "com.nothing.cmf.watch")
        val samples = (0 until 960 step 5).map { HeartRateRecord.Sample(Instant.ofEpochMilli(t0 + it * 1000L), 140L + it / 30) }
        hc.insertRecords(listOf(HeartRateRecord(Instant.ofEpochMilli(t0), ZoneOffset.UTC, Instant.ofEpochMilli(end), ZoneOffset.UTC, samples, Metadata.manualEntry())))
        val res = Watch.heartRate(hc, t0, end)
        val hr = res.samples; val src = res.source
        assertEquals(192, hr.size); assertEquals("com.nothing.cmf.watch", src)
        store.replaceHr(id, hr)
        val a = Analysis.withHeartRate(store.get(id)!!, store.points(id), hr, Profile(), src)
        store.save(a)
        assertEquals(155.0, a.avgHr!!.toDouble(), 2.0); assertEquals(171, a.maxHr)
        assertTrue(a.splits.all { it.avgHr != null })
        assertTrue("kcal ${a.kcal}", a.kcal!! in 150.0..300.0)

        // TCX / GPX are valid XML with heart rate on the track points
        val pts = store.points(id)
        val tcx = Exporters.tcx(a, pts, hr)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(tcx.byteInputStream())
        assertEquals(pts.size, doc.getElementsByTagName("Trackpoint").length)
        assertTrue(doc.getElementsByTagName("HeartRateBpm").length > pts.size * 0.9)
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Exporters.gpx(a, pts, hr).byteInputStream())

        // server ZIP layout
        ZipFile(Exporters.serverZip(a, hr, File(ctx.cacheDir, "w.zip"))).use { z ->
            val w = z.getInputStream(z.getEntry("workouts.csv")).bufferedReader().readLines()
            assertTrue(w[1], w[1].contains(",Laufen,3.0"))
            assertEquals(193, z.getInputStream(z.getEntry("heart_rate.csv")).bufferedReader().readLines().size)
        }

        // screenshot of the detail screen
        val act = Robolectric.buildActivity(DetailActivity::class.java, Intent(ctx, DetailActivity::class.java).putExtra("id", id)).setup().get()
        shoot(act.findViewById(R.id.root), "detail")
    }

    @Test
    fun recordScreenshot() {
        val pts = TrackStats(Sport.RUN).also { s -> loop().take(700).forEach { l -> s.addFix(Fix(l.time, l.latitude, l.longitude, l.altitude, l.accuracy, l.speed)) } }
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        Recorder.state.value = LiveState(true, false, false, 1, Sport.RUN, t0, pts.distance, pts.movingMs, 3.2, pts.avgSpeed, 152, 148, 172,
            pts.elevationGain, 48.0, 4f, Split(2, 318.0, 151, 4.0), pts.points, "Puls verbunden: Polar H10")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shoot(act.findViewById(R.id.root), "record")
        Recorder.state.value = LiveState()
    }

    @Test
    fun watchWorkoutImportWithRouteAndSparseHeartRate() = runTest {
        val hc = FakeHealthConnectClient(packageName = "com.nothing.cmf.watch")
        val locs = loop().map { l ->
            androidx.health.connect.client.records.ExerciseRoute.Location(Instant.ofEpochMilli(l.time), l.latitude, l.longitude,
                androidx.health.connect.client.units.Length.meters(4.0), null, androidx.health.connect.client.units.Length.meters(l.altitude))
        }
        val end = t0 + 961_000
        val session = androidx.health.connect.client.records.ExerciseSessionRecord(Instant.ofEpochMilli(t0), ZoneOffset.UTC, Instant.ofEpochMilli(end), ZoneOffset.UTC,
            Metadata.manualEntry(), androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, "Lauf auf der Uhr", null,
            emptyList(), emptyList(), androidx.health.connect.client.records.ExerciseRoute(locs), null)
        // the watch measured only every 4 minutes
        val hr = (0..4).map { HeartRateRecord.Sample(Instant.ofEpochMilli(t0 + it * 240_000L), 130L + it * 5) }
        hc.insertRecords(listOf(session, HeartRateRecord(Instant.ofEpochMilli(t0), ZoneOffset.UTC, Instant.ofEpochMilli(end), ZoneOffset.UTC, hr, Metadata.manualEntry())))

        val workouts = Watch.workouts(hc, 36500)
        assertEquals(1, workouts.size); assertEquals(Sport.RUN, workouts[0].sport)
        val id = WatchImport.import(ctx, hc, workouts[0], null)
        val a = Store(ctx).get(id)!!
        assertEquals("watch", a.origin); assertEquals("Lauf auf der Uhr", a.title)
        assertTrue("distance ${a.distance}", kotlin.math.abs(a.distance - 3000) < 90)
        assertEquals(140.0, a.avgHr!!.toDouble(), 1.0)
        assertTrue(a.efforts.containsKey("1 km"))
        // importing twice does not duplicate
        assertEquals(id, WatchImport.import(ctx, hc, workouts[0], null))
        // sparse samples are interpolated for every track point (TCX)
        assertEquals(137, Analysis.hrAt(Store(ctx).heartRates(id), t0 + 360_000)) // between 135 (4 min) and 140 (8 min)
        val explain = Watch.HrResult(emptyList(), null, null).explain(t0, end)
        assertTrue(explain, explain.contains("Health Connect"))
    }

    @Test
    fun plannedRouteAndBestEfforts() {
        val smooth = TrackStats(Sport.RUN).also { st -> loop().forEach { l -> st.addFix(Fix(l.time, l.latitude, l.longitude, l.altitude, l.accuracy, l.speed)) } }
        val gpx = Exporters.gpx(Activity(1, Sport.RUN, t0, t0 + 1000, finished = true), smooth.points, emptyList())
        val r = PlannedRoute.parseGpx(gpx.byteInputStream(), "x")
        assertTrue("length ${r.length}", r.length in 2950.0..3200.0)
        val (off, along) = r.locate(r.points[200].first + 50 / 111_195.0, r.points[200].second)
        assertTrue("off $off", off in 30.0..70.0); assertTrue("along $along", along in 500.0..800.0)
        assertTrue(r.locate(r.points[400].first, r.points[400].second).first < 5)

        val s = TrackStats(Sport.RUN)
        loop().forEach { l -> s.addFix(Fix(l.time, l.latitude, l.longitude, l.altitude, l.accuracy, l.speed)) }
        val e = BestEfforts.compute(s.points)
        assertEquals(setOf("400 m", "1 km", "1 Meile"), e.keys)
        assertEquals(320.0, e["1 km"]!!, 8.0)                // 5:20 /km
        assertEquals(Stats.weekStart(t0), Stats.weekStart(t0 + 3_600_000))
    }

    @Test
    fun historyAndStatsScreenshots() {
        val store = Store(ctx)
        val now = System.currentTimeMillis()
        for ((k, km) in listOf(0 to 3, 2 to 5, 9 to 8, 16 to 4, 30 to 10).withIndex()) {
            val start = now - km.first * 86_400_000L - 3_600_000
            val id = store.create(Sport.RUN, start)
            val s = TrackStats(Sport.RUN)
            val pace = 300.0 + k * 8
            loop().take((km.second * 1000 / (1000 / pace)).toInt().coerceAtMost(960)).forEach { l ->
                if (s.addFix(Fix(start + (l.time - t0), l.latitude, l.longitude, l.altitude, l.accuracy, l.speed))) store.addPoint(id, s.points.last())
            }
            val pts = store.points(id)
            store.save(Activity(id, Sport.RUN, start, start + s.movingMs, s.distance, s.movingMs, s.elevationGain, 0.0, 150 - k, 170, 300.0, s.maxSpeed,
                s.splits, "Uhr (Health Connect)", true, efforts = BestEfforts.compute(pts)))
        }
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        act.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav).selectedItemId = R.id.tab_history
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shoot(act.findViewById(R.id.root), "history")
        act.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav).selectedItemId = R.id.tab_stats
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shoot(act.findViewById(R.id.root), "stats")
    }

    @Test
    fun heartRateMeasurementParsing() {
        assertEquals(72, HeartRateBle.parse(byteArrayOf(0x00, 72)))
        assertEquals(300, HeartRateBle.parse(byteArrayOf(0x01, 0x2C, 0x01)))
    }

    private fun shoot(root: View, name: String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.width, root.height)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(System.getProperty("user.dir"), "build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
