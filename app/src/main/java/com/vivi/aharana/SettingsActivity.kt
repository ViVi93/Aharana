package com.vivi.aharana

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

class SettingsActivity : BaseActivity() {
    private val REQUEST_OUTPUT_DIR = 1001
    private val REQUEST_PERSONAL_INI = 1002
    private val delivery = Executor { command ->
        runOnUiThread { if (!isFinishing && !isDestroyed) command.run() }
    }
    // Independent lanes: Python can stall without blocking preview or bundle export.
    private val configRequests = SettingsRequests(delivery)
    private val previewRequests = SettingsRequests(delivery)
    private val dnsRequests = SettingsRequests(delivery)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }
        applyTopInset(findViewById<android.view.View>(R.id.appBar))
        applyBottomInset(findViewById<android.view.View>(R.id.rootScroll), includeIme = true)

        val output = findViewById<EditText>(R.id.inputOutputDir)
        val prefs = getSharedPreferences("fanficfare_prefs", MODE_PRIVATE)
        output.setText(prefs.getString("output_dir", ""))
        val organiseDownloads = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchOrganiseDownloads)
        organiseDownloads.isChecked = prefs.getBoolean(KEY_ORGANISE_DOWNLOADS, false)

        setupThemeSelector(prefs)
        setupDynamicColorSwitch(prefs)

        findViewById<Button>(R.id.buttonPickFolder).setOnClickListener {
            val intent = Intent("android.provider.action.OPEN_DOCUMENT_TREE")
            startActivityForResult(intent, REQUEST_OUTPUT_DIR)
        }

        findViewById<Button>(R.id.buttonSave).setOnClickListener {
            val dir = output.text.toString().trim()
            if (organiseDownloads.isChecked && dir.startsWith("content://")) {
                showMessage("Author folders need a filesystem output path. Enter a folder path or turn off Organise future downloads.")
                return@setOnClickListener
            }
            prefs.edit().putString("output_dir", dir)
                .putBoolean(KEY_ORGANISE_DOWNLOADS, organiseDownloads.isChecked).apply()
            showMessage("Saved")
            finish()
        }

        val btnAllFiles = findViewById<Button>(R.id.buttonAllFilesAccess)
        if (btnAllFiles != null) {
            btnAllFiles.setOnClickListener {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (e2: Exception) {
                        showMessage("Cannot open settings: ${e2.message}")
                    }
                }
            }
        }

        findViewById<Button>(R.id.buttonImportConfig).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/x-ini", "application/octet-stream"))
            }
            startActivityForResult(intent, REQUEST_PERSONAL_INI)
        }

        findViewById<Button>(R.id.buttonRemoveConfig).setOnClickListener {
            removePersonalIni()
        }

        findViewById<Button>(R.id.buttonRefreshPythonDebug).setOnClickListener {
            refreshPythonDebugLog()
        }
        findViewById<Button>(R.id.buttonClearPythonDebug).setOnClickListener {
            clearPythonDebugLog()
        }
        findViewById<Button>(R.id.buttonShareSettingsDiagnostics).setOnClickListener {
            shareDiagnosticBundle()
        }
        findViewById<Button>(R.id.buttonRunDnsDiagnostics).setOnClickListener {
            runDnsDiagnostics()
        }

        refreshPythonDebugLog()
        updateConfigStatus()
    }

    override fun onDestroy() {
        configRequests.close()
        previewRequests.close()
        dnsRequests.close()
        super.onDestroy()
    }

    private fun setupThemeSelector(prefs: android.content.SharedPreferences) {
        val themeInput = findViewById<EditText>(R.id.inputTheme)

        fun labelFor(mode: String): String = when (mode) {
            BaseActivity.THEME_LIGHT -> "Light"
            BaseActivity.THEME_DARK -> "Dark"
            BaseActivity.THEME_AMOLED -> "AMOLED (black)"
            else -> "System"
        }

        // The field is refreshed from the preference every time, rather than from a value
        // captured when the screen was created: the old version read it once and never
        // updated the row, so it showed a stale theme after a selection.
        fun refreshLabel() {
            val mode = prefs.getString(BaseActivity.KEY_THEME_MODE, BaseActivity.THEME_SYSTEM)
                ?: BaseActivity.THEME_SYSTEM
            themeInput.setText(labelFor(mode))
        }
        refreshLabel()

        themeInput.setOnClickListener {
            val current = prefs.getString(BaseActivity.KEY_THEME_MODE, BaseActivity.THEME_SYSTEM)
                ?: BaseActivity.THEME_SYSTEM
            val titles = arrayOf("System", "Light", "Dark", "AMOLED (black)")
            val checked = when (current) {
                BaseActivity.THEME_LIGHT -> 1
                BaseActivity.THEME_DARK -> 2
                BaseActivity.THEME_AMOLED -> 3
                else -> 0
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(titles, checked) { dialog, which ->
                    val mode = when (which) {
                        1 -> BaseActivity.THEME_LIGHT
                        2 -> BaseActivity.THEME_DARK
                        3 -> BaseActivity.THEME_AMOLED
                        else -> BaseActivity.THEME_SYSTEM
                    }
                    prefs.edit().putString(BaseActivity.KEY_THEME_MODE, mode).apply()
                    applyThemeMode(mode)
                    refreshLabel()
                    dialog.dismiss()
                    // Recreate so the change is visible now instead of on the next launch.
                    recreate()
                }
                .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    /** Applies a theme mode to the whole app for this process. */
    private fun applyThemeMode(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                BaseActivity.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                BaseActivity.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                // AMOLED is a dark theme by definition; without this it would be unusable
                // while the system is in light mode.
                BaseActivity.THEME_AMOLED -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /**
     * Material You toggle. Unavailable below Android 12, where the switch is disabled with an
     * explanatory summary rather than silently doing nothing.
     */
    private fun setupDynamicColorSwitch(prefs: android.content.SharedPreferences) {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchDynamicColor)
        val hint = findViewById<TextView>(R.id.textDynamicColorHint)
        val available = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

        // Show as off when the device can't apply it, so the switch never claims an effect
        // that is not happening.
        switch.isChecked = available && prefs.getBoolean(BaseActivity.KEY_DYNAMIC_COLOR, true)
        switch.isEnabled = available
        if (!available) {
            hint.setText("Needs Android 12 or newer — using the Aharana palette")
        }

        switch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BaseActivity.KEY_DYNAMIC_COLOR, checked).apply()
            recreate()
        }
    }



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OUTPUT_DIR && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                findViewById<EditText>(R.id.inputOutputDir).setText(uri.toString())
            }
        } else if (requestCode == REQUEST_PERSONAL_INI && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importPersonalIni(uri)
        }
    }

    private fun importPersonalIni(uri: android.net.Uri) {
        runConfigWork("personal.ini imported") {
            val dest = File(getConfigDir(applicationContext), "personal.ini")
            val staged = File.createTempFile("personal-import-", ".ini", dest.parentFile)
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Cannot open selected configuration")
                input.use {
                    staged.outputStream().use { output -> it.copyTo(output) }
                }
                check(staged.renameTo(dest)) { "Cannot store selected configuration" }
            } finally {
                staged.delete()
            }
        }
    }

    private fun removePersonalIni() {
        runConfigWork("personal.ini removed") {
            val file = File(getConfigDir(applicationContext), "personal.ini")
            check(!file.exists() || file.delete()) { "Cannot remove configuration" }
        }
    }

    private fun engineLogFiles(): List<File> {
        val directory = File(getConfigDir(applicationContext), "diagnostics")
        return (listOf("engine.jsonl") + (1..3).map { "engine.jsonl.$it" })
            .map { File(directory, it) }
    }

    private fun refreshPythonDebugLog() {
        val textView = findViewById<TextView>(R.id.textPythonDebugLog)
        textView.text = "Loading latest debug entries…"
        previewRequests.submit({ SettingsDebugPreview.read(engineLogFiles()) }) { result ->
            textView.text = result.fold(
                onSuccess = { it.text.ifBlank { "No log yet" } },
                onFailure = { "Debug unavailable: ${it.javaClass.simpleName}. Try Refresh or Share full diagnostics." }
            )
        }
    }

    private fun clearPythonDebugLog() {
        val textView = findViewById<TextView>(R.id.textPythonDebugLog)
        textView.text = "Clearing debug log…"
        previewRequests.submit({
            val result = JSONObject(settingsBridge().clearDownloadDebug())
            check(result.optBoolean("ok")) { "Debug clear failed" }
            SettingsDebugPreview.read(engineLogFiles())
        }) { result ->
            result.fold(onSuccess = {
                textView.text = it.text.ifBlank { "No log yet" }
                showMessage("Debug log cleared")
            }, onFailure = {
                textView.text = "Debug unavailable: clear failed (${it.javaClass.simpleName}). Logs may still be retained."
            })
        }
    }

    /** Only called inside SettingsRequests workers, never while binding views. */
    private fun settingsBridge(): PythonBridge {
        synchronized(Python::class.java) {
            if (!Python.isStarted()) {
                Python.start(com.chaquo.python.android.AndroidPlatform(applicationContext))
            }
        }
        val bridge = PythonBridge(applicationContext)
        check(bridge.getInitError() == null) { "Python bridge initialization failed" }
        return bridge
    }

    private data class ConfigDisplay(val text: String, val exists: Boolean)

    private fun updateConfigStatus() {
        runConfigWork()
    }

    private fun runConfigWork(successMessage: String? = null, mutation: () -> Unit = {}) {
        val statusText = findViewById<TextView>(R.id.textConfigStatus)
        val buttonRemove = findViewById<Button>(R.id.buttonRemoveConfig)
        statusText.text = "Loading configuration…"
        buttonRemove.isEnabled = false
        configRequests.submit({
            mutation()
            // A failed Python status lookup must not prevent removing a broken INI.
            val exists = File(getConfigDir(applicationContext), "personal.ini").isFile
            try {
                loadConfigStatus()
            } catch (e: Exception) {
                ConfigDisplay("Configuration unavailable: ${e.javaClass.simpleName}. You can import or remove personal.ini and retry.", exists)
            }
        }) { result ->
            result.fold(onSuccess = {
                statusText.text = it.text
                buttonRemove.isEnabled = it.exists
                if (successMessage != null) showMessage(successMessage)
            }, onFailure = {
                statusText.text = "Configuration unavailable: operation failed (${it.javaClass.simpleName}). Retry import or reopen Settings."
                buttonRemove.isEnabled = true
                showMessage("Configuration operation failed: ${it.javaClass.simpleName}")
            })
        }
    }

    private fun loadConfigStatus(): ConfigDisplay {
        val status = settingsBridge().getConfigStatus()
        check(!status.has("error") && status.optBoolean("initialized")) { "Configuration status failed" }

        val initialized = status.optBoolean("initialized")
        val configDir = status.optString("config_dir", "")
        val personalPath = status.optString("personal_ini_path", "")
        val exists = status.optBoolean("exists")
        val isFile = status.optBoolean("is_file")
        val parseError = if (status.has("parse_error")) status.optString("parse_error", "") else ""
        val personalExists = status.optBoolean("imported")
        val resolvedSize = status.optInt("size", 0)

        val sb = StringBuilder()
        sb.append("Initialized: ").append(initialized).append('\n')
        sb.append("Config directory: ").append(if (configDir.isBlank()) "<NULL>" else configDir).append('\n')
        sb.append("Python personal.ini path: ").append(if (personalPath.isBlank()) "<NULL>" else personalPath).append('\n')
        sb.append("Exists: ").append(exists).append('\n')
        sb.append("Is file: ").append(isFile).append('\n')
        sb.append("Resolved size: ").append(resolvedSize).append(" bytes\n")
        sb.append("Imported: ").append(personalExists).append('\n')
        if (!parseError.isNullOrBlank()) {
            sb.append("Parse error: ").append(parseError).append('\n')
        }
        sb.append("Credentials present: ").append(status.optBoolean("credentials_present")).append('\n')
        sb.append("Configuration valid: ").append(status.optBoolean("configuration_valid")).append('\n')
        val importError = status.optString("import_error", "")
        if (importError.isNotBlank()) {
            sb.append("Import error: ").append(importError).append('\n')
        }
        sb.append("FanFicFare version: ").append(if (status.has("fanficfare_version")) status.optString("fanficfare_version", "<NULL>") else "<NULL>").append('\n')
        return ConfigDisplay(sb.toString().trimEnd(), exists)
    }

    private fun runDnsDiagnostics() {
        val textView = findViewById<TextView>(R.id.textDnsDiagnostics)
        textView.text = "Running…"
        dnsRequests.submit({
            val started = System.currentTimeMillis()
            try {
                DiagnosticLog.append(applicationContext, "Settings.DnsDiagnostics", "START")
                val result = JSONObject(settingsBridge().runDnsDiagnostics())
                if (!result.optBoolean("ok")) {
                    "DNS diagnostics failed: " + DiagnosticFiles.sanitize(result.optString("error", "Unknown error")).take(1024)
                } else {
                    val display = StringBuilder()
                    for ((label, key) in listOf("Target" to "target", "Comparison" to "comparison")) {
                        val group = result.optJSONObject(key)
                        val attempts = group?.optJSONArray("repeated_5") ?: org.json.JSONArray()
                        var success = 0
                        for (i in 0 until attempts.length()) {
                            if (attempts.optJSONObject(i)?.optBoolean("success") == true) success++
                        }
                        display.append("$label: ${group?.optString("host") ?: "<null>"}\n")
                        display.append("Repeated 5 attempts:\n  success=$success fail=${attempts.length() - success}\n")
                        if (key == "target") {
                            val last = attempts.optJSONObject(attempts.length() - 1)
                            if (last != null && !last.optBoolean("success")) {
                                display.append("Last failure:\n  exception=${last.optString("exception")}\n")
                                display.append("  errno=${last.optInt("errno")}\n  msg=${last.optString("msg")}\n")
                            }
                        }
                    }
                    DiagnosticFiles.sanitize(display.toString()).take(8192)
                }
            } catch (e: Exception) {
                DiagnosticLog.append(applicationContext, "Settings.DnsDiagnostics", "FAILED type=${e.javaClass.simpleName}")
                throw e
            } finally {
                DiagnosticLog.append(applicationContext, "Settings.DnsDiagnostics", "END elapsedMs=${System.currentTimeMillis() - started}")
            }
        }) { result ->
            textView.text = result.getOrElse { "DNS diagnostics unavailable: ${it.javaClass.simpleName}" }
        }
    }

    private fun shareDiagnosticBundle() {
        val button = findViewById<Button>(R.id.buttonShareSettingsDiagnostics)
        val status = findViewById<TextView>(R.id.textSettingsExportStatus)
        button.isEnabled = false
        status.text = "Preparing retained diagnostics…"
        // DiagnosticBundle switches to IO itself; no Python, GIL or engine gate involved.
        lifecycleScope.launch {
            try {
                val file = DiagnosticBundle.create(applicationContext)
                if (isFinishing || isDestroyed) return@launch
                val uri = FileProvider.getUriForFile(this@SettingsActivity, "${packageName}.provider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share diagnostic bundle"))
                status.text = "Bundle ready: retained app/engine logs and queue state (not just this preview)."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    status.text = "Could not share diagnostics: ${e.javaClass.simpleName}. Try again."
                }
            } finally {
                if (!isFinishing && !isDestroyed) button.isEnabled = true
            }
        }
    }

    companion object {
        const val KEY_ORGANISE_DOWNLOADS = "organise_downloads"

        fun getOrganiseDownloads(context: Context): Boolean =
            context.getSharedPreferences("fanficfare_prefs", Context.MODE_PRIVATE)
                .getBoolean(KEY_ORGANISE_DOWNLOADS, false)

        fun getOutputDir(context: Context): String {
            val prefs = context.getSharedPreferences("fanficfare_prefs", Context.MODE_PRIVATE)
            val configured = prefs.getString("output_dir", null)
            if (!configured.isNullOrBlank()) return configured

            val publicDownloads = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val fallback = java.io.File(publicDownloads, "FanFicFare").absolutePath
            return fallback
        }

        fun getOutputDocumentFile(context: Context): DocumentFile? {
            val prefs = context.getSharedPreferences("fanficfare_prefs", Context.MODE_PRIVATE)
            val uriString = prefs.getString("output_dir", null)
            if (uriString.isNullOrBlank()) return null
            return DocumentFile.fromTreeUri(context, Uri.parse(uriString))
        }

        fun getConfigDir(context: Context): File {
            val dir = File(context.filesDir, "fanficfare")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }
}
