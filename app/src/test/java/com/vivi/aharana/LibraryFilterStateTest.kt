package com.vivi.aharana

import kotlinx.coroutines.*

/** Deterministic coroutine tests of the exact coordinator used by LibraryViewModel. No sleeps/Android mocks. */
object LibraryFilterStateTest {
    private var passed = 0
    private suspend fun test(name: String, block: suspend () -> Unit) { block(); passed++; println("PASS $name") }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        test("cancel after A before B preserves the durable snapshot on a new coordinator and index") {
            val books = listOf("a", "b")
            fun record(path: String, value: String) = LibraryFacetRecord(path, mapOf("tags" to setOf(value)))
            val original = books.associateWith { LibraryFacetIndex.Entry("1", "1", record(it, "Old")) }
            val disk = original.toMutableMap()
            val enteredB = CompletableDeferred<Unit>(); val cancelledB = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            fun index() = LibraryFacetIndex<String>(identity = { it }, bookSignature = { "1" },
                fileSignature = { "1" }, loadAll = { disk.toMap() }, save = { disk[it.record.path] = it },
                delete = { disk.remove(it); Unit }, extract = { path ->
                    if (path == "b") try { enteredB.complete(Unit); awaitCancellation() }
                        finally { cancelledB.complete(Unit) }
                    record(path, "New") to true
                }, replaceSnapshot = { rows -> disk.clear(); disk.putAll(rows.associateBy { it.record.path }) })
            fun state(cache: LibraryFacetIndex<String>) = LibraryFilterState(scope, { books }, { it },
                read = { path: String -> cache.read(path, cacheResult = false) },
                readFresh = { path: String -> cache.read(path, force = true, cacheResult = false) },
                beginRefresh = cache::beginRefresh,
                loadCached = { cache.cached(it, allowStale = true) }, manualRefreshOnly = true)
            try {
                val current = state(index()); current.restore(mapOf("tags" to setOf("Old")))
                val refresh = launch { current.prepare(force = true) }; enteredB.await()
                refresh.cancelAndJoin(); cancelledB.await()
                check(current.records == original.mapValues { it.value.record })
                check(disk == original) { "cancelled refresh durably advanced A before B completed: $disk" }
                val restarted = state(index()); restarted.restore(current.selection)
                check(restarted.records == current.records && restarted.selection == current.selection)
            } finally { scope.cancel() }
        }
        test("manual startup, ordinary prepare, download and Apply perform zero source reads") {
            var books = listOf("old")
            var reads = 0; var freshReads = 0
            var disk = emptyMap<String, Set<String>>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { it.sorted() }, { b: String ->
                reads++; LibraryFacetRecord(b, emptyMap()) to true
            }, readFresh = { b: String -> freshReads++; LibraryFacetRecord(b, emptyMap()) to true },
                persistSelection = { disk = it }, manualRefreshOnly = true)
            try {
                state.restore(emptyMap())
                state.validateInBackground().join()
                state.prepare()
                books = listOf("old", "downloaded"); state.booksChanged(); yield()
                state.prepare()
                state.apply(mapOf("status" to setOf("Complete"))).join()
                check(reads == 0 && freshReads == 0) {
                    "manual-only paths performed source IO: reads=$reads fresh=$freshReads"
                }
                check(state.records.isEmpty() && !state.isFresh())
                check(state.pendingCount() == 2 && state.unknownCount() == 2 && state.needsRefresh())
                check(state.selection == disk && disk["status"] == setOf("Complete"))
            } finally { scope.cancel() }
        }
        test("manual emissions prune deleted choices but keep cached facets for edited books") {
            data class Book(val path: String, val revision: Int)
            var books = listOf(Book("keep", 1), Book("deleted", 1))
            val old = LibraryFacetRecord("keep", mapOf("tags" to setOf("Cached")))
            val removed = LibraryFacetRecord("deleted", mapOf("tags" to setOf("Deleted only")))
            var reads = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { rows -> rows.map { "${it.path}:${it.revision}" }.sorted() },
                { b: Book -> reads++; LibraryFacetRecord(b.path, emptyMap()) to true },
                loadCached = { mapOf("keep" to old, "deleted" to removed) }, identity = { it.path },
                manualRefreshOnly = true)
            try {
                state.restore(mapOf("tags" to setOf("Cached")))
                books = listOf(Book("keep", 2), Book("new", 1)); state.booksChanged()
                state.prepare(); state.apply(mapOf("tags" to setOf("Cached"))).join()
                check(state.records == mapOf("keep" to old)) { "deleted facets survived membership change" }
                check(state.indexedBooks == listOf(Book("keep", 2)))
                check(LibraryFilters.options(state.records.values, "tags") == mapOf("Cached" to 1))
                check(!state.isFresh() && reads == 0)
                check(state.pendingCount() == 2 && state.unknownCount() == 1)
                check(state.selection["tags"] == setOf("Cached"))
                books = emptyList(); state.booksChanged()
                check(state.records.isEmpty() && state.pendingCount() == 0 && state.isFresh() && reads == 0)
            } finally { scope.cancel() }
        }
        test("manual forced refresh rereads fresh data and is cancellable with active filters") {
            val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            var block = true; var reads = 0; var freshReads = 0; var progress = ""
            val old = LibraryFacetRecord("a", mapOf("status" to setOf("Complete")))
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it }, { b: String ->
                reads++; LibraryFacetRecord(b, emptyMap()) to true
            }, readFresh = { b: String ->
                freshReads++
                if (block) try { entered.complete(Unit); awaitCancellation() }
                    finally { cancelled.complete(Unit) }
                LibraryFacetRecord(b, mapOf("status" to setOf("Hiatus"))) to true
            }, loadCached = { mapOf("a" to old) }, onProgress = { progress = it }, manualRefreshOnly = true)
            try {
                state.restore(mapOf("status" to setOf("Complete")))
                val preview = launch { state.prepare(force = true) }; entered.await()
                check(progress.startsWith("Refreshing filter data"))
                preview.cancelAndJoin(); yield()
                check(cancelled.isCompleted && progress.isEmpty()) { "active selection pinned a cancelled manual refresh" }
                check(state.records["a"] == old && state.selection["status"] == setOf("Complete"))
                block = false; state.prepare(force = true)
                check(reads == 0 && freshReads == 2 && state.isFresh())
                check(state.records["a"]?.facets?.get("status") == setOf("Hiatus"))
            } finally { scope.cancel() }
        }
        test("manual restart retains revision-stale durable facets without source checks") {
            data class Book(val path: String, val revision: Int)
            val old = LibraryFacetRecord("edited", mapOf("status" to setOf("Complete")))
            val disk = linkedMapOf("edited" to LibraryFacetIndex.Entry("1", "1", old),
                "deleted" to LibraryFacetIndex.Entry("1", "1", LibraryFacetRecord("deleted", emptyMap())))
            var checks = 0; var parses = 0; var loads = 0
            val index = LibraryFacetIndex<Book>(identity = { it.path }, bookSignature = { it.revision.toString() },
                fileSignature = { checks++; "1" }, loadAll = { loads++; disk.toMap() },
                save = { disk[it.record.path] = it }, delete = { disk.remove(it); Unit }, extract = { b ->
                    parses++; LibraryFacetRecord(b.path, mapOf("status" to setOf("Hiatus"))) to true
                })
            val books = listOf(Book("edited", 2))
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { rows -> rows.map { "${it.path}:${it.revision}" } },
                read = { b: Book -> index.read(b) }, readFresh = { b: Book -> index.read(b, force = true) },
                loadCached = { index.cached(it, allowStale = true) }, identity = { it.path }, manualRefreshOnly = true,
                cachedCurrentPaths = { index.cached(it).keys })
            try {
                state.restore(mapOf("status" to setOf("Complete")))
                check(state.records["edited"] == old) { "restart discarded revision-stale cached facets" }
                check(!state.isFresh()) { "restoring changed revisions falsely declared cached data current" }
                state.validateInBackground().join(); state.prepare()
                state.apply(mapOf("status" to setOf("Complete"))).join()
                check(checks == 0 && parses == 0 && loads == 1)
                index.prune(books.mapTo(hashSetOf()) { it.path })
                check(disk.keys == setOf("edited") && state.indexedBooks == books)
                state.prepare(force = true)
                check(checks == 2 && parses == 1 && state.isFresh())
                check(state.records["edited"]?.facets?.get("status") == setOf("Hiatus"))
            } finally { scope.cancel() }
        }
        test("manual refresh handles concurrent add/delete/edit atomically without Apply reads") {
            var books = listOf("old", "deleted")
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val old = LibraryFacetRecord("old", mapOf("tags" to setOf("Cached")))
            var reads = 0; var freshReads = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { it.sorted() }, { _: String ->
                reads++; error("ordinary metadata reader must never run in manual mode")
            }, readFresh = { b: String ->
                if (++freshReads == 1) { entered.complete(Unit); release.await() }
                LibraryFacetRecord(b, mapOf("tags" to setOf("Fresh"))) to true
            }, loadCached = { mapOf("old" to old, "deleted" to LibraryFacetRecord("deleted", emptyMap())) },
                manualRefreshOnly = true)
            try {
                state.restore(mapOf("tags" to setOf("Cached")))
                val refresh = launch { state.prepare(force = true) }; entered.await()
                books = listOf("old", "edited", "downloaded"); state.booksChanged()
                state.prepare(); state.apply(mapOf("tags" to setOf("Fresh"))).join()
                check(state.records == mapOf("old" to old) && !refresh.isCompleted && reads == 0)
                release.complete(Unit); refresh.join()
                check(state.records.keys == books.toSet() && state.indexedBooks == books && state.isFresh())
                check(state.records.values.all { it.facets["tags"] == setOf("Fresh") })
                check(state.selection["tags"] == setOf("Fresh") && reads == 0)
            } finally { scope.cancel() }
        }
        test("manual failed refresh and failed persistence retain the last committed snapshot") {
            val old = LibraryFacetRecord("a", mapOf("status" to setOf("Complete")))
            var disk = mapOf("status" to setOf("Complete"))
            var saveFails = false; var message = ""; var sourceReads = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it }, { _: String ->
                error("ordinary source path")
            }, readFresh = { _: String -> sourceReads++; error("private source failure") },
                loadCached = { mapOf("a" to old) }, onError = { message = it },
                persistSelection = { if (saveFails) error("private disk error") else disk = it },
                manualRefreshOnly = true)
            try {
                state.restore(disk)
                var failed = false
                try { state.prepare(force = true) } catch (_: IllegalStateException) { failed = true }
                check(failed && state.records["a"] == old && state.selection == disk && state.needsRefresh())
                state.prepare(); check(sourceReads == 1)
                saveFails = true; state.apply(mapOf("status" to setOf("Hiatus"))).join()
                check(state.selection == disk && state.records["a"] == old && message.isNotBlank())
                check(!message.contains("private") && sourceReads == 1)
                saveFails = false; state.apply(emptyMap()).join()
                check(disk.isEmpty() && state.selection.isEmpty() && sourceReads == 1)
            } finally { scope.cancel() }
        }
        test("cancel during an accepted manual selection save publishes disk success before cancellation returns") {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var disk = mapOf("tags" to setOf("Old"))
            val state = LibraryFilterState(scope, { listOf("a") }, { it },
                { b: String -> LibraryFacetRecord(b, emptyMap()) to true },
                persistSelection = { entered.complete(Unit); release.await(); disk = it }, manualRefreshOnly = true)
            try {
                state.restore(disk)
                val saving = state.apply(mapOf("tags" to setOf("New"))); entered.await()
                saving.cancel(); release.complete(Unit); saving.join()
                check(state.selection == disk && disk["tags"] == setOf("New")) {
                    "accepted save changed disk but cancellation skipped the matching memory publication"
                }
            } finally { scope.cancel() }
        }
        test("manual selection persistence is ordered and restart never reads an EPUB") {
            var disk = emptyMap<String, Set<String>>()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var saves = 0; var reads = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            fun fresh() = LibraryFilterState(scope, { listOf("a") }, { it }, { b: String ->
                reads++; LibraryFacetRecord(b, emptyMap()) to true
            }, persistSelection = {
                if (++saves == 1) { entered.complete(Unit); release.await() }
                disk = it
            }, manualRefreshOnly = true)
            try {
                val state = fresh(); state.restore(emptyMap())
                val first = state.apply(mapOf("authors" to setOf("Alice"))); entered.await()
                val latest = state.apply(mapOf("authors" to setOf("Doe, Jane")))
                release.complete(Unit); first.join(); latest.join()
                check(disk == state.selection && disk["authors"] == setOf("Doe, Jane"))
                val restarted = fresh(); restarted.restore(disk); restarted.prepare()
                check(restarted.selection == disk && saves == 2 && reads == 0)
                restarted.apply(emptyMap()).join()
                check(disk.isEmpty() && restarted.selection.isEmpty() && reads == 0)
            } finally { scope.cancel() }
        }
        test("Apply rechecks freshness after a download while the dialog was open") {
            var books = listOf("old")
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { it.sorted() }, { b: String ->
                LibraryFacetRecord(b, mapOf("status" to setOf("Complete"))) to true
            })
            try {
                state.prepare()
                books = listOf("old", "new")
                state.booksChanged() // No filters yet: simulates an open category dialog.
                state.apply(mapOf("status" to setOf("Complete"))).join()
                check(state.selection["status"] == setOf("Complete"))
                check(state.indexedBooks == books && state.isFresh())
                check(state.records.keys == setOf("old", "new"))
            } finally { scope.cancel() }
        }
        test("scan restart on add/delete/edit publishes only the complete latest snapshot") {
            var books = listOf("old", "deleted")
            var read = 0
            var published = 0
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { it.sorted() }, { b: String ->
                read++
                if (read == 1) { entered.complete(Unit); release.await() }
                LibraryFacetRecord(b, mapOf("tags" to setOf(b))) to true
            }, onChanged = { published++ })
            try {
                val waiter = launch { state.prepare() }
                entered.await()
                check(state.records.isEmpty())
                books = listOf("edited", "new")
                state.booksChanged(); release.complete(Unit); waiter.join()
                check(state.indexedBooks == books && state.records.keys == books.toSet())
                check(published == 1 && state.isFresh())
            } finally { scope.cancel() }
        }
        test("cancelling the sole preview cancels partial work and reopening recovers") {
            val entered = CompletableDeferred<Unit>()
            var block = true
            var progress = ""
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it }, { b: String ->
                if (block) { entered.complete(Unit); awaitCancellation() }
                LibraryFacetRecord(b, emptyMap()) to true
            }, onProgress = { progress = it })
            try {
                val preview = launch { state.prepare() }
                entered.await(); preview.cancelAndJoin(); yield()
                check(state.records.isEmpty() && progress.isEmpty())
                block = false; state.prepare()
                check(state.records.keys == setOf("a") && state.isFresh())
            } finally { scope.cancel() }
        }
        test("dialog cancellation cannot stop applied-filter maintenance; old results stay intact") {
            var books = listOf("a")
            var block = false
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { it.sorted() }, { b: String ->
                if (block) { entered.complete(Unit); release.await() }
                LibraryFacetRecord(b, mapOf("status" to setOf("Complete"))) to true
            })
            try {
                state.apply(mapOf("status" to setOf("Complete"))).join()
                books = listOf("a", "b"); block = true; state.booksChanged()
                entered.await()
                val preview = launch { state.prepare() }; yield(); preview.cancelAndJoin()
                check(state.indexedBooks == listOf("a") && state.selection.isNotEmpty())
                release.complete(Unit)
                state.prepare()
                check(state.indexedBooks == books && state.records.size == 2)
            } finally { scope.cancel() }
        }
        test("a pending Apply survives cancelled dialog and a newer Apply wins") {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it }, { b: String ->
                entered.complete(Unit); release.await(); LibraryFacetRecord(b, emptyMap()) to true
            })
            try {
                val first = state.apply(mapOf("authors" to setOf("Alice")))
                entered.await()
                val preview = launch { state.prepare() }; yield(); preview.cancelAndJoin()
                val second = state.apply(mapOf("authors" to setOf("Bob")))
                release.complete(Unit); second.join(); first.join()
                check(state.selection["authors"] == setOf("Bob"))
            } finally { scope.cancel() }
        }
        test("clear filters during Apply prevents a late stale selection") {
            val entered = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it }, { _: String ->
                entered.complete(Unit); awaitCancellation()
            })
            try {
                val apply = state.apply(mapOf("tags" to setOf("Adventure")))
                entered.await(); state.apply(emptyMap()).join(); apply.join(); yield()
                check(state.selection.isEmpty() && state.records.isEmpty())
            } finally { scope.cancel() }
        }
        test("failed forced refresh retains applied filters and the prior complete index") {
            var fail = false
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it }, { b: String ->
                if (fail) error("simulated failure")
                LibraryFacetRecord(b, emptyMap()) to true
            })
            try {
                state.apply(mapOf("series" to setOf("X"))).join()
                fail = true
                try { state.prepare(force = true) } catch (_: IllegalStateException) { }
                check(state.indexedBooks == listOf("a") && state.selection["series"] == setOf("X"))
                check(state.records.size == 1)
            } finally { scope.cancel() }
        }
        test("empty library is a valid atomic refreshed snapshot") {
            var books = listOf("a")
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { it }, { b: String -> LibraryFacetRecord(b, emptyMap()) to true })
            try {
                state.prepare(); books = emptyList(); state.prepare(force = true)
                check(state.isFresh() && state.records.isEmpty() && state.indexedBooks.isEmpty())
            } finally { scope.cancel() }
        }
        test("rapid cancel/reopen waits for a cancelled blocking read to unwind") {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var active = 0; var maximum = 0; var reads = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it }, { b: String ->
                reads++; active++; maximum = maxOf(maximum, active)
                try {
                    if (reads == 1) withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    LibraryFacetRecord(b, emptyMap()) to true
                } finally { active-- }
            })
            try {
                val first = launch { state.prepare() }; entered.await(); first.cancel(); yield()
                val second = launch { state.prepare() }; yield()
                check(reads == 1 && active == 1)
                release.complete(Unit); first.join(); second.join()
                check(maximum == 1 && state.records.size == 1 && state.isFresh())
            } finally { scope.cancel() }
        }
        test("latest library change during pending Apply is included without another emission") {
            var books = listOf("a")
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var reads = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { it.sorted() }, { b: String ->
                if (++reads == 1) { entered.complete(Unit); release.await() }
                LibraryFacetRecord(b, mapOf("status" to setOf("Hiatus"))) to true
            })
            try {
                val apply = state.apply(mapOf("status" to setOf("Hiatus")))
                entered.await(); books = listOf("a", "downloaded"); state.booksChanged(); release.complete(Unit)
                apply.join()
                check(state.records.size == 2 && state.indexedBooks == books && state.isFresh())
                check(state.selection["status"] == setOf("Hiatus"))
            } finally { scope.cancel() }
        }
        test("one cancelled preview does not interrupt a second preview") {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var reads = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it }, { b: String ->
                reads++; entered.complete(Unit); release.await(); LibraryFacetRecord(b, emptyMap()) to true
            })
            try {
                val first = launch { state.prepare() }; entered.await()
                val second = launch { state.prepare() }; yield(); first.cancelAndJoin()
                release.complete(Unit); second.join()
                check(reads == 1 && state.isFresh())
            } finally { scope.cancel() }
        }
        test("failed Apply keeps old criteria/results and emits a visible-safe error") {
            var books = listOf("a"); var fail = false; var message = ""
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { it }, { b: String ->
                if (fail) error("private upstream error")
                LibraryFacetRecord(b, emptyMap()) to true
            }, onError = { message = it })
            try {
                state.apply(mapOf("authors" to setOf("Alice"))).join()
                books = listOf("a", "b"); fail = true
                state.apply(mapOf("authors" to setOf("Bob"))).join()
                check(state.selection["authors"] == setOf("Alice") && state.indexedBooks == listOf("a"))
                check(message.contains("Previous filters") && !message.contains("private"))
            } finally { scope.cancel() }
        }
        test("Apply and Clear persist before publication; restart restores exact committed choices") {
            var disk = emptyMap<String, Set<String>>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            fun fresh(): LibraryFilterState<String> = LibraryFilterState(scope, { listOf("a") }, { it },
                { b: String -> LibraryFacetRecord(b, emptyMap()) to true },
                persistSelection = { disk = it.mapValues { e -> e.value.toSet() } })
            try {
                val first = fresh()
                first.apply(mapOf("authors" to setOf("Doe, Jane"))).join()
                check(disk["authors"] == setOf("Doe, Jane"))
                val restarted = fresh(); restarted.apply(disk).join()
                check(restarted.selection == first.selection)
                restarted.apply(emptyMap()).join()
                check(disk.isEmpty() && restarted.selection.isEmpty())
            } finally { scope.cancel() }
        }
        test("persistence failure keeps old committed filters and reports safe error") {
            var fail = false; var message = ""
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it },
                { b: String -> LibraryFacetRecord(b, emptyMap()) to true },
                onError = { message = it }, persistSelection = { if (fail) error("disk unavailable") })
            try {
                state.apply(mapOf("tags" to setOf("A"))).join()
                fail = true; state.apply(mapOf("tags" to setOf("B"))).join()
                check(state.selection["tags"] == setOf("A") && message.isNotBlank())
                state.apply(emptyMap()).join()
                check(state.selection["tags"] == setOf("A"))
            } finally { scope.cancel() }
        }
        test("only explicit force uses fresh metadata reader; normal snapshots use cached path") {
            var cached = 0; var fresh = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it },
                { b: String -> cached++; LibraryFacetRecord(b, emptyMap()) to true },
                readFresh = { b: String -> fresh++; LibraryFacetRecord(b, emptyMap()) to true })
            try {
                state.prepare(); state.prepare()
                check(cached == 1 && fresh == 0)
                state.prepare(force = true)
                check(cached == 1 && fresh == 1)
            } finally { scope.cancel() }
        }
        test("warm restart publishes 3500 cached books before any source validation") {
            val books = (1..3500).map { "book-$it" }
            val cached = books.associateWith { LibraryFacetRecord(it, mapOf("status" to setOf("Complete"))) }
            var reads = 0; var bulkLoads = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { it.sorted() }, { _: String ->
                reads++; error("source IO must not gate cached startup")
            }, loadCached = { bulkLoads++; cached }, manualRefreshOnly = true)
            try {
                state.restore(mapOf("status" to setOf("Complete")))
                check(reads == 0 && bulkLoads == 1)
                check(state.indexedBooks.size == 3500 && state.records.size == 3500)
                check(state.selection["status"] == setOf("Complete") && state.isFresh())
            } finally { scope.cancel() }
        }
        test("one edited book and one addition do not reread unchanged books") {
            data class Book(val path: String, val revision: Int)
            var books = listOf(Book("a", 1), Book("b", 1), Book("deleted", 1))
            val cached = books.associate { it.path to LibraryFacetRecord(it.path, mapOf("status" to setOf("Complete"))) }
            val reads = mutableListOf<String>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { books }, { rows -> rows.map { "${it.path}:${it.revision}" }.sorted() },
                { b: Book -> reads.add(b.path); LibraryFacetRecord(b.path, emptyMap()) to true },
                loadCached = { cached }, identity = { it.path })
            try {
                state.restore(mapOf("status" to setOf("Complete")))
                books = listOf(Book("a", 1), Book("b", 2), Book("new", 1))
                state.booksChanged(); state.prepare()
                check(reads == listOf("b", "new")) { "unchanged book was reread: $reads" }
                check(state.records.keys == setOf("a", "b", "new") && state.isFresh())
            } finally { scope.cancel() }
        }
        test("external validation keeps cached results visible and does not block filter use") {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val old = LibraryFacetRecord("a", mapOf("status" to setOf("Complete")))
            var freshReads = 0
            val state = LibraryFilterState(scope, { listOf("a") }, { it }, { _: String ->
                entered.complete(Unit); release.await()
                LibraryFacetRecord("a", mapOf("status" to setOf("Hiatus"))) to true
            }, readFresh = { _: String -> freshReads++; error("validation must use fingerprint cache") },
                loadCached = { mapOf("a" to old) })
            try {
                state.restore(mapOf("status" to setOf("Complete")))
                val job = state.validateInBackground()
                entered.await()
                check(state.records["a"] == old && state.selection["status"] == setOf("Complete"))
                state.prepare() // Fresh cached index must not await background source checks.
                state.apply(mapOf("status" to setOf("Hiatus"))).join()
                check(state.selection["status"] == setOf("Hiatus") && !job.isCompleted)
                release.complete(Unit); job.join()
                check(state.records["a"]?.facets?.get("status") == setOf("Hiatus"))
                check(state.selection["status"] == setOf("Hiatus") && freshReads == 0)
            } finally { scope.cancel() }
        }
        test("Clear leaves startup validation running while cached filters remain usable") {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val old = LibraryFacetRecord("a", mapOf("status" to setOf("Complete")))
            var sourceStatus = "Complete"
            var disk = mapOf("status" to setOf("Complete"))
            var reads = 0
            val state = LibraryFilterState(scope, { listOf("a") }, { it }, { _: String ->
                reads++; entered.complete(Unit); release.await()
                LibraryFacetRecord("a", mapOf("status" to setOf(sourceStatus))) to true
            }, persistSelection = { disk = it }, loadCached = { mapOf("a" to old) })
            try {
                state.restore(disk)
                val validation = state.validateInBackground()
                entered.await()
                withTimeout(5_000) { state.prepare(); state.apply(emptyMap()).join() }
                check(state.selection.isEmpty() && disk.isEmpty() && !release.isCompleted)
                withTimeout(5_000) { state.prepare() } // Reopen before source IO finishes.
                check(state.isFresh() && state.records["a"] == old)
                sourceStatus = "Hiatus" // External edit, with no library-row change.
                release.complete(Unit); validation.join()
                check(state.records["a"]?.facets?.get("status") == setOf("Hiatus")) {
                    "Clear cancelled startup source validation and left cached metadata stale"
                }
                check(state.selection.isEmpty() && disk.isEmpty() && reads == 1)
                state.prepare()
            } finally { scope.cancel() }
        }
        test("a newer Clear during slow cache restore cannot resurrect saved criteria") {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a") }, { it },
                { b: String -> LibraryFacetRecord(b, emptyMap()) to true },
                loadCached = { entered.complete(Unit); release.await(); mapOf("a" to LibraryFacetRecord("a", emptyMap())) })
            try {
                val restore = launch { state.restore(mapOf("authors" to setOf("Old author"))) }
                entered.await(); state.apply(emptyMap()).join(); release.complete(Unit); restore.join()
                check(state.selection.isEmpty())
            } finally { scope.cancel() }
        }
        test("partial cache restoration prunes removed identities and reads only uncached books") {
            var reads = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val state = LibraryFilterState(scope, { listOf("a", "new") }, { it },
                { b: String -> reads++; LibraryFacetRecord(b, emptyMap()) to true },
                loadCached = { mapOf("a" to LibraryFacetRecord("a", emptyMap()), "removed" to LibraryFacetRecord("removed", emptyMap())) })
            try {
                state.restore(mapOf("tags" to setOf("Adventure")))
                check(state.indexedBooks == listOf("a") && state.records.keys == setOf("a") && !state.isFresh())
                state.prepare()
                check(reads == 1 && state.records.keys == setOf("a", "new") && state.isFresh())
            } finally { scope.cancel() }
        }
        println("$passed LibraryFilterState coroutine tests passed")
    }
}
