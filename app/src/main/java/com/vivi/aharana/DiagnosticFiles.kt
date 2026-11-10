package com.vivi.aharana

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Filesystem-only diagnostic helpers; never include INI files, headers or response bodies. */
object DiagnosticFiles {
    private val urls = Regex("https?://[^\\s\\\"<>]+", RegexOption.IGNORE_CASE)
    private val pathSecrets = Regex(
        "(/(?:token|access_token|refresh_token|password|passwd|secret|auth|apikey|api_key|api-key)(?:/|=)).*",
        RegexOption.IGNORE_CASE
    )
    private val headers = Regex("""("?\b(?:authorization|proxy-authorization|cookie|set-cookie)"?\s*[:=]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\r\n]*)""", RegexOption.IGNORE_CASE)
    private val secrets = Regex(
        """("?(?:username|password|passwd|token|access_token|refresh_token|api_key|apikey|secret|credential)"?\s*[:=]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,}]+)""",
        RegexOption.IGNORE_CASE
    )

    fun sanitize(message: String): String {
        // Legacy worker diagnostics included complete bridge payloads after this marker.
        val withoutBodies = message.replace("\\/", "/").replace(Regex("raw_prefix=[^\\r\\n]*"), "raw_prefix=<omitted>")
        val withoutUrls = urls.replace(withoutBodies) { match ->
            try {
                val uri = URI(match.value)
                if (uri.host == null) "<redacted-url>"
                else {
                    val path = pathSecrets.replace(uri.path.orEmpty().replace(Regex(";[^/]*"), "")) {
                        it.groupValues[1] + "[redacted]"
                    }
                    URI(uri.scheme, null, uri.host, uri.port, path, null, null).toASCIIString()
                }
            } catch (_: Exception) { "<redacted-url>" }
        }
        val withoutHeaders = headers.replace(withoutUrls) { it.groupValues[1] + "\"<redacted>\"" }
        return secrets.replace(withoutHeaders) {
            it.groupValues[1] + "\"<redacted>\""
        }
    }

    class RollingFile(val file: File, private val maxBytes: Int = 1024 * 1024, private val backups: Int = 3) {
        init { require(maxBytes > 0 && backups >= 0) }

        @Synchronized fun append(line: String) {
            file.parentFile?.mkdirs()
            val bytes = sanitize(line).toByteArray(StandardCharsets.UTF_8)
            val bounded = if (bytes.size <= maxBytes) bytes else bytes.copyOf(maxBytes)
            if (file.exists() && file.length() + bounded.size > maxBytes) {
                if (backups > 0) {
                    File(file.path + ".$backups").delete()
                    for (index in backups - 1 downTo 1) {
                        val older = File(file.path + ".$index")
                        if (older.exists()) check(older.renameTo(File(file.path + ".${index + 1}")))
                    }
                    check(file.renameTo(File(file.path + ".1")))
                } else check(file.delete())
            }
            FileOutputStream(file, true).use { it.write(bounded) }
        }

        @Synchronized fun clear() {
            file.delete()
            for (index in 1..backups) File(file.path + ".$index").delete()
        }
    }

    /** Limit old/unbounded files too; read tails without loading the original file into memory. */
    fun safeTail(file: File, maxBytes: Int = 1024 * 1024): String {
        if (!file.isFile) return ""
        return RandomAccessFile(file, "r").use { input ->
            val count = minOf(input.length(), maxBytes.toLong()).toInt()
            val truncated = input.length() > count
            input.seek(input.length() - count)
            val bytes = ByteArray(count)
            input.readFully(bytes)
            val text = String(bytes, StandardCharsets.UTF_8)
            sanitize(if (truncated) "[Older content omitted: bounded export]\n" + text.substringAfter('\n', "") else text)
        }
    }

    /** Scrub decoded values, then serialize again; regex edits to escaped JSON corrupt it. */
    private fun sanitizeJson(value: Any?): Any? = when (value) {
        is JSONObject -> value.apply {
            val names = keys().asSequence().toList()
            for (name in names) {
                val sensitive = name.lowercase() in setOf("authorization", "proxy-authorization",
                    "cookie", "set-cookie", "username", "password", "passwd", "token",
                    "access_token", "refresh_token", "api_key", "apikey", "secret", "credential")
                put(name, if (sensitive) "<redacted>" else sanitizeJson(opt(name)))
            }
        }
        is JSONArray -> value.apply {
            for (index in 0 until length()) put(index, sanitizeJson(opt(index)))
        }
        is String -> sanitize(value)
        else -> value
    }

    fun writeBundle(destination: File, sections: Map<String, String>, logs: List<File>) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.path + ".part")
        try {
            ZipOutputStream(FileOutputStream(partial)).use { zip ->
                for ((name, content) in sections) {
                    require(!name.contains('/') && !name.contains('\\'))
                    zip.putNextEntry(ZipEntry(name))
                    val safe = if (name.endsWith(".json")) {
                        sanitizeJson(JSONObject(content)).toString()
                    } else sanitize(content)
                    zip.write(safe.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
                for (file in logs.distinctBy { it.name }.filter { it.isFile }) {
                    zip.putNextEntry(ZipEntry("logs/${file.name}"))
                    zip.write(safeTail(file).toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
            check(partial.renameTo(destination)) { "Cannot publish diagnostic bundle" }
        } finally { partial.delete() }
    }
}
