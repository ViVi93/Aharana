package com.vivi.aharana

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** Dependency-free JVM suite; run main with the cached Kotlin compiler. */
object BulkQueuePolicyTest {
    private var passed = 0

    private fun test(name: String, block: () -> Unit) {
        block()
        passed++
        println("PASS $name")
    }

    // Reflection lets the first test report a missing feature as an assertion,
    // rather than mistaking an unresolved-reference compiler error for RED.
    private fun canonical(url: String): String {
        val type = try { Class.forName("com.vivi.aharana.BulkQueuePolicy") }
        catch (_: ClassNotFoundException) { throw AssertionError("BulkQueuePolicy canonical story matching is missing") }
        return type.getMethod("canonicalUrl", String::class.java)
            .invoke(type.getField("INSTANCE").get(null), url) as String
    }

    private suspend fun invokeEnqueue(
        coordinator: Any, urls: List<String>, mode: String,
        snapshot: suspend () -> BulkQueueSnapshot,
        download: suspend (String) -> Unit,
        update: suspend (BulkQueueBook) -> Unit
    ): Any = kotlin.coroutines.suspendCoroutine { continuation ->
        val result = coordinator.javaClass.methods.single { it.name == "enqueue" }
            .invoke(coordinator, urls, mode, snapshot, download, update, continuation)
        if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
            continuation.resumeWith(Result.success(result))
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        test("meaningful query story IDs remain distinct") {
            check(canonical("https://example.org/viewstory.php?sid=123") !=
                canonical("https://example.org/viewstory.php?sid=456"))
        }
        test("AO3 alias and work chapter URLs have one identity") {
            val work = canonical("https://archiveofourown.org/works/123")
            for (url in listOf(
                "http://www.archiveofourown.org/works/123/chapters/456?view_adult=true#main",
                "https://archiveofourown.com/works/123?view_full_work=true",
                "https://www.archiveofourown.net/works/123/chapters/789/",
                "https://ao3.org/works/123"
            )) check(canonical(url) == work) { url }
        }
        test("FFN mobile chapter URLs match their story") {
            check(canonical("http://m.fanfiction.net/s/123/12/A-Title?utm_source=share#end") ==
                canonical("https://www.fanfiction.net/s/123/1/Other-Title"))
        }
        test("tracking removal preserves query values and case sensitive paths") {
            check(canonical("HTTP://WWW.Example.org/Story/AbC?sid=X&utm_source=share&fbclid=z#part") ==
                canonical("https://example.org/Story/AbC?sid=X"))
            check(canonical("https://example.org/Story/AbC?sid=X") != canonical("https://example.org/story/abc?sid=X"))
            check(canonical("https://example.org/Story?sid=X") != canonical("https://example.org/Story?sid=x"))
            check(canonical("https://example.org/Story?sid=1&chapter=2") != canonical("https://example.org/Story?sid=1&chapter=3"))
            check(canonical("https://example.org:8443/Story") != canonical("https://example.org/Story"))
        }
        test("preview identifies an available library book by canonical URL") {
            val snapshotType = try { Class.forName("com.vivi.aharana.BulkQueueSnapshot") }
            catch (_: ClassNotFoundException) { throw AssertionError("BulkQueueSnapshot library-aware preview is missing") }
            val bookType = Class.forName("com.vivi.aharana.BulkQueueBook")
            val book = bookType.constructors.single().newInstance(7L, "https://archiveofourown.org/works/123", "/book.epub", true)
            val snapshot = snapshotType.constructors.single().newInstance(listOf(book), emptyList<Any>())
            val url = "http://archiveofourown.net/works/123/chapters/999"
            val preview = snapshotType.getMethod("preview", List::class.java).invoke(snapshot, listOf(url, "https://example.org/new"))
            check(preview == mapOf(url to "library", "https://example.org/new" to "new"))
        }
        test("active write jobs override library and new statuses") {
            val url = "https://archiveofourown.org/works/123"
            val book = BulkQueueBook(7, url, "/book.epub", true)
            for (type in listOf("download", "update", "force_download")) {
                for (status in listOf("queued", "waiting", "running", "preparing", "downloading", "processing", "copying", "retrying")) {
                    val snapshot = BulkQueueSnapshot(listOf(book), listOf(BulkQueueJob(type, status, url, 0, null)))
                    check(snapshot.preview(listOf(url))[url] == "queued") { "$type/$status" }
                }
            }
        }
        test("update jobs without inputUrl resolve bookId or inputPath") {
            val url = "https://example.org/story?sid=7"
            val book = BulkQueueBook(7, url, "content://books/7", false)
            for (job in listOf(
                BulkQueueJob("update", "waiting", null, 7, null),
                BulkQueueJob("force_download", "processing", null, 0, "content://books/7")
            )) check(BulkQueueSnapshot(listOf(book), listOf(job)).preview(listOf(url))[url] == "queued")
        }
        test("terminal rows and metadata jobs never block story operations") {
            val url = "https://example.org/story?sid=7"
            for (job in listOf("failed", "cancelled", "success", "completed").map {
                BulkQueueJob("download", it, url, 0, null)
            } + BulkQueueJob("metadata", "running", url, 0, null)) {
                check(BulkQueueSnapshot(emptyList(), listOf(job)).preview(listOf(url))[url] == "new")
            }
            check(BulkQueueSnapshot(listOf(BulkQueueBook(7, url, "/missing.epub", false)), emptyList())
                .preview(listOf(url))[url] == "new")
        }
        test("new mode downloads only missing nonqueued stories") {
            kotlinx.coroutines.runBlocking {
                val type = try { Class.forName("com.vivi.aharana.BulkQueueCoordinator") }
                catch (_: ClassNotFoundException) { throw AssertionError("BulkQueueCoordinator enqueue is missing") }
                val newUrl = "https://example.org/new"
                val heldUrl = "https://example.org/held"
                val queuedUrl = "https://example.org/queued"
                val snapshot = BulkQueueSnapshot(listOf(BulkQueueBook(7, heldUrl, "/held.epub", true)),
                    listOf(BulkQueueJob("download", "queued", queuedUrl, 0, null)))
                val downloads = mutableListOf<String>()
                val updates = mutableListOf<BulkQueueBook>()
                val result = invokeEnqueue(type.getDeclaredConstructor().newInstance(), listOf(newUrl, heldUrl, queuedUrl), "new",
                    { snapshot }, { downloads.add(it); Unit }, { updates.add(it); Unit })
                check(downloads == listOf(newUrl))
                check(updates.isEmpty())
                check(result.javaClass.getMethod("getDownloads").invoke(result) == 1)
                check(result.javaClass.getMethod("getSkipped").invoke(result) == 2)
                check(result.javaClass.getMethod("getFailed").invoke(result) == 0)
            }
        }
        test("update mode queues held books only using book identity and stored path") {
            kotlinx.coroutines.runBlocking {
                val held = BulkQueueBook(7, "https://example.org/held", "content://books/7", true)
                val queued = BulkQueueBook(8, "https://example.org/queued", "/queued.epub", true)
                val snapshot = BulkQueueSnapshot(listOf(held, queued), listOf(BulkQueueJob("update", "waiting", null, 8, null)))
                val downloads = mutableListOf<String>()
                val updates = mutableListOf<BulkQueueBook>()
                val result = BulkQueueCoordinator().enqueue(listOf(held.url!!, queued.url!!, "https://example.org/new"), "update",
                    { snapshot }, { downloads.add(it); Unit }, { updates.add(it); Unit })
                check(downloads.isEmpty())
                check(updates == listOf(held))
                check(result == BulkQueueResult(0, 1, 2, 0))
            }
        }
        test("smart mode downloads missing and updates held while queued are skipped") {
            kotlinx.coroutines.runBlocking {
                val held = BulkQueueBook(7, "https://example.org/held", "/held.epub", true)
                val queued = "https://example.org/queued"
                val snapshot = BulkQueueSnapshot(listOf(held), listOf(BulkQueueJob("download", "running", queued, 0, null)))
                val downloads = mutableListOf<String>()
                val updates = mutableListOf<BulkQueueBook>()
                val result = BulkQueueCoordinator().enqueue(listOf(held.url!!, queued, "https://example.org/new"), "smart",
                    { snapshot }, { downloads.add(it); Unit }, { updates.add(it); Unit })
                check(downloads == listOf("https://example.org/new"))
                check(updates == listOf(held))
                check(result == BulkQueueResult(1, 1, 1, 0))
            }
        }
        test("duplicates count as skipped and never enqueue a story twice") {
            kotlinx.coroutines.runBlocking {
                val urls = listOf("https://archiveofourown.org/works/123", "http://archiveofourown.net/works/123/chapters/9", "https://archiveofourown.org/works/123")
                var calls = 0
                val result = BulkQueueCoordinator().enqueue(urls, "smart", { BulkQueueSnapshot(emptyList(), emptyList()) }, { calls++ }, { error("unexpected update") })
                check(calls == 1)
                check(result == BulkQueueResult(1, 0, 2, 0))
            }
        }
        test("item failures are counted without aborting the remaining selection") {
            kotlinx.coroutines.runBlocking {
                val calls = mutableListOf<String>()
                val result = BulkQueueCoordinator().enqueue(listOf("https://example.org/fail", "https://example.org/good"), "new",
                    { BulkQueueSnapshot(emptyList(), emptyList()) }, { if (it.endsWith("fail")) throw java.io.IOException("submission failed") else calls.add(it); Unit }, { error("unexpected update") })
                check(calls == listOf("https://example.org/good"))
                check(result == BulkQueueResult(1, 0, 0, 1))
            }
        }
        test("concurrent repository coordinators snapshot and submit atomically") {
            kotlinx.coroutines.runBlocking {
                val url = "https://example.org/story"
                val jobs = mutableListOf<BulkQueueJob>()
                var calls = 0
                val results = List(2) {
                    async {
                        BulkQueueCoordinator().enqueue(listOf(url), "smart", {
                            val snapshot = BulkQueueSnapshot(emptyList(), jobs.toList())
                            kotlinx.coroutines.delay(20)
                            snapshot
                        }, {
                            kotlinx.coroutines.delay(20)
                            calls++
                            jobs.add(BulkQueueJob("download", "queued", it, 0, null))
                        }, { error("unexpected update") })
                    }
                }.awaitAll()
                check(calls == 1) { "double submission: $calls" }
                check(results.sumOf { it.downloads } == 1 && results.sumOf { it.skipped } == 1)
            }
        }
        test("invalid URLs fail individually without poisoning snapshots") {
            kotlinx.coroutines.runBlocking {
                val snapshot = BulkQueueSnapshot(listOf(BulkQueueBook(99, "not a URL", "/bad.epub", true)), emptyList())
                val urls = listOf("not a URL", "file:///local.epub", "https://example.org/good")
                check(snapshot.preview(urls).keys == urls.toSet())
                var calls = 0
                val result = BulkQueueCoordinator().enqueue(urls, "new", { snapshot }, { calls++ }, { error("unexpected update") })
                check(calls == 1)
                check(result == BulkQueueResult(1, 0, 0, 2))
            }
        }
        test("storage availability probes content URIs without copying and rejects inaccessible books") {
            val type = try { Class.forName("com.vivi.aharana.BulkQueueStorage") }
            catch (_: ClassNotFoundException) { throw AssertionError("BulkQueueStorage availability probe is missing") }
            val method = type.getMethod("isAvailable", String::class.java, kotlin.jvm.functions.Function1::class.java)
            val instance = type.getField("INSTANCE").get(null)
            var probes = 0
            val readable: (String) -> Boolean = { probes++; it == "content://books/readable" }
            check(method.invoke(instance, "content://books/readable", readable) == true)
            check(method.invoke(instance, "content://books/missing", readable) == false)
            val denied: (String) -> Boolean = { throw SecurityException("revoked permission") }
            check(method.invoke(instance, "content://books/denied", denied) == false)
            val file = java.io.File.createTempFile("BulkQueue", ".epub", java.io.File(System.getenv("TMPDIR")))
            try {
                check(method.invoke(instance, file.path, readable) == false)
                file.writeText("book")
                check(method.invoke(instance, file.path, readable) == true)
            } finally { file.delete() }
            check(probes == 2)
        }
        test("metadata recovery retries interrupted submissions but not successful or live work") {
            val type = try { Class.forName("com.vivi.aharana.BulkQueueMetadataPolicy") }
            catch (_: ClassNotFoundException) { throw AssertionError("Recoverable metadata policy is missing") }
            val method = type.getMethod("needsSubmission", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            val policy = type.getField("INSTANCE").get(null)
            check(method.invoke(policy, false, false) == true)
            check(method.invoke(policy, true, false) == false)
            check(method.invoke(policy, false, true) == false)
            check(method.invoke(policy, true, true) == false)
        }
        println("BulkQueue: $passed tests passed")
    }
}
