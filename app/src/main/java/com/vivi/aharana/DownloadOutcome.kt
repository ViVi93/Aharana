package com.vivi.aharana

import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader

/** Queue history is an outcome, not a second copy of the book/bridge response.
 * The fixed prefix lets SQLite exclude legacy bodies before cursor materialization.
 * Metadata jobs deliberately do not use this format (AddFromPage consumes their metadata).
 */
object DownloadOutcome {
    const val PREFIX = "{\"queue_outcome\":1,"
    const val MAX_JSON_CHARS = 8192

    fun compact(result: JSONObject): String {
        val warnings = result.optJSONArray("warnings")
        val messages = JSONArray()
        if (warnings != null) {
            for (i in 0 until warnings.length()) {
                val message = (warnings.opt(i) as? String)?.trim().orEmpty()
                if (message.isNotEmpty()) messages.put(message.take(192))
                if (messages.length() == 4) break
            }
        }
        val outcome = JSONObject().put("ok", result.optBoolean("ok"))
            .put("skipped", result.optBoolean("skipped"))
            .put("chapter_error_count", result.optInt("chapter_error_count").coerceAtLeast(0))
            .put("warnings", messages)
        if (result.has("chapters")) outcome.put("chapters", result.optInt("chapters").coerceAtLeast(0))
        for (key in listOf("chapter_added_count", "chapter_updated_count", "chapter_written_count")) {
            if (result.has(key)) outcome.put(key, result.optInt(key).coerceAtLeast(0))
        }
        for (key in listOf("reason", "status", "error")) {
            (result.opt(key) as? String)?.let { outcome.put(key, it.take(128)) }
        }
        return PREFIX + outcome.toString().drop(1)
    }

    /** Retain only bounded top-level values while streaming past arbitrary cover/titlepage bodies.
     * Reader I/O failures propagate: a changed/retried row must never be compacted from mixed chunks.
     */
    fun compact(reader: Reader): String = compact(readFields(reader,
        setOf("ok", "skipped", "chapter_error_count", "chapter_added_count", "chapter_updated_count",
            "chapter_written_count", "chapters", "warnings", "reason", "status", "error")))

    /** AddFromPage needs only these fields, not a cover or the full metadata response. */
    fun metadataPreview(reader: Reader): JSONObject {
        val fields = readFields(reader, setOf("ok", "title", "chapters"))
        return JSONObject().put("ok", fields.optBoolean("ok") && !fields.optBoolean("history_unreadable"))
            .put("title", (fields.opt("title") as? String).orEmpty().take(512))
            .put("chapters", fields.optInt("chapters").coerceAtLeast(0))
    }

    private fun readFields(reader: Reader, keys: Set<String>): JSONObject {
        val source = java.io.PushbackReader(reader.buffered(8192), 1)
        fun next(): Int {
            var c = source.read()
            while (c >= 0 && c.toChar().isWhitespace()) c = source.read()
            return c
        }
        // Capture only a bounded JSON token, but always consume it completely. Unknown bodies
        // allocate no string regardless of their size; nesting is also bounded for corrupt input.
        fun token(first: Int, limit: Int, quotedOnly: Boolean = false): String? {
            val raw = StringBuilder()
            var c = first
            var depth = 0
            var quoted = false
            var escaped = false
            var overflow = false
            while (c >= 0) {
                if (!quoted && depth == 0 && (c == ','.code || c == '}'.code || c == ']'.code || c.toChar().isWhitespace())) {
                    source.unread(c)
                    break
                }
                if (raw.length < limit) raw.append(c.toChar()) else overflow = true
                if (quoted) {
                    if (escaped) escaped = false
                    else if (c == '\\'.code) escaped = true
                    else if (c == '"'.code) {
                        quoted = false
                        if (quotedOnly) break
                    }
                } else when (c.toChar()) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; if (depth > 64) throw org.json.JSONException("History nesting limit") }
                    '}', ']' -> depth--
                }
                c = source.read()
            }
            if (quoted || depth != 0 || (quotedOnly && c < 0)) throw org.json.JSONException("Incomplete history JSON")
            return if (overflow) null else raw.toString()
        }
        val result = JSONObject()
        try {
            if (next() != '{'.code) throw org.json.JSONException("History must be an object")
            var c = next()
            if (c == '}'.code) return result
            while (true) {
                if (c != '"'.code) throw org.json.JSONException("Missing history key")
                val keyToken = token(c, 1024, quotedOnly = true)
                val key = keyToken?.let { JSONObject("{\"v\":$it}").getString("v") }
                if (next() != ':'.code) throw org.json.JSONException("Missing history value")
                val value = token(next(), if (key in keys) MAX_JSON_CHARS else 0)
                if (key in keys) {
                    if (value != null) result.put(key!!, JSONObject("{\"v\":$value}").get("v"))
                    else if (key == "warnings") result.put("warnings", JSONArray().put("Historical warning details exceeded the retained limit"))
                }
                c = next()
                if (c == '}'.code) break
                if (c != ','.code) throw org.json.JSONException("Missing history separator")
                c = next()
            }
            if (next() >= 0) throw org.json.JSONException("Trailing history JSON")
        } catch (_: org.json.JSONException) {
            // Keep the row/status/retry identity; surface incomplete historical evidence, not success certainty.
            result.put("warnings", JSONArray().put("Historical outcome was incomplete or unreadable"))
            result.put("history_unreadable", true)
        }
        return result
    }
}