package io.github.witzig0hast.cmfexport

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Persisted settings for the server connection. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var serverUrl: String
        get() = p.getString("server_url", "") ?: ""
        set(v) = p.edit().putString("server_url", v.trim().trimEnd('/')).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()
    var autoSync: Boolean
        get() = p.getBoolean("auto_sync", false)
        set(v) = p.edit().putBoolean("auto_sync", v).apply()
    var onlyCmf: Boolean
        get() = p.getBoolean("only_cmf", true)
        set(v) = p.edit().putBoolean("only_cmf", v).apply()
    /** Gadgetbridge export file (persisted SAF permission) that is re-read on every sync. */
    var gadgetbridgeUri: String?
        get() = p.getString("gb_uri", null)
        set(v) = p.edit().putString("gb_uri", v).apply()
    var intervalMin: Long
        get() = p.getLong("interval_min", 15)
        set(v) = p.edit().putLong("interval_min", v).apply()
    var gadgetbridgeSignature: String?
        get() = p.getString("gb_sig", null)
        set(v) = p.edit().putString("gb_sig", v).apply()
    var dashboard: Dashboard
        get() {
            // v1.2 showed a wrong Bluetooth battery value (the CMF watch reports a fixed 100 %): drop it once
            if (!p.getBoolean("battery_fix_v13", false)) {
                val d = Dashboard.fromJson(p.getString("dashboard", null))
                p.edit().putString("dashboard", d.copy(battery = null, batteryTime = null).toJson()).putBoolean("battery_fix_v13", true).apply()
            }
            return Dashboard.fromJson(p.getString("dashboard", null))
        }
        set(v) = p.edit().putString("dashboard", v.toJson()).apply()
    var trend: List<TrendDay>
        get() = runCatching {
            val a = org.json.JSONArray(p.getString("trend", "[]"))
            (0 until a.length()).map { TrendDay.parse(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(v) = p.edit().putString("trend", org.json.JSONArray(v.map { it.json() }).toString()).apply()
    var lastSync: String
        get() = p.getString("last_sync", "") ?: ""
        set(v) = p.edit().putString("last_sync", v).apply()

    val configured get() = serverUrl.startsWith("http")
}

/** Talks to the cmfserver backend (see server/ in the repository). */
class Uploader(private val s: Settings) {

    private fun open(path: String): HttpURLConnection =
        (URL(s.serverUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 120_000
            if (s.token.isNotEmpty()) setRequestProperty("Authorization", "Bearer ${s.token}")
        }

    /** Returns a short human-readable status, throws on failure. */
    fun test(): String {
        val c = open("/api/latest")
        try {
            when (c.responseCode) {
                200 -> {
                    val st = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("state")
                    return "Verbunden ✓ (${st.length()} Werte auf dem Server)"
                }
                401 -> throw IOException("Token falsch (401)")
                else -> throw IOException("HTTP ${c.responseCode}")
            }
        } finally { c.disconnect() }
    }

    fun upload(file: File): String = upload(file.name, file.length()) { file.inputStream() }

    fun upload(name: String, length: Long, input: () -> java.io.InputStream): String {
        val c = open("/api/upload?name=" + URLEncoder.encode(name, "UTF-8"))
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(length)
            c.outputStream.use { o -> input().use { it.copyTo(o) } }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code != 200) {
                val msg = runCatching { JSONObject(body).getString("error") }.getOrDefault(body.take(200))
                throw IOException("Server: HTTP $code $msg")
            }
            return runCatching { JSONObject(body).getJSONObject("counts").toString() }.getOrDefault("ok")
        } finally { c.disconnect() }
    }
}

/** Periodic background sync: Health Connect (last days) + Gadgetbridge file -> server. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val NAME = "cmf-sync"
        const val SYNC_DAYS = 2L
        /** Selectable intervals in minutes (15 = Android's minimum for periodic background work). */
        val INTERVALS = listOf(15L, 30L, 60L, 180L, 360L)

        fun label(min: Long) = if (min < 60) "alle $min Minuten" else if (min == 60L) "jede Stunde" else "alle ${min / 60} Stunden"

        fun schedule(ctx: Context, enabled: Boolean, minutes: Long = Settings(ctx).intervalMin) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes.coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Size + last-modified of a document, to skip re-uploading an unchanged Gadgetbridge file. */
        private fun signature(ctx: Context, u: Uri): String? = runCatching {
            ctx.contentResolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst()) "${c.getLong(0)}:${if (c.isNull(1)) 0 else c.getLong(1)}" else null
            }
        }.getOrNull()

        /**
         * One sync run (worker and "Jetzt synchronisieren"): Health Connect (last [SYNC_DAYS] days) and the Gadgetbridge file if it changed.
         * Updates the start-screen dashboard; uploads when a server is configured.
         */
        suspend fun syncOnce(ctx: Context, log: (String) -> Unit): Boolean {
            val s = Settings(ctx)
            val up = if (s.configured) Uploader(s) else null
            val dir = File(ctx.cacheDir, "sync").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            var ok = false
            var networkError = false
            var dash = s.dashboard

            fun upload(what: String, block: () -> String) {
                if (up == null) return
                try { log("$what → Server: ${block()}"); ok = true }
                catch (e: IOException) { log("$what Upload: ${e.message}"); networkError = true }
            }

            if (HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE) {
                try {
                    val f = File(dir, "cmf_healthconnect_sync.zip")
                    val res = HealthConnectExporter(HealthConnectClient.getOrCreate(ctx)).export(SYNC_DAYS, s.onlyCmf, f) {}
                    dash = dash.merge(res.dash)
                    s.trend = TrendDay.merge(s.trend, res.trend)
                    upload("Health Connect") { up!!.upload(f) }
                } catch (e: Exception) {
                    log("Health Connect: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            s.gadgetbridgeUri?.let { uri ->
                val u = Uri.parse(uri)
                val sig = signature(ctx, u)
                if (sig != null && sig == s.gadgetbridgeSignature && up != null) {
                    log("Gadgetbridge: Datei unverändert, übersprungen"); return@let
                }
                try {
                    // Dashboard from the file, then send the file as-is (the server parses it itself).
                    val f = File(dir, "gb.zip")
                    val gb = GadgetbridgeExporter(ctx).export(u, f) {}
                    dash = dash.merge(gb.dash)
                    s.trend = TrendDay.merge(s.trend, gb.trend)
                    f.delete()
                    upload("Gadgetbridge") {
                        val len = ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L
                        val res = if (len > 0) up!!.upload("Gadgetbridge", len) { ctx.contentResolver.openInputStream(u)!! }
                        else up!!.upload(File(dir, "gadgetbridge.bin").also { t ->
                            ctx.contentResolver.openInputStream(u)!!.use { i -> t.outputStream().use { i.copyTo(it) } }
                        })
                        s.gadgetbridgeSignature = sig
                        res
                    }
                } catch (e: Exception) {
                    log("Gadgetbridge: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            s.dashboard = dash
            CmfWidget.updateAll(ctx)
            if (ok) s.lastSync = iso(java.time.Instant.now()) ?: ""
            if (up != null && !ok && networkError) throw IOException("Upload fehlgeschlagen")
            return ok || up == null
        }
    }

    override suspend fun doWork(): Result = try {
        syncOnce(applicationContext) { android.util.Log.i("CmfSync", it) }
        Result.success()
    } catch (e: IOException) {
        Result.retry()
    }
}
