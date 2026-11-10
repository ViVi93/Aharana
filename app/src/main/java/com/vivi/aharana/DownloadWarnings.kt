package com.vivi.aharana

import org.json.JSONObject

/** Presentation only: partial EPUBs remain saved, successful jobs, never automatic retries. */
data class DownloadWarnings(val chapterErrorCount: Int, val messages: List<String>) {
    val hasWarnings: Boolean get() = chapterErrorCount > 0 || messages.isNotEmpty()

    fun completionMessage(successMessage: String): String = if (hasWarnings) {
        val count = if (chapterErrorCount > 0) {
            " ($chapterErrorCount chapter ${if (chapterErrorCount == 1) "error" else "errors"})"
        } else ""
        "Completed with warnings$count: $successMessage"
    } else successMessage

    companion object {
        fun parse(raw: String?): DownloadWarnings = try {
            parse(JSONObject(raw ?: "{}"))
        } catch (_: org.json.JSONException) {
            DownloadWarnings(0, emptyList())
        }

        fun showInQueue(type: String, status: String, resultJson: String?): Boolean =
            type != "metadata" && status != "superseded" &&
                (status != "success" || parse(resultJson).hasWarnings)

        fun parse(result: JSONObject): DownloadWarnings {
            val warnings = result.optJSONArray("warnings")
            val messages = if (warnings == null) emptyList() else (0 until warnings.length())
                .mapNotNull { (warnings.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }
            return DownloadWarnings(result.optInt("chapter_error_count", 0).coerceAtLeast(0), messages)
        }
    }
}
