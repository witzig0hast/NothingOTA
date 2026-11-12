package io.github.witzig0hast.cmfexport

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExporterTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val t0 = 1_762_900_000L

    private fun ZipFile.csv(name: String): List<Map<String, String>> {
        val lines = getInputStream(getEntry(name)!!).bufferedReader().readLines()
        val head = lines.first().split(",")
        return lines.drop(1).map { l -> head.zip(l.split(",")).toMap() }
    }

    private fun makeGadgetbridgeDb(f: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        listOf(
            "CREATE TABLE DEVICE (_id INTEGER PRIMARY KEY, NAME TEXT, MANUFACTURER TEXT, IDENTIFIER TEXT)",
            "CREATE TABLE CMF_ACTIVITY_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, RAW_INTENSITY INTEGER, STEPS INTEGER, RAW_KIND INTEGER, HEART_RATE INTEGER, DISTANCE INTEGER, CALORIES INTEGER)",
            "CREATE TABLE CMF_HEART_RATE_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, HEART_RATE INTEGER)",
            "CREATE TABLE CMF_SPO2_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, SPO2 INTEGER)",
            "CREATE TABLE CMF_STRESS_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, STRESS INTEGER)",
            "CREATE TABLE CMF_SLEEP_SESSION_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, WAKEUP_TIME INTEGER, METADATA BLOB)",
            "CREATE TABLE CMF_SLEEP_STAGE_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, DURATION INTEGER, STAGE INTEGER)",
            "CREATE TABLE CMF_WORKOUT_GPS_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, LATITUDE INTEGER, LONGITUDE INTEGER)",
            "CREATE TABLE BASE_ACTIVITY_SUMMARY (_id INTEGER PRIMARY KEY, NAME TEXT, START_TIME INTEGER, END_TIME INTEGER, ACTIVITY_KIND INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, SUMMARY_DATA TEXT, RAW_SUMMARY_DATA BLOB)",
            "INSERT INTO DEVICE VALUES (1,'CMF Watch 3 Pro','Nothing','AA:BB')",
        ).forEach(db::execSQL)
        for (i in 0 until 10) db.execSQL("INSERT INTO CMF_ACTIVITY_SAMPLE VALUES (${t0 - 7200 + i * 60},1,1,0,100,0,70,80,5)")
        db.execSQL("INSERT INTO CMF_HEART_RATE_SAMPLE VALUES (${t0 * 1000 + 600000},1,1,58)")
        db.execSQL("INSERT INTO CMF_SPO2_SAMPLE VALUES (${t0 * 1000 + 600000},1,1,97)")
        db.execSQL("INSERT INTO CMF_STRESS_SAMPLE VALUES (${t0 * 1000 + 600000},1,1,23)")
        db.execSQL("INSERT INTO CMF_SLEEP_SESSION_SAMPLE VALUES (${t0 * 1000},1,1,${(t0 + 7200) * 1000},NULL)")
        var t = t0
        for ((dur, st) in listOf(1800 to 2, 3600 to 1, 1200 to 3, 600 to 4)) {
            db.execSQL("INSERT INTO CMF_SLEEP_STAGE_SAMPLE VALUES (${t * 1000},1,1,$dur,$st)"); t += dur
        }
        val w0 = t0 - 3600
        val raw = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(w0.toInt()); putShort(1800); put(0x02); position(26); putInt((w0 + 1800).toInt()); put(1)
        }.array()
        db.execSQL("INSERT INTO BASE_ACTIVITY_SUMMARY VALUES (1,NULL,?,?,0,1,1,NULL,?)", arrayOf<Any>(w0 * 1000, (w0 + 1800) * 1000, raw))
        for (i in 0 until 3) db.execSQL("INSERT INTO CMF_WORKOUT_GPS_SAMPLE VALUES (${(w0 + i * 60) * 1000},1,1,${525200000 + i},${134050000 + i})")
        db.close()
    }

    @Test
    fun gadgetbridgeZipExport() {
        val dbFile = File(ctx.cacheDir, "Gadgetbridge")
        makeGadgetbridgeDb(dbFile)
        val zipIn = File(ctx.cacheDir, "gb-export.zip")
        ZipOutputStream(zipIn.outputStream()).use { z -> z.putNextEntry(ZipEntry("database/Gadgetbridge")); dbFile.inputStream().copyTo(z); z.closeEntry() }

        val out = File(ctx.cacheDir, "out.zip")
        val res = GadgetbridgeExporter(ctx).export(Uri.fromFile(zipIn), out) {}
        ZipFile(out).use { z ->
            val night = z.csv("sleep_sessions.csv").single()
            assertEquals("60.0", night["deep_min"]); assertEquals("30.0", night["light_min"])
            assertEquals("20.0", night["rem_min"]); assertEquals("10.0", night["awake_min"]); assertEquals("110.0", night["asleep_min"])
            assertEquals(4, z.csv("sleep_stages.csv").size)
            assertEquals(10, z.csv("activity.csv").size)
            assertEquals("58", z.csv("heart_rate.csv").single()["heart_rate"])
            assertEquals("97", z.csv("spo2.csv").single()["spo2"])
            assertEquals("23", z.csv("stress.csv").single()["stress"])
            val w = z.csv("workouts.csv").single()
            assertEquals("OUTDOOR_RUNNING", w["workout_type"]); assertEquals("1800", w["active_seconds"]); assertEquals("3", w["gps_points"])
            val gpx = z.entries().toList().single { it.name.startsWith("gpx/") }
            assertEquals(3, z.getInputStream(gpx).bufferedReader().readText().split("<trkpt").size - 1)
            assertEquals(1000, z.csv("daily_summary.csv").sumOf { it["steps"]!!.toInt() })
            assertTrue(z.getEntry("raw/CMF_SLEEP_STAGE_SAMPLE.csv") != null)
            assertTrue(z.getEntry("report.html") != null)
        }
        assertTrue(res.summary, res.summary.contains("Tief 1h00"))
    }

    @Test
    fun healthConnectExport() = runTest {
        val start = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES)
        val off = ZoneOffset.UTC
        fun at(min: Long) = start.plus(min, ChronoUnit.MINUTES)

        val client = FakeHealthConnectClient(packageName = "com.nothing.cmf.watch")
        client.insertRecords(listOf(
            SleepSessionRecord(
                startTime = at(0), startZoneOffset = off, endTime = at(120), endZoneOffset = off,
                metadata = Metadata.autoRecorded(androidx.health.connect.client.records.metadata.Device(type = androidx.health.connect.client.records.metadata.Device.TYPE_WATCH)),
                stages = listOf(
                    SleepSessionRecord.Stage(at(0), at(30), SleepSessionRecord.STAGE_TYPE_LIGHT),
                    SleepSessionRecord.Stage(at(30), at(90), SleepSessionRecord.STAGE_TYPE_DEEP),
                    SleepSessionRecord.Stage(at(90), at(110), SleepSessionRecord.STAGE_TYPE_REM),
                    SleepSessionRecord.Stage(at(110), at(120), SleepSessionRecord.STAGE_TYPE_AWAKE),
                ),
            ),
            HeartRateRecord(at(200), off, at(202), off,
                listOf(HeartRateRecord.Sample(at(200), 61), HeartRateRecord.Sample(at(201), 63)), Metadata.manualEntry()),
            StepsRecord(at(300), off, at(310), off, 500, Metadata.manualEntry()),
        ))

        val out = File(ctx.cacheDir, "hc.zip")
        val res = HealthConnectExporter(client).export(30, onlyCmf = true, out = out) {}
        ZipFile(out).use { z ->
            val n = z.csv("sleep_sessions.csv").single()
            assertEquals("60.0", n["deep_min"]); assertEquals("30.0", n["light_min"]); assertEquals("20.0", n["rem_min"])
            assertEquals("10.0", n["awake_min"]); assertEquals("110.0", n["asleep_min"])
            assertEquals(4, z.csv("sleep_stages.csv").size)
            assertEquals(listOf("61", "63"), z.csv("heart_rate.csv").map { it["bpm"] })
            assertEquals("500", z.csv("steps.csv").single()["steps"])
            assertEquals(500, z.csv("daily_summary.csv").sumOf { it["steps"]!!.toInt() })
        }
        assertTrue(res.summary, res.summary.contains("com.nothing.cmf.watch"))

        // a non-CMF app's data is filtered out
        val other = FakeHealthConnectClient(packageName = "com.other.app")
        other.insertRecords(listOf(StepsRecord(at(300), off, at(310), off, 7, Metadata.manualEntry())))
        val out2 = File(ctx.cacheDir, "hc2.zip")
        HealthConnectExporter(other).export(30, onlyCmf = true, out = out2) {}
        ZipFile(out2).use { z -> assertEquals(0, z.csv("steps.csv").size) }
    }
}
