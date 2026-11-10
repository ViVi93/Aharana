package com.vivi.aharana

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.json.JSONObject

object DiagnosticFilesTest {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("DiagnosticFilesTest").toFile()
        try {
            val privateUrl = "https://alice:private-pass@example.org/story/42?token=hidden-token#private-fragment"
            val safe = DiagnosticFiles.sanitize("url=$privateUrl password=secret-pass cookie=session-secret Authorization: Bearer auth-secret")
            check("https://example.org/story/42" in safe)
            for (secret in listOf("alice", "private-pass", "hidden-token", "private-fragment", "secret-pass", "session-secret", "auth-secret")) {
                check(secret !in safe) { "Leaked $secret: $safe" }
            }
            check("response-body-secret" !in DiagnosticFiles.sanitize("result_error=HTTP503 raw_prefix={response-body-secret}"))
            val json = DiagnosticFiles.sanitize("""{"password":"quoted secret", "token":"token-secret","url":"https://host.test/a?key=secret"}""")
            check("quoted secret" !in json && "token-secret" !in json && "key=secret" !in json)
            val headers = DiagnosticFiles.sanitize("""{"Authorization":"Bearer json-auth-secret","Cookie":"json-cookie-secret","username":"private-login"}""")
            check("json-auth-secret" !in headers && "json-cookie-secret" !in headers && "private-login" !in headers)
            val escaped = DiagnosticFiles.sanitize("""url=https:\/\/alice:escaped-secret@example.org/a?token=escaped-token""")
            check("escaped-secret" !in escaped && "escaped-token" !in escaped)
            val escapedQuote = DiagnosticFiles.sanitize("""{"password":"first\"trailing-secret","Authorization":"Bearer first\"trailing-auth"}""")
            check("trailing-secret" !in escapedQuote && "trailing-auth" !in escapedQuote)
            val quoted = DiagnosticFiles.sanitize("""password='secret with spaces' cookie="private cookie"""")
            check("secret with spaces" !in quoted && "private cookie" !in quoted)
            val file = File(root, "app.log")
            val rolling = DiagnosticFiles.RollingFile(file, maxBytes = 160, backups = 2)
            repeat(20) { rolling.append("line-$it " + "x".repeat(40) + "\n") }
            check(file.readText().contains("line-19"))
            check((listOf(file) + (1..2).map { File(root, "app.log.$it") }).all { it.length() <= 160 })
            check(!File(root, "app.log.3").exists())
            rolling.append("y".repeat(2000))
            check(file.length() <= 160)
            val export = File(root, "diagnostics.zip")
            val legacy = File(root, "legacy.log").apply { writeText("url=$privateUrl\nraw_prefix=response-body-secret\n") }
            DiagnosticFiles.writeBundle(export, mapOf("environment.txt" to "password=export-secret"), listOf(legacy))
            ZipFile(export).use { zip ->
                check(zip.getEntry("environment.txt") != null)
                check(zip.getEntry("logs/legacy.log") != null)
                val content = zip.entries().asSequence().joinToString("\n") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                for (secret in listOf("private-pass", "hidden-token", "export-secret", "response-body-secret")) check(secret !in content)
            }
            val structured = File(root, "structured.zip")
            val alreadySafeError = DiagnosticFiles.sanitize("password=STRUCTURED_SECRET")
            val snapshot = JSONObject().put("rows", org.json.JSONArray().put(
                JSONObject().put("error", alreadySafeError).put("url", privateUrl))).toString()
            DiagnosticFiles.writeBundle(structured, mapOf("queue.json" to snapshot), emptyList())
            ZipFile(structured).use { zip ->
                val text = zip.getInputStream(zip.getEntry("queue.json")).bufferedReader().use { it.readText() }
                val parsed = JSONObject(text)
                check(parsed.getJSONArray("rows").getJSONObject(0).has("error"))
                check(listOf("STRUCTURED_SECRET", "hidden-token", "private-fragment").none { it in text })
            }
            val malformed = DiagnosticFiles.sanitize("url=https://user:secret@bad_host/path?q=secret")
            check("user:secret" !in malformed && "q=secret" !in malformed)
            val pathCredentials = DiagnosticFiles.sanitize(
                "url=https://example.org/token/PATH_SECRET/chapter;session=MATRIX_SECRET")
            check("PATH_SECRET" !in pathCredentials && "MATRIX_SECRET" !in pathCredentials)
            for (path in listOf("auth/token/NESTED_SECRET", "access_token/ROUTE_SECRET", "password=ASSIGNMENT_SECRET")) {
                val redacted = DiagnosticFiles.sanitize("url=https://example.org/$path")
                check(listOf("NESTED_SECRET", "ROUTE_SECRET", "ASSIGNMENT_SECRET").none { it in redacted })
            }
            rolling.clear()
            check((listOf(file) + (1..2).map { File(root, "app.log.$it") }).none { it.exists() })
            println("PASS DiagnosticFiles: redaction, legacy-body stripping, bounded rotation, bundle sanitization, clear")
        } finally { root.deleteRecursively() }
    }
}
