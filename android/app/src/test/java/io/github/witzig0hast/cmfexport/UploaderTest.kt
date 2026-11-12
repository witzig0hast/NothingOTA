package io.github.witzig0hast.cmfexport

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploaderTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    private suspend fun appZip(steps: Long): File {
        val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val client = FakeHealthConnectClient(packageName = "com.nothing.cmf.watch")
        client.insertRecords(listOf(StepsRecord(now.minusSeconds(600), ZoneOffset.UTC, now.minusSeconds(60), ZoneOffset.UTC, steps, Metadata.manualEntry())))
        val f = File(ctx.cacheDir, "cmf_healthconnect_test.zip")
        HealthConnectExporter(client).export(1, true, f) {}
        return f
    }

    @Test
    fun uploadToMockServer() = runTest {
        val srv = MockWebServer()
        srv.enqueue(MockResponse().setBody("""{"state":{"steps_today":1},"attributes":{}}"""))
        srv.enqueue(MockResponse().setBody("""{"ok":true,"counts":{"days":1}}"""))
        srv.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        srv.start()
        try {
            val s = Settings(ctx).apply { serverUrl = srv.url("/").toString(); token = "t0k" }
            assertTrue(Uploader(s).test().startsWith("Verbunden"))
            assertEquals("/api/latest", srv.takeRequest().path)
            val zip = appZip(123)
            assertEquals("""{"days":1}""", Uploader(s).upload(zip))
            val up = srv.takeRequest()
            assertEquals("POST", up.method)
            assertTrue(up.path!!.startsWith("/api/upload?name=cmf_healthconnect_test.zip"))
            assertEquals("Bearer t0k", up.getHeader("Authorization"))
            assertEquals(zip.length(), up.bodySize)
            s.token = "falsch"
            assertTrue(runCatching { Uploader(s).test() }.exceptionOrNull()?.message!!.contains("401"))
        } finally { srv.shutdown() }
    }

    /** Integration test against a running cmfserver: CMF_SERVER_URL=http://host:port CMF_SERVER_TOKEN=... */
    @Test
    fun uploadToRealServer() = runTest {
        val url = System.getenv("CMF_SERVER_URL") ?: return@runTest
        val s = Settings(ctx).apply { serverUrl = url; token = System.getenv("CMF_SERVER_TOKEN") ?: "" }
        Uploader(s).upload(appZip(4242))
        val c = URL("$url/api/latest").openConnection().apply { setRequestProperty("Authorization", "Bearer ${s.token}") }
        val state = JSONObject(c.getInputStream().bufferedReader().readText()).getJSONObject("state")
        assertTrue(state.toString(), state.getLong("steps_today") >= 4242)
    }
}
