package io.github.witzig0hast.cmfexport

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private lateinit var log: TextView
    private lateinit var progress: ProgressBar
    private lateinit var daysInput: EditText
    private lateinit var onlyCmf: CheckBox
    private lateinit var resultButtons: LinearLayout
    private val busyButtons = mutableListOf<Button>()
    private var last: ExportResult? = null

    private val hcPermissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val missing = HealthConnectExporter.PERMISSIONS - granted
        say("Health Connect: ${granted.size} Berechtigungen erteilt" + if (missing.isNotEmpty()) ", ${missing.size} fehlen (werden übersprungen)" else "")
    }

    private val pickDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::runGadgetbridge) }

    private val saveZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val zip = last?.zip ?: return@registerForActivityResult
        if (uri != null) lifecycleScope.launch {
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.use { o -> zip.inputStream().use { it.copyTo(o) } } }
            toast("Gespeichert")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (intent?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" || intent?.action == "android.intent.action.VIEW_PERMISSION_USAGE") {
            showPrivacy()
        }
    }

    private fun buildUi(): View {
        val pad = dp(16)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun heading(t: String) = col.addView(TextView(this).apply { text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(4)) })
        fun note(t: String) = col.addView(TextView(this).apply { text = t; alpha = 0.8f })
        fun button(t: String, parent: LinearLayout = col, busy: Boolean = true, onClick: () -> Unit) =
            Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }.also { parent.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); if (busy) busyButtons += it }

        col.addView(TextView(this).apply { text = "CMF Watch Export"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        note("Holt Schlaf, Puls, SpO2, Stress, Schritte, Kalorien und Trainings deiner CMF Watch Pro 3 als CSV/GPX/HTML in eine ZIP-Datei.")

        heading("A · Health Connect (offizielle CMF-App)")
        note("Vorher in der CMF-Watch-App: Profil → Health Connect → alle Datentypen freigeben und synchronisieren.")
        button("1. Health Connect Zugriff erlauben", busy = false) { requestHc() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = "Zeitraum (Tage): " })
        daysInput = EditText(this).apply { setText("3650"); inputType = InputType.TYPE_CLASS_NUMBER; minEms = 4 }
        row.addView(daysInput)
        col.addView(row)
        onlyCmf = CheckBox(this).apply { text = "Nur Daten der CMF-App"; isChecked = true }
        col.addView(onlyCmf)
        button("2. Aus Health Connect exportieren") { runHealthConnect() }

        heading("B · Gadgetbridge-Datenbank")
        note("Gadgetbridge → Datenverwaltung → Datenbank exportieren, dann hier die Datei (Gadgetbridge oder .zip) wählen. Liefert alle Rohdaten inkl. Stress und GPS.")
        button("Gadgetbridge-Datei auswählen …") { pickDb.launch(arrayOf("*/*")) }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
        col.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        resultButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        heading("Ergebnis")
        col.addView(resultButtons)
        button("Bericht anzeigen", resultButtons, busy = false) { showReport() }
        button("ZIP speichern …", resultButtons, busy = false) { last?.let { saveZip.launch(it.zip.name) } }
        button("ZIP teilen …", resultButtons, busy = false) { share() }

        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); setPadding(0, dp(8), 0, 0) }
        col.addView(log)
        return ScrollView(this).apply { addView(col); fitsSystemWindows = true }
    }

    private fun hcClient(): HealthConnectClient? {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> return HealthConnectClient.getOrCreate(this)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                say("Health Connect muss installiert/aktualisiert werden – öffne Play Store …")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")).setPackage("com.android.vending"))
            }
            else -> say("Health Connect ist auf diesem Gerät nicht verfügbar.")
        }
        return null
    }

    private fun requestHc() {
        hcClient() ?: return
        hcPermissions.launch(HealthConnectExporter.PERMISSIONS)
    }

    private fun runHealthConnect() {
        val client = hcClient() ?: return
        val days = daysInput.text.toString().toLongOrNull()?.coerceIn(1, 36500) ?: 3650
        val cmf = onlyCmf.isChecked
        runExport("healthconnect") { out, p -> HealthConnectExporter(client).export(days, cmf, out, p) }
    }

    private fun runGadgetbridge(uri: Uri) = runExport("gadgetbridge") { out, p -> GadgetbridgeExporter(this).export(uri, out, p) }

    private fun runExport(kind: String, block: suspend (File, (String) -> Unit) -> ExportResult) {
        val dir = File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "cmf_${kind}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))}.zip")
        setBusy(true)
        log.text = ""
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { block(out) { msg -> runOnUiThread { say(msg) } } }
                last = res
                log.text = res.summary + "\nZIP: ${res.zip.name} (${res.zip.length() / 1024} KB)"
                resultButtons.visibility = View.VISIBLE
            } catch (e: Exception) {
                say("Fehler: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun showReport() {
        val html = last?.reportHtml ?: return
        val web = WebView(this).apply { loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
        AlertDialog.Builder(this).setView(web).setPositiveButton("Schließen", null).show()
    }

    private fun share() {
        val zip = last?.zip ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "ZIP teilen"))
    }

    private fun showPrivacy() = AlertDialog.Builder(this)
        .setTitle("Datenschutz")
        .setMessage("CMF Export liest deine Gesundheitsdaten nur lokal auf dem Gerät, um sie als Datei zu exportieren. Es werden keine Daten ins Internet gesendet; die App hat keine Internet-Berechtigung.")
        .setPositiveButton("OK", null).show()

    private fun setBusy(b: Boolean) {
        progress.visibility = if (b) View.VISIBLE else View.GONE
        busyButtons.forEach { it.isEnabled = !b }
    }

    private fun say(s: String) { log.append(s + "\n") }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
