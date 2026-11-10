package com.vivi.aharana

import androidx.room.Query
import com.vivi.aharana.data.local.DownloadJobDao
import com.vivi.aharana.data.local.JobOutcomeIdentity
import java.lang.reflect.Proxy
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Actual production cleanup and actual SQL; only Room's generated adapter is replaced with JDBC. */
object QueueHistoryTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().execute("CREATE TABLE download_jobs (id INTEGER PRIMARY KEY, workId TEXT, status TEXT, bookId INTEGER, type TEXT, inputUrl TEXT, inputPath TEXT, outputPath TEXT, error TEXT, finishedAt INTEGER, createdAt INTEGER, resultJson TEXT)")
        val raw = """{"ok":true,"cover":"${"A".repeat(100_000)}","title":"Preview title","chapters":8,"skipped":true,"reason":"already current","chapter_error_count":2,"warnings":["Missing chapters"]}"""
        fun insert(id: Int, type: String, status: String = "success", payload: String = raw) {
            db.prepareStatement("INSERT INTO download_jobs VALUES (?, ?, ?, 7, ?, 'url', '/original', '/output', 'keep reason', 99, 1, ?)").use {
                it.setInt(1, id); it.setString(2, "work-$id"); it.setString(3, status)
                it.setString(4, type); it.setString(5, payload); it.executeUpdate()
            }
        }
        for (id in 1..100) insert(id, listOf("download", "update", "force_download")[id % 3],
            listOf("success", "failed", "cancelled", "superseded")[id % 4])
        insert(101, "metadata")
        var maxChunk = 0
        var race = false
        var raceTriggered = false
        val methods = DownloadJobDao::class.java.methods.associateBy { it.name }
        fun sql(name: String) = methods.getValue(name).getAnnotation(Query::class.java).value
        val names = mapOf("nextLegacyOutcome" to listOf("afterId"), "outcomeIdentity" to listOf("id"),
            "readOutcomeChunk" to listOf("id", "workId", "type", "status", "finishedAt", "resultLength", "start"),
            "compactLegacyOutcome" to listOf("id", "workId", "type", "status", "finishedAt", "resultLength", "resultJson"))
        val dao = Proxy.newProxyInstance(DownloadJobDao::class.java.classLoader, arrayOf(DownloadJobDao::class.java)) { _, method, values ->
            val parameters = names[method.name] ?: error("Unexpected DAO access: ${method.name}")
            val bindings = parameters.zip(values!!.take(parameters.size)).toMap()
            val order = mutableListOf<String>()
            val query = Regex(":([A-Za-z]+)").replace(sql(method.name)) { match -> order.add(match.groupValues[1]); "?" }
            db.prepareStatement(query).use { statement ->
                order.forEachIndexed { index, name -> statement.setObject(index + 1, bindings[name]) }
                if (method.name == "compactLegacyOutcome") statement.executeUpdate()
                else statement.executeQuery().use { rows ->
                    if (!rows.next()) null
                    else if (method.name == "readOutcomeChunk") {
                        val chunk = rows.getString(1)
                        maxChunk = maxOf(maxChunk, chunk.length)
                        if (race && !raceTriggered) {
                            raceTriggered = true
                            db.createStatement().execute("UPDATE download_jobs SET workId='replacement', status='queued', error=NULL WHERE id=1000")
                        }
                        chunk
                    } else JobOutcomeIdentity(rows.getLong("id"), rows.getString("workId"), rows.getString("type"),
                        rows.getString("status"), rows.getLong("finishedAt"), rows.getInt("resultLength"))
                }
            }
        } as DownloadJobDao
        // Projection executes BEFORE cleanup, covering every historical row, including metadata.
        db.createStatement().executeQuery(sql("getAll")).use { rows ->
            var count = 0
            while (rows.next()) { count++; check(rows.getString("resultJson") == null) }
            check(count == 101)
        }
        val preview = QueueHistory.metadataPreview(dao, 101) ?: error("Missing metadata preview")
        check(preview.getBoolean("ok") && preview.getString("title") == "Preview title" && preview.getInt("chapters") == 8)
        check(!preview.has("cover") && preview.toString().length < 4096)
        check(QueueHistory.metadataPreview(dao, 1) == null)
        check(QueueHistory.compactLegacy(dao) == 100)
        check(maxChunk <= 8192)
        check(QueueHistory.compactLegacy(dao) == 0) { "Compaction must be idempotent" }
        db.createStatement().executeQuery("SELECT * FROM download_jobs ORDER BY id").use { rows ->
            while (rows.next()) {
                if (rows.getInt("id") == 101) check(rows.getString("resultJson") == raw)
                else {
                    val saved = rows.getString("resultJson")
                    check(saved.length < 8192 && !saved.contains("cover"))
                    check(DownloadWarnings.parse(saved).messages == listOf("Missing chapters"))
                    check(DownloadWarnings.parse(saved).chapterErrorCount == 2)
                    check(JSONObject(saved).getBoolean("skipped"))
                    check(JSONObject(saved).getString("reason") == "already current")
                }
                check(rows.getString("error") == "keep reason" && rows.getLong("bookId") == 7L)
                check(rows.getString("inputPath") == "/original" && rows.getLong("finishedAt") == 99L)
            }
        }
        insert(1000, "download")
        race = true
        check(QueueHistory.compactLegacy(dao) == 0)
        check(raceTriggered)
        db.createStatement().executeQuery("SELECT workId,status,resultJson FROM download_jobs WHERE id=1000").use {
            check(it.next()); check(it.getString(1) == "replacement" && it.getString(2) == "queued" && it.getString(3) == raw)
        }
        println("PASS QueueHistory: 100 legacy cover rows, <=8K chunks, preserved warnings/skips/states/metadata, idempotence, mid-stream Retry fence")
        db.close()
    }
}