package com.vivi.aharana

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.vivi.aharana.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Manual, read-only export. Never take the storage/engine gates or enter Python. */
object DiagnosticBundle {
    private fun engineFiles(context: Context): List<File> {
        val directory = File(SettingsActivity.getConfigDir(context), "diagnostics")
        return (listOf("engine.jsonl") + (1..3).map { "engine.jsonl.$it" }).map { File(directory, it) }
    }

    suspend fun create(context: Context): File = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val startedMono = SystemClock.elapsedRealtime()
        val scratch = File(context.cacheDir, "diagnostic-snapshot-${UUID.randomUUID()}")
        check(scratch.mkdirs()) { "Cannot create diagnostic snapshot directory" }
        try {
            val logs = DiagnosticLog.copyForExport(context, scratch).toMutableList()
            val warnings = JSONArray()
            for (source in engineFiles(context)) {
                try {
                    if (source.isFile) {
                        val copy = File(scratch, source.name)
                        copy.writeText(DiagnosticFiles.safeTail(source))
                        logs.add(copy)
                    }
                } catch (e: Exception) {
                    // An active engine can rotate between the existence check and open.
                    warnings.put("${source.name}: snapshot unavailable (${e.javaClass.simpleName})")
                }
            }
            val allRows = AppDatabase.getInstance(context).downloadJobDao().getAll()
            val rows = allRows.sortedByDescending { it.createdAt }.take(5000)
            val queue = JSONArray()
            for (job in rows) {
                queue.put(JSONObject().put("job_id", job.id).put("book_id", job.bookId)
                    .put("type", job.type).put("status", job.status)
                    .put("work_name", job.workId ?: JSONObject.NULL)
                    .put("url", job.inputUrl?.let(DiagnosticFiles::sanitize) ?: JSONObject.NULL)
                    .put("error", job.error?.let(DiagnosticFiles::sanitize)?.take(1024) ?: JSONObject.NULL)
                    .put("created_at_ms", job.createdAt).put("finished_at_ms", job.finishedAt ?: JSONObject.NULL)
                    .put("has_output_path", !job.outputPath.isNullOrBlank()))
            }
            val work = JSONArray()
            val workNames = rows.mapNotNull { it.workId?.takeIf(String::isNotBlank) }.distinct()
            val manager = WorkManager.getInstance(context)
            for (names in workNames.chunked(200)) {
                // A diagnostic query deadline must never become a download deadline.
                if (SystemClock.elapsedRealtime() - startedMono > 10_000L) {
                    warnings.put("WorkManager snapshot incomplete: export query budget exhausted")
                    break
                }
                try {
                    val query = WorkQuery.Builder.fromUniqueWorkNames(names).build()
                    for (info in manager.getWorkInfos(query).get(2, TimeUnit.SECONDS)) {
                        work.put(JSONObject().put("request_id", info.id.toString())
                            .put("state", info.state.name).put("run_attempt", info.runAttemptCount)
                            .put("stop_reason", if (Build.VERSION.SDK_INT >= 31) info.stopReason else JSONObject.NULL)
                            .put("stop_reason_supported", Build.VERSION.SDK_INT >= 31)
                            .put("tags", JSONArray(info.tags.toList()))
                            .put("phase", info.progress.getString(FanFicFareWorker.PROGRESS_PHASE) ?: JSONObject.NULL))
                    }
                } catch (e: Exception) {
                    warnings.put("WorkManager snapshot unavailable (${e.javaClass.simpleName})")
                }
            }
            val app = context.packageManager.getPackageInfo(context.packageName, 0)
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val environment = JSONObject().put("schema_version", 1).put("package", context.packageName)
                .put("app_version", app.versionName).put("version_code", app.versionCode)
                .put("installed_at_ms", app.lastUpdateTime).put("android_api", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("export_started_at_ms", startedAt).put("export_finished_at_ms", System.currentTimeMillis())
                .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                .put("interactive_now", power?.isInteractive ?: JSONObject.NULL)
                .put("device_idle_now", power?.isDeviceIdleMode ?: JSONObject.NULL)
                .put("ignoring_battery_optimizations_now", power?.isIgnoringBatteryOptimizations(context.packageName) ?: JSONObject.NULL)
                .put("queue_total", allRows.size).put("queue_included", rows.size)
                .put("warnings", warnings)
            val queueSnapshot = JSONObject().put("rows", queue).put("workmanager", work)
                .put("queried_work_names", JSONArray(workNames)).put("total_rows", allRows.size)
            val destination = File(context.cacheDir, "aharana-diagnostics-${UUID.randomUUID()}.zip")
            DiagnosticFiles.writeBundle(destination, linkedMapOf(
                "README.txt" to "Aharana diagnostic snapshot\nContains bounded, redacted app/engine logs and queue/WorkManager state.\nNo configuration files, request headers or response bodies are included.\nSnapshots are best effort, not a single transaction; environment power fields describe export time, not the earlier interruption.\nA missing/unknown stop reason must not be interpreted as user cancellation.\n",
                "environment.json" to environment.toString(2),
                "queue.json" to queueSnapshot.toString(2)
            ), logs)
            destination
        } finally { scratch.deleteRecursively() }
    }
}
