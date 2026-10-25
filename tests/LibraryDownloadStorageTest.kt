package com.vivi.aharana

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.launch

object LibraryDownloadStorageTest {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("download-storage-").toFile()
        try {
            val existing = File(root, "Author/Title.epub").apply { parentFile.mkdirs(); writeText("old book") }
            val generated = File(root, "generated-new-name.epub").apply { writeText("new book") }
            LibraryEpubStorage.replace(generated, existing)
            check(existing.readText() == "new book") { "Update must replace the organised book in place" }
            check(generated.readText() == "new book") { "Replacement must not consume the source" }
            check(root.walkTopDown().filter { it.isFile }.count() == 2) { "No duplicate or staging files may remain" }
            println("PASS: staged replacement preserves Author/Title.epub without a duplicate")
            val empty = File(root, "empty.epub").apply { writeBytes(byteArrayOf()) }
            check(runCatching { LibraryEpubStorage.replace(empty, existing) }.isFailure) {
                "Empty generated output must not destroy an existing EPUB"
            }
            check(existing.readText() == "new book")
            empty.delete()
            println("PASS: empty generated output is rejected without touching the original")
            val failedCopy = runCatching {
                LibraryEpubStorage.replace(generated, existing, stageCopy = { _, stage ->
                    stage.writeText("partial")
                    throw java.io.IOException("simulated full disk")
                })
            }
            check(failedCopy.isFailure && existing.readText() == "new book")
            check(existing.parentFile.listFiles()!!.map { it.name } == listOf("Title.epub")) {
                "Partial staging output must be removed and the original retained"
            }
            check(runCatching {
                LibraryEpubStorage.replace(generated, existing, stageCopy = { _, stage -> stage.writeText("x") })
            }.isFailure) { "A short staged copy must never replace the complete original" }
            check(existing.readText() == "new book")
            check(LibraryEpubStorage.replace(existing, existing) == existing.absolutePath)
            check(existing.readText() == "new book")
            println("PASS: failed/short staging copies preserve original bytes; same-path input is safe")
            var replacementCalled = false
            val keptDocument = LibraryEpubStorage.saveExisting(generated, "content://library/book",
                replaceFile = { _, _ -> error("Never truncate the picked original") },
                saveDocumentCopy = { source -> replacementCalled = true; check(source == generated); "content://output/new" })
            check(replacementCalled && keptDocument == "content://output/new")
            val keptPath = LibraryEpubStorage.saveExisting(generated, existing.path,
                replaceFile = { source, path -> check(source == generated && path == existing.path); path },
                saveDocumentCopy = { error("Filesystem update must stay at its existing path") })
            check(keptPath == existing.path)
            println("PASS: picked documents save a new copy; filesystem updates keep their path")
            val legacy = LibraryEpubStorage.copyDownload(generated, root.path, false,
                legacyCopy = { source, _ -> check(source == generated); "legacy.epub" },
                organisedCopy = { _, _ -> error("Disabled preference must preserve legacy copy behavior") })
            check(legacy == "legacy.epub")
            val organised = LibraryEpubStorage.copyDownload(generated, root.path, true,
                legacyCopy = { _, _ -> error("Enabled preference must never flatten the book") },
                organisedCopy = { source, destination -> check(source == generated && destination == root.path); existing.path })
            check(organised == existing.path)
            var called = false
            val rejected = runCatching {
                LibraryEpubStorage.copyDownload(generated, "content://output/tree", true,
                    legacyCopy = { _, _ -> called = true; "bad" },
                    organisedCopy = { _, _ -> called = true; "bad" })
            }.exceptionOrNull()
            check(rejected is java.io.IOException && rejected.message!!.contains("filesystem"))
            check(!called) { "SAF organisation must fail before any output is created" }
            println("PASS: preference selects shared organiser naming; SAF rejection never flattens")
            check(LibraryEpubStorage.existingPath(existing.path, "stale-flat.epub") == existing.path)
            check(LibraryEpubStorage.existingPath(null, existing.path) == existing.path)
            check(LibraryEpubStorage.sourceAfterReplacement("old", "old", "new") == "new")
            check(LibraryEpubStorage.sourceAfterReplacement("old", null, "new") == "new")
            check(LibraryEpubStorage.sourceAfterReplacement("cache", "content://source", "cache") == "content://source")
            println("PASS: canonical Room path wins over stale queued input; source provenance is retained")
            kotlinx.coroutines.runBlocking {
                val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
                val release = kotlinx.coroutines.CompletableDeferred<Unit>()
                val events = java.util.Collections.synchronizedList(mutableListOf<String>())
                val organiser = launch(kotlinx.coroutines.Dispatchers.Default) {
                    LibraryStorageGate.run {
                        events.add("organiser")
                        entered.complete(Unit)
                        release.await()
                    }
                }
                entered.await()
                val worker = launch(kotlinx.coroutines.Dispatchers.Default) {
                    LibraryStorageGate.run { events.add("worker") }
                }
                val detail = Thread { LibraryStorageGate.runBlocking { events.add("detail") } }.apply { start() }
                kotlinx.coroutines.delay(100)
                check(events == listOf("organiser")) { "Worker/detail must not touch paths while organiser holds the gate" }
                release.complete(Unit)
                organiser.join(); worker.join()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { detail.join(5000) }
                check(!detail.isAlive && events.toSet() == setOf("organiser", "worker", "detail"))
                runCatching { LibraryStorageGate.run { error("simulated failure") } }
                LibraryStorageGate.run { events.add("after failure") }
            }
            println("PASS: suspend worker and blocking detail share organiser exclusion; failure releases gate")
        } finally { root.deleteRecursively() }
    }
}
