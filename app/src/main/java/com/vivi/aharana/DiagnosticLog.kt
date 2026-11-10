package com.vivi.aharana

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object DiagnosticLog {
    private const val FILE_NAME = "fanficfare_debug.log"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val session = UUID.randomUUID().toString().take(8)
    private var appVersion: String? = null

    fun getFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    @Synchronized fun clear(context: Context) {
        DiagnosticFiles.RollingFile(getFile(context)).clear()
    }

    @Synchronized fun append(context: Context, tag: String, message: String) {
        try {
            if (appVersion == null) {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                appVersion = "${info.versionName}/${info.versionCode} installed=${info.lastUpdateTime}"
            }
            val line = String.format(
                Locale.US,
                "[%s] %s: mono=%d pid=%d session=%s thread=%d app=%s %s%n",
                timeFormat.format(Date()),
                tag,
                SystemClock.elapsedRealtime(), Process.myPid(), session,
                Thread.currentThread().id, appVersion,
                DiagnosticFiles.sanitize(message).replace("\n", " ").replace("\r", " ").take(4096)
            )
            DiagnosticFiles.RollingFile(getFile(context)).append(line)
        } catch (e: Exception) {
            // never let diagnostics break the app
        }
    }

    fun appendException(context: Context, tag: String, message: String, e: Throwable) {
        val sb = StringBuilder()
        sb.append(message)
        sb.append(" | exception=").append(e.javaClass.simpleName)
        sb.append(" | msg=").append(e.message ?: "null")
        val stack = e.stackTrace
        if (stack.isNotEmpty()) {
            sb.append(" | at=").append(stack[0].className).append('.').append(stack[0].methodName)
        }
        append(context, tag, sb.toString())
    }

    @Synchronized fun getText(context: Context): String {
        return try { DiagnosticFiles.safeTail(getFile(context)) } catch (e: Exception) { "" }
    }

    /** Stable, redacted copies: rotation cannot rename a file while it is being copied. */
    @Synchronized fun copyForExport(context: Context, directory: File): List<File> {
        directory.mkdirs()
        val file = getFile(context)
        return (listOf(file) + (1..3).map { File(file.path + ".$it") }).filter { it.isFile }.map { source ->
            File(directory, source.name).apply { writeText(DiagnosticFiles.safeTail(source)) }
        }
    }

    /**
     * Result of [getTail]: the tail lines and the total line count.
     */
    data class TailResult(val lines: String, val totalCount: Int)

    /**
     * Read at most [maxLines] from the end of the log file, using a sliding
     * window that never holds more than [maxLines] strings in memory at once.
     *
     * Used by the Diagnostics screen to avoid TextView OOM crashes when the
     * log file is very large.
     *
     * Returns both the tail content and the total line count.
     *
     * The full log remains available via the Share button, which writes the
     * complete file to an external intent.
     */
    @Synchronized fun getTail(context: Context, maxLines: Int = 30): TailResult {
        val file = getFile(context)
        if (!file.exists() || file.length() == 0L) return TailResult("", 0)
        return try {
            val lines = mutableListOf<String>()
            var totalCount = 0
            file.forEachLine { line ->
                totalCount++
                lines.add(DiagnosticFiles.sanitize(line))
                if (lines.size > maxLines) lines.removeAt(0)
            }
            TailResult(lines.joinToString("\n"), totalCount)
        } catch (e: Exception) {
            TailResult("", 0)
        }
    }
}
