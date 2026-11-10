package com.vivi.aharana

import org.json.JSONObject

object DownloadWarningsTest {
    @JvmStatic fun main(args: Array<String>) {
        val result = JSONObject("""{"ok":true,"chapter_error_count":2,"warnings":["Two chapters could not be downloaded"]}""")
        val warnings = DownloadWarnings.parse(result)
        check(warnings.chapterErrorCount == 2)
        check(warnings.messages == listOf("Two chapters could not be downloaded"))
        check(warnings.hasWarnings)
        check(warnings.completionMessage("Updated: Story") == "Completed with warnings (2 chapter errors): Updated: Story")
        // Simulates Room resultJson reload, including the worker's final path augmentation.
        result.put("path", "/library/Story.epub").put("title", "Story")
        val reloaded = DownloadWarnings.parse(result.toString())
        check(reloaded == warnings)
        check(DownloadWarnings.parse("""{"chapter_error_count":1}""").completionMessage("Saved") ==
            "Completed with warnings (1 chapter error): Saved")
        check(DownloadWarnings.parse("""{"warnings":[" Warning ",null,42," "]}""").messages == listOf("Warning"))
        check(DownloadWarnings.parse("""{"warnings":["Warning"]}""").completionMessage("Saved") ==
            "Completed with warnings: Saved")
        for (raw in listOf(null, "", "not json", "{}", """{"chapter_error_count":0,"warnings":[]}""",
            """{"chapter_error_count":-1,"warnings":"invalid"}""", """{"chapter_error_count":null}""")) {
            check(!DownloadWarnings.parse(raw).hasWarnings) { "Unexpected warning for $raw" }
            for (message in listOf("✓ Complete", "Complete", "Updated: Story", "Force downloaded: Story")) {
                check(DownloadWarnings.parse(raw).completionMessage(message) == message)
            }
        }
        check(DownloadWarnings.showInQueue("download", "success", result.toString()))
        check(DownloadWarnings.showInQueue("update", "success", result.toString()))
        check(DownloadWarnings.showInQueue("force_download", "success", result.toString()))
        check(!DownloadWarnings.showInQueue("download", "success", "{}"))
        check(!DownloadWarnings.showInQueue("metadata", "success", result.toString()))
        check(DownloadWarnings.showInQueue("download", "cancelled", result.toString())) {
            "Cancelled downloads must remain visible for Retry/Remove"
        }
        check(!DownloadWarnings.showInQueue("download", "superseded", result.toString()))
        check(!DownloadWarnings.showInQueue("metadata", "cancelled", null))
        for (status in listOf("queued", "waiting", "running", "failed")) {
            check(DownloadWarnings.showInQueue("download", status, null))
        }
        // Run every production download/update/force persistence expression, not a model.
        val fullResponse = JSONObject().put("ok", true).put("skipped", true)
            .put("reason", "already current").put("chapters", 120)
            .put("chapter_added_count", 3).put("chapter_updated_count", 4).put("chapter_written_count", 120)
            .put("chapter_error_count", 2).put("warnings", org.json.JSONArray(warnings.messages))
            .put("cover", "A".repeat(500_000)).put("title_page", "huge HTML")
            .put("metadata", JSONObject().put("cover", "nested image")).put("title", "Story")
        for (raw in WorkerOutcomeHarness.persist(fullResponse)) {
            val saved = JSONObject(raw)
            check(!saved.has("cover") && !saved.has("title_page") && !saved.has("metadata") && !saved.has("title")) {
                "Queue outcome must not retain image/titlepage/full response"
            }
            check(raw.length < 8192) { "Queue outcome is not bounded" }
            check(saved.optBoolean("ok") && saved.optBoolean("skipped"))
            check(saved.optString("reason") == "already current" && saved.optInt("chapters") == 120)
            check(saved.optInt("chapter_added_count") == 3 && saved.optInt("chapter_updated_count") == 4 &&
                saved.optInt("chapter_written_count") == 120) { "Compact history must retain scalar chapter counters" }
            check(DownloadWarnings.parse(raw) == warnings)
        }
        check(fullResponse.getString("cover").length == 500_000) { "Compaction must not mutate book metadata" }
        val legacyFull = JSONObject(DownloadOutcome.compact(fullResponse.toString().reader()))
        for (key in listOf("chapter_added_count", "chapter_updated_count", "chapter_written_count")) {
            check(legacyFull.getInt(key) == fullResponse.getInt(key)) { "Legacy cleanup lost $key" }
        }
        val streamCompact = DownloadOutcome::class.java.methods.firstOrNull {
            it.name == "compact" && it.parameterTypes.toList() == listOf(java.io.Reader::class.java)
        } ?: error("Legacy compaction must stream instead of materializing a full cover row")
        val prefix = """{"ok":true,"metadata":{"nested":["ignored"]},"cover":"""" // opening cover quote
        val suffix = """","skipped":true,"reason":"already current","chapters":120,"chapter_error_count":2,"warnings":["Two chapters could not be downloaded"]}"""
        val coverLength = 5_000_000
        val reader = object : java.io.Reader() {
            var position = 0
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                if (position >= prefix.length + coverLength + suffix.length) return -1
                var count = 0
                while (count < length && position < prefix.length + coverLength + suffix.length) {
                    buffer[offset + count++] = when {
                        position < prefix.length -> prefix[position]
                        position < prefix.length + coverLength -> 'A'
                        else -> suffix[position - prefix.length - coverLength]
                    }
                    position++
                }
                return count
            }
            override fun close() = Unit
        }
        val streamed = streamCompact.invoke(DownloadOutcome, reader) as String
        check(DownloadWarnings.parse(streamed) == warnings)
        check(JSONObject(streamed).getBoolean("skipped"))
        check(JSONObject(streamed).getString("reason") == "already current")
        check(JSONObject(streamed).getInt("chapters") == 120)
        check(!streamed.contains("cover") && streamed.length < 8192)
        println("PASS DownloadWarnings: parsing, queue visibility, all five compact outcomes, streaming 5MB legacy cover")
    }
}
