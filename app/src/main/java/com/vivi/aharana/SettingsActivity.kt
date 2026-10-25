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
import java.io.File

class SettingsActivity : BaseActivity() {
    private val REQUEST_OUTPUT_DIR = 1001
    private val REQUEST_PERSONAL_INI = 1002

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
            val bridge = try { PythonBridge(this) } catch (e: Exception) { null }
            try { bridge?.clearDownloadDebug() } catch (e: Exception) { /* ignore */ }
            refreshPythonDebugLog()
            showMessage("Debug log cleared")
        }

        findViewById<Button>(R.id.buttonRunDnsDiagnostics).setOnClickListener {
            val bridge = try { PythonBridge(this) } catch (e: Exception) { null }
            val textView = findViewById<TextView>(R.id.textDnsDiagnostics)
            textView.text = "Running..."
            DiagnosticLog.append(this, "Settings.DnsDiagnostics", "button_clicked")
            Thread {
                var display = "DNS diagnostics unavailable"
                val startedMs = System.currentTimeMillis()
                try {
                    DiagnosticLog.append(this, "Settings.DnsDiagnostics", "thread_start")
                    val raw = try { bridge?.runDnsDiagnostics() } catch (e: Exception) { null }
                    DiagnosticLog.append(this, "Settings.DnsDiagnostics", "bridge_returned raw=${raw != null}")
                    if (raw == null) {
                        display = "DNS diagnostics failed: bridge returned null"
                        DiagnosticLog.append(this, "Settings.DnsDiagnostics", "result=null")
                    } else {
                        val obj = org.json.JSONObject(raw)
                        if (obj.optBoolean("ok")) {
                            val sb = StringBuilder()
                            val target = obj.optJSONObject("target")
                            val comparison = obj.optJSONObject("comparison")
                            sb.append("Target: ").append(target?.optString("host") ?: "<null>").append('\n')
                            sb.append("Repeated 5 attempts:\n")
                            val targetResults = target?.optJSONArray("repeated_5") ?: org.json.JSONArray()
                            var targetSuccess = 0
                            var targetFail = 0
                            for (i in 0 until targetResults.length()) {
                                val r = targetResults.getJSONObject(i)
                                if (r.optBoolean("success")) targetSuccess++ else targetFail++
                            }
                            sb.append("  success=").append(targetSuccess).append(" fail=").append(targetFail).append('\n')
                            sb.append("Comparison: ").append(comparison?.optString("host") ?: "<null>").append('\n')
                            sb.append("Repeated 5 attempts:\n")
                            val compResults = comparison?.optJSONArray("repeated_5") ?: org.json.JSONArray()
                            var compSuccess = 0
                            var compFail = 0
                            for (i in 0 until compResults.length()) {
                                val r = compResults.getJSONObject(i)
                                if (r.optBoolean("success")) compSuccess++ else compFail++
                            }
                            sb.append("  success=").append(compSuccess).append(" fail=").append(compFail).append('\n')
                            val firstFail = targetResults.optJSONObject(targetResults.length() - 1)
                            if (firstFail != null && !firstFail.optBoolean("success")) {
                                sb.append("Last failure:\n")
                                sb.append("  exception=").append(firstFail.optString("exception", "<null>")).append('\n')
                                sb.append("  errno=").append(firstFail.optInt("errno")).append('\n')
                                sb.append("  msg=").append(firstFail.optString("msg", "<null>")).append('\n')
                            }
                            display = sb.toString()
                            DiagnosticLog.append(this, "Settings.DnsDiagnostics", "result=ok targetSuccess=${targetSuccess} targetFail=${targetFail}")
                        } else {
                            display = "DNS diagnostics failed: ${obj.optString("error", "<null>")}"
                            DiagnosticLog.append(this, "Settings.DnsDiagnostics", "result=error")
                        }
                    }
                } catch (e: Exception) {
                    display = "DNS diagnostics unavailable: ${e.javaClass.simpleName}: ${e.message}"
                    DiagnosticLog.appendException(this, "Settings.DnsDiagnostics", "ui_exception", e)
                } finally {
                    val elapsedMs = System.currentTimeMillis() - startedMs
                    DiagnosticLog.append(this, "Settings.DnsDiagnostics", "thread_end elapsedMs=${elapsedMs}")
                }
                val finalDisplay = display
                runOnUiThread {
                    textView.text = finalDisplay
                    DiagnosticLog.append(this, "Settings.DnsDiagnostics", "ui_updated")
                }
            }.start()
        }

        refreshPythonDebugLog()
        updateConfigStatus()
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
        try {
            val dest = File(getConfigDir(this), "personal.ini")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (dest.exists()) {
                showMessage("personal.ini imported")
                val bridge = try { PythonBridge(this) } catch (e: Exception) { null }
                bridge?.initialize(getConfigDir(this).absolutePath)
                updateConfigStatus()
            } else {
                showMessage("Import failed")
            }
        } catch (e: Exception) {
            showMessage("Import error: ${e.javaClass.simpleName}")
        }
    }

    private fun removePersonalIni() {
        try {
            val file = File(getConfigDir(this), "personal.ini")
            if (file.exists()) file.delete()
            showMessage("personal.ini removed")
            val bridge = try { PythonBridge(this) } catch (e: Exception) { null }
            bridge?.initialize(getConfigDir(this).absolutePath)
            updateConfigStatus()
        } catch (e: Exception) {
            showMessage("Remove error: ${e.javaClass.simpleName}")
        }
    }

    private fun refreshPythonDebugLog() {
        val textView = findViewById<TextView>(R.id.textPythonDebugLog)
        val bridge = try { PythonBridge(this) } catch (e: Exception) { null }
        val raw = try { bridge?.readDownloadDebug() } catch (e: Exception) { null }
        val log = try {
            val obj = org.json.JSONObject(raw ?: "{}")
            if (obj.optBoolean("ok")) obj.optString("log", "No log yet") else "Debug unavailable"
        } catch (e: Exception) {
            "Debug unavailable"
        }
        textView.text = if (log.isBlank()) "No log yet" else log
    }

    private fun updateConfigStatus() {
        val statusText = findViewById<TextView>(R.id.textConfigStatus)
        val buttonRemove = findViewById<Button>(R.id.buttonRemoveConfig)
        val bridge = try { PythonBridge(this) } catch (e: Exception) { null }
        val status = bridge?.getConfigStatus()
        DiagnosticLog.append(this, "Settings.ConfigStatus", status?.toString() ?: "null")

        val initialized = status?.optBoolean("initialized") == true
        val configDir = status?.optString("config_dir", "") ?: ""
        val personalPath = status?.optString("personal_ini_path", "") ?: ""
        val exists = status?.optBoolean("exists") == true
        val isFile = status?.optBoolean("is_file") == true
        val size = status?.optInt("size", 0) ?: 0
        val modified = status?.optLong("modified", 0) ?: 0L
        val parseError = if (status?.has("parse_error") == true) status.optString("parse_error", "") else ""
        val personalExists = status?.optBoolean("imported") ?: false
        val personalIsFile = status?.optBoolean("is_file") ?: false
        val resolvedSize = status?.optInt("size", 0) ?: 0
        val storiesUsername = if (status?.has("storiesonline_section_username") == true) status.optString("storiesonline_section_username", "") else ""
        val storiesPasswordPresent = status?.optBoolean("storiesonline_section_password_present") ?: false
        val alwaysLogin = if (status?.has("always_login") == true) status.optString("always_login", "") else ""
        val loginAttempted = status?.optBoolean("login_test_attempted") ?: false
        val loginError = if (status?.has("login_test_error") == true) status.optString("login_test_error", "") else ""

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
        sb.append("Credentials present: ").append(status?.optBoolean("credentials_present") ?: false).append('\n')
        sb.append("Configuration valid: ").append(status?.optBoolean("configuration_valid") ?: false).append('\n')
        val importError = status?.optString("import_error", "") ?: ""
        if (importError.isNotBlank()) {
            sb.append("Import error: ").append(importError).append('\n')
        }
        sb.append("FanFicFare version: ").append(if (status?.has("fanficfare_version") == true) status.optString("fanficfare_version", "<NULL>") else "<NULL>").append('\n')
        statusText.text = sb.toString().trimEnd()
        buttonRemove.isEnabled = exists
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
