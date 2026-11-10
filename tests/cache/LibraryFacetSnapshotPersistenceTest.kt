package com.vivi.aharana

import android.content.Context
import java.nio.file.Files
import java.sql.DriverManager

/** Real production database methods and payload encoding, exercised against file-backed SQLite. */
object LibraryFacetSnapshotPersistenceTest {
    private fun entry(path: String, value: String) = LibraryFacetIndexEntry("book", "file",
        LibraryFacetRecord(path, mapOf("tags" to setOf(value))))

    @JvmStatic fun main(args: Array<String>) {
        val directory = Files.createTempDirectory("facet-snapshot-").toFile()
        try {
            val context = Context(directory)
            val database = LibraryFacetIndexDatabase(context)
            val old = listOf(entry("a", "Old"), entry("b", "Old"), entry("deleted", "Old"))
            database.upsertAll(old)
            val before = LibraryFacetIndexDatabase(context).readAll()
            DriverManager.getConnection("jdbc:sqlite:${directory.resolve("library_facet_index_v2.db")}").use { connection ->
                connection.createStatement().use {
                    it.execute("CREATE TRIGGER fail_prune BEFORE DELETE ON library_facet_index WHEN OLD.path = 'deleted' BEGIN SELECT RAISE(ABORT, 'injected prune failure'); END")
                }
            }
            val next = listOf(entry("a", "New"), entry("b", "New"))
            var failed = false
            try { replace(database, next) } catch (_: Exception) { failed = true }
            check(failed)
            check(LibraryFacetIndexDatabase(context).readAll() == before) {
                "snapshot replacements committed before pruning failed; restart observed a partial durable advance"
            }
            println("PASS real SQLite snapshot replacement/pruning rollback preserves every old row on reopen")
        } finally { directory.deleteRecursively() }
    }

    private fun replace(database: LibraryFacetIndexDatabase, entries: Collection<LibraryFacetIndexEntry>) {
        database.replaceSnapshot(entries)
    }
}
