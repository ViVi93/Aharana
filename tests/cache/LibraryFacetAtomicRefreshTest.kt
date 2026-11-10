package com.vivi.aharana

import android.content.Context
import kotlinx.coroutines.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Coordinator + index + production database, reopened from actual SQLite files at every boundary. */
object LibraryFacetAtomicRefreshTest {
    private data class Book(val path: String, val revision: Int = 1)
    private class Fixture(val scope: CoroutineScope, val io: CoroutineDispatcher = Dispatchers.Unconfined) : AutoCloseable {
        val directory = Files.createTempDirectory("facet-refresh-").toFile()
        val context = Context(directory)
        val database = LibraryFacetIndexDatabase(context)
        var books = listOf(Book("a"), Book("b"))
        val versions = ConcurrentHashMap<String, String>().apply { put("a", "1"); put("b", "1") }
        var beforeRead: suspend (String) -> Unit = {}
        var readable: (String) -> Boolean = { true }
        var beforeStore: () -> Unit = {}
        var afterStore: () -> Unit = {}
        var tag = "New"
        var reads = 0; var stats = 0; var commits = 0
        var choices = mapOf("tags" to setOf("Old"))
        val old = books.associate { it.path to LibraryFacetIndex.Entry("1", "1", record(it.path, "Old")) }
        init { database.upsertAll(old.values.map(::stored)) }
        fun stored(entry: LibraryFacetIndex.Entry) = LibraryFacetIndexEntry(entry.bookSignature, entry.fileSignature, entry.record)
        fun reopen() = LibraryFacetIndexDatabase(context).readAll().mapValues { (_, e) ->
            LibraryFacetIndex.Entry(e.bookSignature, e.fileSignature, e.record)
        }
        fun newIndex() = LibraryFacetIndex<Book>(identity = { it.path }, bookSignature = { it.revision.toString() },
            fileSignature = { stats++; versions[it.path] ?: "1" }, loadAll = ::reopen,
            save = { error("manual refresh used per-book write") }, delete = database::invalidate,
            extract = { book ->
                reads++; beforeRead(book.path)
                val good = readable(book.path)
                record(book.path, if (good) currentCoroutineContext()[CoroutineName]?.name ?: tag else "(Unreadable)") to good
            },
            replaceSnapshot = { entries -> beforeStore(); database.replaceSnapshot(entries.map(::stored)); commits++; afterStore() },
            ioDispatcher = io)
        val index by lazy { newIndex() }
        fun state(cache: LibraryFacetIndex<Book> = index, scanScope: CoroutineScope = scope,
            onBegin: () -> Unit = {}) = LibraryFilterState(scanScope, { books },
            { rows -> rows.map { "${it.path}:${it.revision}" }.sorted() },
            read = { _: Book -> error("automatic source read") },
            loadCached = { cache.cached(it, allowStale = true) }, identity = { it.path },
            cachedCurrentPaths = { cache.cached(it).keys }, manualRefreshOnly = true,
            beginRefresh = { cache.beginRefresh().also { onBegin() } }, persistSelection = { choices = it },
            cachedUnreadablePaths = { cache.unreadable(it) })
        fun sql(statement: String) {
            DriverManager.getConnection("jdbc:sqlite:${directory.resolve("library_facet_index_v2.db")}").use { connection ->
                connection.createStatement().use { it.execute(statement) }
            }
        }
        override fun close() { directory.deleteRecursively() }
    }
    private fun record(path: String, value: String) = LibraryFacetRecord(path, mapOf("tags" to setOf(value)))
    private var passed = 0
    private suspend fun test(name: String, block: suspend CoroutineScope.() -> Unit) = coroutineScope {
        withTimeout(10_000) { block() }; passed++; println("PASS $name")
    }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        test("actual persistence: cancel after A read before B keeps complete old cache on a new VM/index") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope).use { f ->
                try {
                    val atB = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
                    f.beforeRead = { if (it == "b") try { atB.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) } }
                    val state = f.state(); state.restore(f.choices)
                    val refresh = launch { state.prepare(force = true) }; atB.await()
                    check(f.reopen() == f.old && f.commits == 0)
                    refresh.cancelAndJoin(); stopped.await()
                    val reopened = f.state(f.newIndex()); reopened.restore(f.choices); reopened.prepare()
                    check(f.reopen() == f.old && reopened.records == state.records && reopened.selection == state.selection)
                    check(f.reads == 2 && f.commits == 0)
                } finally { scope.cancel() }
            }
        }
        test("an unreadable book keeps prior facets while readable books commit atomically") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope).use { f ->
                try {
                    f.readable = { it != "b" }
                    val state = f.state(); state.restore(f.choices)
                    state.prepare(force = true)
                    check(f.commits == 1 && f.reads == 2)
                    check(state.records["a"] == record("a", "New"))
                    check(state.records["b"] == f.old.getValue("b").record) { "unreadable book lost its prior metadata" }
                    check(state.failures == 1 && state.needsRefresh() && state.selection == f.choices)
                    check(f.reopen().mapValues { it.value.record } == state.records)
                    check(f.index.cached(f.books).keys == setOf("a")) { "unreadable cache was marked fresh" }
                } finally { scope.cancel() }
            }
        }
        test("unreadability survives restart without source reads and a later retry clears it") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope).use { f ->
                try {
                    f.readable = { it != "b" }
                    val state = f.state(); state.restore(f.choices); state.prepare(force = true)
                    val reads = f.reads; val stats = f.stats
                    val cache = f.newIndex(); val reopened = f.state(cache)
                    reopened.restore(f.choices); reopened.prepare()
                    check(reopened.failures == 1) { "unreadability was lost across process recreation" }
                    check(reopened.needsRefresh() && reopened.records == state.records)
                    check(f.reads == reads && f.stats == stats) { "restoring warnings performed source IO" }
                    f.readable = { true }
                    reopened.prepare(force = true)
                    check(reopened.failures == 0 && !reopened.needsRefresh() && f.commits == 2)
                    check(cache.cached(f.books).keys == setOf("a", "b"))
                } finally { scope.cancel() }
            }
        }
        test("new unreadable books get explicit fallback facets and a bounded actionable summary") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope).use { f ->
                try {
                    f.books = listOf(Book("a"), Book("b")) + (1..8).map { Book("new$it") }
                    f.readable = { it == "a" }
                    val state = f.state(); state.restore(f.choices); state.prepare(force = true)
                    check(f.commits == 1 && state.failures == 9)
                    check(state.records.getValue("b") == f.old.getValue("b").record)
                    check(state.records.getValue("new1").facets["tags"] == setOf("(Unreadable)"))
                    var labels = 0
                    val summary = state.unreadableSummary { labels++; "Title\n\u0000" + "x".repeat(5000) }
                    check(labels == 5 && summary.length < 1200 && summary.lines().size == 7)
                    check("\u0000" !in summary && "4 more" in summary && "retry" in summary && "Diagnostics" in summary)
                    check(f.index.cached(f.books).keys == setOf("a"))
                } finally { scope.cancel() }
            }
        }
        test("cancel after an unreadable book does not publish its staged failure marker") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope).use { f ->
                try {
                    val atB = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
                    f.readable = { it != "a" }
                    f.beforeRead = { if (it == "b") try { atB.complete(Unit); awaitCancellation() }
                        finally { stopped.complete(Unit) } }
                    val state = f.state(); state.restore(f.choices)
                    val task = launch { state.prepare(force = true) }; atB.await()
                    task.cancelAndJoin(); stopped.await()
                    val reopened = f.state(f.newIndex()); reopened.restore(f.choices)
                    check(f.commits == 0 && f.reopen() == f.old && reopened.failures == 0)
                    check(reopened.records == state.records && state.failures == 0)
                } finally { scope.cancel() }
            }
        }
        test("actual persistence: unexpected extraction failure leaves disk and live cache unchanged") {
            for (unreadable in listOf(false)) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                Fixture(scope).use { f ->
                    try {
                        if (unreadable) f.readable = { it != "b" }
                        else f.beforeRead = { if (it == "b") error("injected extractor failure") }
                        val state = f.state(); state.restore(f.choices)
                        check(runCatching { state.prepare(force = true) }.isFailure)
                        check(f.reopen() == f.old && f.index.cached(f.books, true) == state.records && f.commits == 0)
                        val reopened = f.state(f.newIndex()); reopened.restore(f.choices)
                        check(reopened.records == state.records && reopened.selection == state.selection)
                    } finally { scope.cancel() }
                }
            }
        }
        test("actual persistence: A source changing while B reads rejects final commit") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope).use { f ->
                try {
                    f.beforeRead = { if (it == "b") f.versions["a"] = "2" }
                    val state = f.state(); state.restore(f.choices)
                    check(runCatching { state.prepare(force = true) }.isFailure)
                    check(f.reopen() == f.old && f.commits == 0 && state.records == f.old.mapValues { it.value.record })
                } finally { scope.cancel() }
            }
        }
        test("actual persistence: library change discards staged A and cancellation of replacement keeps old snapshot") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope).use { f ->
                try {
                    val atB = CompletableDeferred<Unit>(); val resumeB = CompletableDeferred<Unit>()
                    val restarted = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
                    var attempts = 0
                    f.beforeRead = { path ->
                        if (path == "a" && ++attempts == 2) try { restarted.complete(Unit); awaitCancellation() }
                            finally { stopped.complete(Unit) }
                        if (path == "b") { atB.complete(Unit); resumeB.await() }
                    }
                    val state = f.state(); state.restore(f.choices)
                    val refresh = launch { state.prepare(force = true) }; atB.await()
                    f.books = listOf(Book("a", 2), Book("new")); state.booksChanged(); resumeB.complete(Unit)
                    restarted.await(); check(f.reopen() == f.old && f.commits == 0)
                    refresh.cancelAndJoin(); stopped.await()
                    val reopened = f.state(f.newIndex()); reopened.restore(f.choices)
                    check(reopened.records == state.records && state.records.keys == setOf("a"))
                } finally { scope.cancel() }
            }
        }
        test("actual persistence: mid-transaction write failure rolls A back and keeps live cache and choices") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope).use { f ->
                try {
                    f.sql("CREATE TRIGGER fail_b BEFORE INSERT ON library_facet_index WHEN NEW.path = 'b' BEGIN SELECT RAISE(ABORT, 'injected write failure'); END")
                    val state = f.state(); state.restore(f.choices)
                    check(runCatching { state.prepare(force = true) }.isFailure)
                    check(f.reopen() == f.old && f.commits == 0 && f.index.cached(f.books) == state.records)
                    val reopened = f.state(f.newIndex()); reopened.restore(f.choices)
                    check(reopened.records == state.records && reopened.selection == state.selection)
                } finally { scope.cancel() }
            }
        }
        test("accepted blocking commit completes memory publication despite cancellation and concurrent Clear") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope, Dispatchers.IO).use { f ->
                val release = CountDownLatch(1)
                try {
                    val committing = CompletableDeferred<Unit>(); val published = CompletableDeferred<Unit>()
                    f.beforeStore = { committing.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
                    val state = f.state(); state.restore(f.choices)
                    val refresh = launch { state.prepare(force = true) }; committing.await()
                    refresh.cancelAndJoin()
                    state.apply(emptyMap()).join()
                    check(f.choices.isEmpty() && state.selection.isEmpty())
                    scope.launch { while (state.records.values.any { it.facets["tags"] != setOf("New") }) yield(); published.complete(Unit) }
                    check(f.reopen() == f.old)
                    release.countDown(); published.await()
                    check(state.selection.isEmpty() && f.reopen().mapValues { it.value.record } == state.records)
                    val reopened = f.state(f.newIndex()); reopened.restore(f.choices)
                    check(reopened.records == state.records && reopened.selection.isEmpty() && f.commits == 1)
                } finally { release.countDown(); scope.cancel() }
            }
        }
        test("superseded coordinator cannot retry itself over a newer refresh snapshot") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope).use { f ->
                try {
                    val atB = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                    var first = true
                    f.beforeRead = { if (it == "b" && first) { first = false; atB.complete(Unit); release.await() } }
                    val older = f.state(); older.restore(f.choices)
                    val oldRefresh = async { runCatching { older.prepare(force = true) } }; atB.await()
                    val newer = f.state(); val latest = launch { newer.prepare(force = true) }; yield()
                    release.complete(Unit); latest.join()
                    check(oldRefresh.await().exceptionOrNull() is CancellationException) { "superseded scan was allowed to retry over newer owner" }
                    check(f.commits == 1 && f.reopen().mapValues { it.value.record } == newer.records)
                } finally { scope.cancel() }
            }
        }
        test("current owner retries a changed library with empty staging and keeps one commit") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            Fixture(scope).use { f ->
                try {
                    val atB = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                    var first = true
                    f.beforeRead = {
                        if (it == "b" && first) { first = false; atB.complete(Unit); release.await() }
                    }
                    val state = f.state(); state.restore(f.choices)
                    val refresh = launch { state.prepare(force = true) }; atB.await()
                    f.books = listOf(Book("a", 2), Book("new")); state.booksChanged(); release.complete(Unit)
                    refresh.join()
                    check(f.commits == 1 && f.reads == 4 && state.isFresh())
                    check(f.reopen().keys == setOf("a", "new") && state.records.keys == setOf("a", "new"))
                    check(f.reopen().mapValues { it.value.record } == state.records)
                } finally { scope.cancel() }
            }
        }
        test("library-signature retry cannot reclaim generation from a newer coordinator") {
            val olderScope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("older"))
            val newerScope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("newer"))
            Fixture(olderScope).use { f ->
                try {
                    val atB = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                    val newerBegan = CompletableDeferred<Unit>()
                    var first = true
                    f.beforeRead = {
                        if (it == "b" && first) { first = false; atB.complete(Unit); release.await() }
                    }
                    val older = f.state(); older.restore(f.choices)
                    val oldTask = async { runCatching { older.prepare(force = true) } }
                    atB.await()
                    f.books = listOf(Book("a", 2), Book("b")); older.booksChanged()
                    val newer = f.state(scanScope = newerScope, onBegin = { newerBegan.complete(Unit) })
                    val newTask = async { runCatching { newer.prepare(force = true) } }
                    newerBegan.await(); release.complete(Unit)
                    val newerResult = newTask.await(); val olderResult = oldTask.await()
                    check(newerResult.isSuccess) { "older retry superseded the newer refresh: $newerResult" }
                    check(olderResult.exceptionOrNull() is CancellationException) {
                        "superseded older refresh reclaimed ownership after a library revision change"
                    }
                    check(f.commits == 1 && f.reopen().mapValues { it.value.record } == newer.records)
                    check(newer.records.values.all { it.facets["tags"] == setOf("newer") })
                } finally { olderScope.cancel(); newerScope.cancel() }
            }
        }
        println("$passed real SQLite atomic-refresh tests passed")
    }
}
