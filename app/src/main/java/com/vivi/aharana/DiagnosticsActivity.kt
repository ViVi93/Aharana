package com.vivi.aharana

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DiagnosticsActivity : BaseActivity() {

    private lateinit var statusText: TextView
    private lateinit var importText: TextView
    private lateinit var logText: TextView
    private lateinit var appVersionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }
        applyTopInset(findViewById<android.view.View>(R.id.appBar))
        applyBottomInset(findViewById<android.view.View>(R.id.root))

        statusText = findViewById(R.id.textDiagnosticStatus)
        importText = findViewById(R.id.textImportDiagnostics)
        logText = findViewById(R.id.textDiagnosticLog)
        appVersionText = findViewById(R.id.textAppVersion)

        updateStatus()
        showLog()

        findViewById<android.widget.Button>(R.id.buttonRefreshLog).setOnClickListener {
            showLog()
            updateStatus()
        }
        findViewById<android.widget.Button>(R.id.buttonClearLog).setOnClickListener {
            DiagnosticLog.clear(this)
            logText.text = "Log cleared"
            importText.text = ""
            importText.visibility = android.view.View.GONE
            updateStatus()
        }
        findViewById<android.widget.Button>(R.id.buttonShareLog).setOnClickListener {
            val button = it
            button.isEnabled = false
            lifecycleScope.launch {
                try {
                    val file = withContext(Dispatchers.IO) { DiagnosticBundle.create(applicationContext) }
                    val uri: Uri = FileProvider.getUriForFile(this@DiagnosticsActivity, "${packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share diagnostic bundle"))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    DiagnosticLog.appendException(applicationContext, "Diagnostics.Export", "export_failed", e)
                    showMessage("Could not export diagnostics: ${e.javaClass.simpleName}")
                } finally { button.isEnabled = true }
            }
        }
        findViewById<android.widget.Button>(R.id.buttonRunImportDiagnostics).setOnClickListener {
            runImportDiagnostics()
        }
    }

    private fun updateStatus() {
        // The export must remain usable even if a synchronous Python call holds the GIL.
        statusText.text = "Python: ${if (Python.isStarted()) "started" else "missing"}\n" +
            "Module: use Run import diagnostics to check\n" +
            "Log file: ${DiagnosticLog.getFile(this).absolutePath}"
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            appVersionText.text = "App version: ${info.versionName ?: "?"} (${info.versionCode})"
        } catch (e: Exception) {
            appVersionText.text = "App version: unavailable"
        }
    }

    private fun showLog() {
        // Run on background thread to avoid ANR on very large log files
        Thread {
            val result = DiagnosticLog.getTail(this, 30)
            runOnUiThread {
                if (result.lines.isBlank()) {
                    logText.text = "No log entries"
                } else {
                    if (result.totalCount > 30) {
                        logText.text = result.lines + "\n\n(last 30 of ${result.totalCount} current log lines — Share includes retained app/engine logs and queue state)"
                    } else {
                        logText.text = result.lines
                    }
                }
            }
        }.start()
    }

    private fun runImportDiagnostics() {
        val python = if (Python.isStarted()) Python.getInstance() else null
        val bridge = if (python != null) PythonBridge(applicationContext) else null
        if (bridge == null || bridge.getInitError() != null) {
            val err = bridge?.getInitError() ?: "null_bridge"
            importText.text = "Diagnostics unavailable: bridge not available\nInit error: ${err}"
            importText.visibility = android.view.View.VISIBLE
            return
        }
        importText.text = "Running import diagnostics..."
        importText.visibility = android.view.View.VISIBLE
        Thread {
            val resultJson = try {
                bridge.diagnoseFanFicFareImports()
            } catch (e: Exception) {
                DiagnosticLog.appendException(this, "Diagnostics.Import", "diagnostic_exception", e)
                "{\"ok\":false,\"error\":\"${e.javaClass.simpleName}: ${e.message ?: ""}\"}"
            }
            runOnUiThread {
                val sb = StringBuilder()
                sb.append("FanFicFare import diagnostics:\n")
                try {
                    val result = JSONObject(resultJson)
                    val arr = result.optJSONArray("results")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val r = arr.getJSONObject(i)
                            val test = r.optString("test")
                            sb.append(if (r.optBoolean("ok")) "[OK] " else "[FAIL] ")
                            sb.append(test)
                            if (!r.optBoolean("ok")) {
                                sb.append("\n  ")
                                sb.append(r.optString("error_type", "Error"))
                                sb.append(": ")
                                sb.append(r.optString("error", ""))
                                val tb = r.optString("traceback")
                                if (!tb.isNullOrBlank()) {
                                    sb.append("\n")
                                    sb.append(tb)
                                }
                            }
                            sb.append("\n")
                        }
                    }
                } catch (e: Exception) {
                    sb.append("\nFailed to parse diagnostic result: ")
                    sb.append(e.javaClass.simpleName)
                    sb.append(": ")
                    sb.append(e.message ?: "")
                }
                val text = sb.toString().trim()
                importText.text = text
                importText.visibility = android.view.View.VISIBLE
                DiagnosticLog.append(this, "Diagnostics.Import", text.replace("\n", " "))
            }
        }.start()
    }
}
