package io.github.witzig0hast.cmfexport

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private var last: ExportResult? = null
    private val busy = mutableListOf<View>()
    private val logText = StringBuilder()

    private fun <T : View> v(id: Int): T = findViewById(id)
    private val progress by lazy { v<LinearProgressIndicator>(R.id.progress) }
    private val log by lazy { v<TextView>(R.id.log) }

    // --- permission / file launchers --------------------------------------------------------
    private val hcPermissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val missing = HealthConnectExporter.PERMISSIONS - granted
        snack("Health Connect: ${granted.size} Freigaben" + if (missing.isNotEmpty()) " · ${missing.size} fehlen" else " ✓")
    }
    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) snack("Ohne Bluetooth-Freigabe kann der Uhr-Akku nicht gelesen werden")
    }
    private val pickDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        // Keep access so the background sync can re-read Gadgetbridge's auto-export file later.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onSuccess { settings.gadgetbridgeUri = uri.toString(); settings.gadgetbridgeSignature = null }
        renderGadgetbridge()
        runExport("gadgetbridge") { out, p -> GadgetbridgeExporter(this).export(uri, out, p) }
    }
    private val saveZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val zip = last?.zip ?: return@registerForActivityResult
        if (uri != null) lifecycleScope.launch {
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.use { o -> zip.inputStream().use { it.copyTo(o) } } }
            snack("Gespeichert")
        }
    }

    // --- lifecycle ---------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(R.layout.activity_main)
        bind()
        if (intent?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" || intent?.action == "android.intent.action.VIEW_PERMISSION_USAGE") showPrivacy()
    }

    override fun onResume() {
        super.onResume()
        renderDashboard()
    }

    private fun bind() {
        val url = v<TextInputEditText>(R.id.urlInput).apply { setText(settings.serverUrl) }
        val token = v<TextInputEditText>(R.id.tokenInput).apply { setText(settings.token) }
        fun saveServer() { settings.serverUrl = url.text.toString(); settings.token = token.text.toString() }

        // Interval dropdown
        val labels = SyncWorker.INTERVALS.map { SyncWorker.label(it).replaceFirstChar(Char::uppercase) }
        v<MaterialAutoCompleteTextView>(R.id.intervalInput).apply {
            setSimpleItems(labels.toTypedArray())
            setText(labels[SyncWorker.INTERVALS.indexOf(settings.intervalMin).coerceAtLeast(0)], false)
            setOnItemClickListener { _, _, pos, _ ->
                settings.intervalMin = SyncWorker.INTERVALS[pos]
                if (settings.autoSync) SyncWorker.schedule(this@MainActivity, true)
                renderServer()
            }
        }
        v<MaterialSwitch>(R.id.autoSync).apply {
            isChecked = settings.autoSync
            setOnCheckedChangeListener { sw, on ->
                saveServer()
                if (on && !settings.configured) { sw.isChecked = false; snack("Bitte zuerst die Server-Adresse eintragen"); return@setOnCheckedChangeListener }
                settings.autoSync = on
                SyncWorker.schedule(this@MainActivity, on)
                if (on) { askBluetooth(); hcClient()?.let { hcPermissions.launch(HealthConnectExporter.PERMISSIONS + HealthConnectExporter.BACKGROUND) } }
                renderServer()
            }
        }
        v<View>(R.id.testButton).also { busy += it }.setOnClickListener { saveServer(); background("Verbindung") { Uploader(settings).test() } }
        v<View>(R.id.sendButton).also { busy += it }.setOnClickListener { saveServer(); askBluetooth(); sync() }
        v<View>(R.id.refreshButton).also { busy += it }.setOnClickListener { saveServer(); askBluetooth(); sync() }

        v<MaterialSwitch>(R.id.onlyCmf).apply { isChecked = settings.onlyCmf; setOnCheckedChangeListener { _, c -> settings.onlyCmf = c } }
        v<View>(R.id.hcPermButton).setOnClickListener { hcClient()?.let { hcPermissions.launch(HealthConnectExporter.PERMISSIONS) } }
        v<View>(R.id.hcExportButton).also { busy += it }.setOnClickListener {
            val client = hcClient() ?: return@setOnClickListener
            val days = v<TextInputEditText>(R.id.daysInput).text.toString().toLongOrNull()?.coerceIn(1, 36500) ?: 3650
            val cmf = settings.onlyCmf
            runExport("healthconnect") { out, p -> HealthConnectExporter(client).export(days, cmf, out, p) }
        }
        v<View>(R.id.gbButton).also { busy += it }.setOnClickListener { pickDb.launch(arrayOf("*/*")) }

        v<View>(R.id.reportButton).setOnClickListener { showReport() }
        v<View>(R.id.saveButton).setOnClickListener { last?.let { saveZip.launch(it.zip.name) } }
        v<View>(R.id.shareButton).setOnClickListener { share() }
        v<View>(R.id.uploadButton).also { busy += it }.setOnClickListener {
            saveServer(); val z = last?.zip ?: return@setOnClickListener
            background("Upload") { "Gesendet: " + Uploader(settings).upload(z) }
        }
        v<TextView>(R.id.logHeader).setOnClickListener {
            val show = log.visibility != View.VISIBLE
            log.visibility = if (show) View.VISIBLE else View.GONE
            (it as TextView).text = if (show) "Protokoll ▴" else "Protokoll ▾"
        }
        v<TextView>(R.id.footer).text = "CMF Export ${packageManager.getPackageInfo(packageName, 0).versionName} · liest nur, löscht nichts"
        renderServer(); renderGadgetbridge()
    }

    // --- rendering ---------------------------------------------------------------------------
    private fun ago(t: Instant?): String {
        t ?: return ""
        val m = Duration.between(t, Instant.now()).toMinutes()
        return when {
            m < 1 -> "gerade eben"
            m < 60 -> "vor $m min"
            m < 24 * 60 -> "vor ${m / 60} h"
            else -> "vor ${m / 1440} Tagen"
        }
    }

    private fun renderDashboard() {
        val d = settings.dashboard
        val n = d.night
        val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        v<TextView>(R.id.sleepValue).text = n?.let { hm(it.asleep).replace("h", " h ") + " min" } ?: "–"
        v<TextView>(R.id.sleepTimes).text = n?.let {
            "${it.start.atZone(zone).format(hhmm)} – ${it.end.atZone(zone).format(hhmm)} · " +
                (it.efficiency?.let { e -> "Effizienz ${e.toInt()} %" } ?: "") +
                if (day(it.end) != LocalDate.now(zone)) " · ${day(it.end).format(DateTimeFormatter.ofPattern("dd.MM."))}" else ""
        } ?: "Noch keine Schlafdaten – „Jetzt aktualisieren“ tippen"
        v<SleepBarView>(R.id.sleepBar).set(n)
        v<TextView>(R.id.sleepLegend).text = n?.let {
            if (!it.hasStages) "ohne Schlafphasen" else "● Tief ${hm(it.deep)}   ● Leicht ${hm(it.light)}   ● REM ${hm(it.rem)}   ● Wach ${hm(it.awake)}"
        } ?: ""
        colorLegend()

        val nf = NumberFormat.getIntegerInstance()
        val today = LocalDate.now(zone).toString()
        v<TextView>(R.id.stepsValue).text = d.stepsToday?.takeIf { d.stepsDay == today }?.let { nf.format(it) } ?: if (d.stepsDay != null) "0" else "–"
        v<TextView>(R.id.stepsSub).text = if (d.stepsDay != null) "heute" else ""
        v<TextView>(R.id.hrValue).text = d.hr?.let { "$it" } ?: "–"
        v<TextView>(R.id.hrSub).text = d.hr?.let { "bpm · ${ago(d.hrTime)}" } ?: ""
        v<TextView>(R.id.batteryValue).text = d.battery?.let { "$it %" } ?: "–"
        v<TextView>(R.id.batterySub).text = d.batteryTime?.let { ago(it) } ?: "über Bluetooth/Gadgetbridge"

        val lastSync = runCatching { java.time.OffsetDateTime.parse(settings.lastSync).toInstant() }.getOrNull()
        val fresh = lastSync != null && Duration.between(lastSync, Instant.now()).toMinutes() < settings.intervalMin * 3
        v<TextView>(R.id.subtitle).text = when {
            lastSync != null -> "An Home Assistant gesendet ${ago(lastSync)}"
            settings.configured -> "Server eingestellt · noch nicht gesendet"
            else -> "Lokal · kein Server eingestellt"
        }
        v<View>(R.id.statusDot).backgroundTintList = ColorStateList.valueOf(
            if (fresh) 0xFF2EBD59.toInt() else if (settings.configured) getColor(R.color.stage_awake) else getColor(R.color.muted))
    }

    private fun colorLegend() {
        val tv = v<TextView>(R.id.sleepLegend)
        val text = tv.text.toString()
        val sp = android.text.SpannableString(text)
        val cols = listOf(R.color.stage_deep, R.color.stage_light, R.color.stage_rem, R.color.stage_awake)
        var from = 0
        for (c in cols) {
            val i = text.indexOf('●', from).takeIf { it >= 0 } ?: break
            sp.setSpan(android.text.style.ForegroundColorSpan(getColor(c)), i, i + 1, 0)
            from = i + 1
        }
        tv.text = sp
    }

    private fun renderServer() {
        v<View>(R.id.intervalLayout).visibility = if (settings.autoSync) View.VISIBLE else View.GONE
        v<TextView>(R.id.serverStatus).text = when {
            !settings.configured -> "Noch kein Server eingetragen – Anleitung: server/README.md im Repo"
            settings.autoSync -> "Sendet automatisch ${SyncWorker.label(settings.intervalMin)} (wenn Internet verfügbar)"
            else -> "Automatisches Senden ist aus"
        }
        renderDashboard()
    }

    private fun renderGadgetbridge() {
        v<TextView>(R.id.gbStatus).text = if (settings.gadgetbridgeUri != null) "✓ Datei gemerkt – wird bei jedem Sync neu gelesen" else "Keine Datei gewählt"
        v<MaterialButton>(R.id.gbButton).text = if (settings.gadgetbridgeUri != null) "Andere Datei wählen" else "Datei auswählen"
    }

    // --- actions -----------------------------------------------------------------------------
    private fun askBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !WatchBattery.hasPermission(this)) btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun sync() {
        setBusy(true)
        lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { SyncWorker.syncOnce(this@MainActivity) { m -> runOnUiThread { say(m) } } }
                snack(if (!settings.configured) "Aktualisiert (lokal)" else if (ok) "An Home Assistant gesendet ✓" else "Nichts gesendet – siehe Protokoll")
            } catch (e: Exception) {
                say("Fehler: ${e.message}"); snack("Senden fehlgeschlagen: ${e.message}")
            } finally {
                setBusy(false); renderDashboard()
            }
        }
    }

    private fun hcClient(): HealthConnectClient? {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> return HealthConnectClient.getOrCreate(this)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                snack("Health Connect muss installiert/aktualisiert werden")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")).setPackage("com.android.vending"))
            }
            else -> snack("Health Connect ist auf diesem Gerät nicht verfügbar")
        }
        return null
    }

    private fun runExport(kind: String, block: suspend (File, (String) -> Unit) -> ExportResult) {
        val dir = File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "cmf_${kind}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))}.zip")
        setBusy(true)
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { block(out) { msg -> runOnUiThread { say(msg) } } }
                last = res
                settings.dashboard = settings.dashboard.merge(res.dash)
                say(res.summary)
                val counts = res.summary.lines().mapNotNull { Regex("^  (\\S+)\\s+(\\d+)$").find(it)?.destructured }
                    .filter { it.component2() != "0" }.joinToString("\n") { (k, n) -> "$k: $n" }
                v<TextView>(R.id.resultText).text = "${res.zip.name} · ${res.zip.length() / 1024} KB\n$counts"
                v<MaterialCardView>(R.id.resultCard).visibility = View.VISIBLE
                v<View>(R.id.uploadButton).isEnabled = settings.configured
                renderDashboard()
            } catch (e: Exception) {
                say("Fehler: ${e.javaClass.simpleName}: ${e.message}")
                snack("Fehler: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun background(what: String, block: suspend () -> String) {
        setBusy(true)
        lifecycleScope.launch {
            try { val r = withContext(Dispatchers.IO) { block() }; say(r); snack(r) }
            catch (e: Exception) { val m = "$what: ${e.message ?: e.javaClass.simpleName}"; say(m); snack(m) }
            finally { setBusy(false) }
        }
    }

    private fun showReport() {
        val html = last?.reportHtml ?: return
        val web = WebView(this).apply { loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
        MaterialAlertDialogBuilder(this).setView(web).setPositiveButton("Schließen", null).show()
    }

    private fun share() {
        val zip = last?.zip ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "ZIP teilen"))
    }

    private fun showPrivacy() = MaterialAlertDialogBuilder(this)
        .setTitle("Datenschutz")
        .setMessage("CMF Export liest deine Gesundheitsdaten nur – es löscht und verändert nichts, weder auf der Uhr noch in Health Connect. Daten werden nur an den Server gesendet, den du selbst unter „Home Assistant“ einträgst.")
        .setPositiveButton("OK", null).show()

    private fun setBusy(b: Boolean) {
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
        busy.forEach { it.isEnabled = !b }
    }

    private fun say(s: String) { logText.appendLine(s); log.text = logText }
    private fun snack(s: String) = Snackbar.make(v(R.id.root), s, Snackbar.LENGTH_LONG).show()
}
