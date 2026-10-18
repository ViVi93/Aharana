package com.vivi.aharana

import kotlinx.coroutines.*

object LibraryFacetIndexTest {
    data class Book(val path: String, val version: String)
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val books = (1..3500).map { Book("book-$it", "1") }
        val disk = linkedMapOf<String, LibraryFacetIndex.Entry>()
        var bulkLoads = 0; var sourceChecks = 0; var parses = 0; var writes = 0
        var sourceVersion = "1"; var fail = false
        fun fresh() = LibraryFacetIndex<Book>(
            identity = { it.path }, bookSignature = { it.version },
            fileSignature = { sourceChecks++; sourceVersion },
            loadAll = { bulkLoads++; disk.toMap() },
            save = { writes++; disk[it.record.path] = it },
            delete = { disk.remove(it); Unit },
            extract = { b ->
                parses++
                LibraryFacetRecord(b.path, mapOf("status" to setOf(if (fail) "(Unreadable)" else "Complete"))) to !fail
            })
        val first = fresh()
        for (book in books) first.read(book)
        check(parses == 3500 && writes == 3500)
        val restarted = fresh(); sourceChecks = 0; parses = 0; writes = 0
        check(restarted.cached(books).size == 3500)
        check(sourceChecks == 0 && parses == 0 && writes == 0 && bulkLoads == 2)
        for (book in books) restarted.read(book)
        check(parses == 0 && writes == 0)
        val changed = Book("book-2", "2")
        restarted.read(changed)
        check(parses == 1 && writes == 1)
        sourceVersion = "2"; restarted.read(books[0])
        check(parses == 2) // External source fingerprint changed, stored library revision did not.
        restarted.read(changed, force = true)
        check(parses == 3)
        fail = true
        val missing = Book("unreadable", "1")
        check(!restarted.read(missing).second && !restarted.read(missing).second)
        check("unreadable" !in disk && parses == 5)
        fail = false
        restarted.invalidate("book-3")
        check("book-3" !in disk)
        restarted.read(books[2]); check(parses == 6)
        restarted.prune(setOf("book-1", "book-2", "book-3"))
        check(disk.keys == setOf("book-1", "book-2", "book-3"))
        val readEntered = CompletableDeferred<Unit>(); val readRelease = CompletableDeferred<Unit>()
        var members = setOf("keep")
        val guardedDisk = linkedMapOf("keep" to LibraryFacetIndex.Entry("1", "1", LibraryFacetRecord("keep", emptyMap())))
        val guarded = LibraryFacetIndex<Book>(identity = { it.path }, bookSignature = { it.version },
            fileSignature = { "1" }, loadAll = { guardedDisk.toMap() }, save = { guardedDisk[it.record.path] = it },
            delete = { guardedDisk.remove(it); Unit }, extract = { b ->
                readEntered.complete(Unit); readRelease.await(); LibraryFacetRecord(b.path, emptyMap()) to true
            })
        val readJob = launch { guarded.read(Book("new", "1")) }; readEntered.await()
        val pruning = launch { guarded.prune { members } }; yield()
        members = setOf("keep", "new"); readRelease.complete(Unit); readJob.join(); pruning.join()
        check(guardedDisk.keys == members) { "queued stale prune deleted a newly indexed book" }
        var versionDuringRead = "before"
        var mutationSaved = false
        val mutating = LibraryFacetIndex<Book>(identity = { it.path }, bookSignature = { it.version },
            fileSignature = { versionDuringRead }, loadAll = { emptyMap() }, save = { mutationSaved = true }, delete = {},
            extract = { b -> versionDuringRead = "after"; LibraryFacetRecord(b.path, emptyMap()) to true })
        var rejected = false
        try { mutating.read(books[0]) } catch (_: IllegalStateException) { rejected = true }
        check(rejected && !mutationSaved) { "source changed mid-read but was reported as a successful snapshot" }
        println("PASS index cold3500; bulk warm3500 without source checks; unchanged0 parses/writes; changed1; external change; force; failed-read retry; invalidation; deletion; changed-during-read rejection")
    }
}
