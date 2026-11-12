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
        val (hr, src) = WatchHeartRate.load(hc, t0, end)
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
