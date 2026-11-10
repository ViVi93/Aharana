package com.vivi.aharana

import java.io.File
import java.nio.file.Files
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

object SettingsDiagnosticsTest {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("settings-tail-").toFile()
        try {
            val current = File(root, "engine.jsonl")
            val old = File(root, "engine.jsonl.1")
            old.writeText((1..40).joinToString("\n", postfix = "\n") { "old-$it" })
            current.writeText((1..5).joinToString("\n", postfix = "\n") { "new-$it" })
            val result = SettingsDebugPreview.read(listOf(current, old))
            val expected = (16..40).map { "old-$it" } + (1..5).map { "new-$it" }
            check(result.text.lines() == expected) { "Preview must contain only newest 30 lines across rotations: ${result.text}" }
            check(current.readText().endsWith("new-5\n")) { "Preview changed the log" }
            println("PASS newest 30 lines across rotations; log unchanged")
            current.writeText("password=" + "PRIVATE_SECRET".repeat(8000) + "\nlatest-safe\n")
            val boundary = SettingsDebugPreview.read(listOf(current))
            check("PRIVATE_SECRET" !in boundary.text) { "Byte clipping lost the secret label but exposed its value" }
            check(boundary.text.endsWith("latest-safe"))
            println("PASS partial first line cannot expose a credential whose label fell outside the byte window")
            current.outputStream().use { out ->
                val block = ByteArray(1024 * 1024) { 'x'.code.toByte() }
                repeat(64) { out.write(block) }
                out.write("LATEST".toByteArray())
            }
            val huge = SettingsDebugPreview.read(listOf(current, old))
            check(huge.bytesRead <= 32 * 1024) { "Read beyond global byte budget: ${huge.bytesRead}" }
            check(huge.text.lines().size <= 30 && huge.text.lines().all { it.length <= 1024 })
            check(huge.text.endsWith("[Partial oversized log entry omitted]")) { "Unredactable partial entries must be omitted" }
            check(huge.truncated) { "Clipping must be visible to callers" }
            println("PASS 64 MiB single line: <=32 KiB read, <=1024 chars, unsafe partial entry omitted")
            current.bufferedWriter().use { out ->
                repeat(200_000) { out.write("event-$it ${"a".repeat(120)}\n") }
            }
            val many = SettingsDebugPreview.read(listOf(current, old))
            check(many.bytesRead <= 32 * 1024 && many.text.lines().size == 30)
            check(many.text.lines().first().startsWith("event-199970 "))
            check(many.text.lines().last().startsWith("event-199999 "))
            // Budget is global, even when each rotation contains only a few long lines.
            current.writeText("a".repeat(20_000) + "\n")
            old.writeText("b".repeat(20_000) + "\n")
            val rotations = SettingsDebugPreview.read(listOf(current, old))
            check(rotations.bytesRead == 32 * 1024)
            check(rotations.text.lines().all { it.length <= 1024 })
            current.writeText("αβ🙂\r\nlast-without-newline")
            check(SettingsDebugPreview.read(listOf(current)).text == "αβ🙂\nlast-without-newline")
            current.writeText("")
            check(SettingsDebugPreview.read(listOf(current, File(root, "missing"))).text.isEmpty())
            check(runCatching { SettingsDebugPreview.read(listOf(root)) }.isFailure) { "Read errors hidden" }
            println("PASS 200,000-line log, global rotation byte cap, UTF-8/CRLF, missing/empty files and IO errors")
            requestOwnership()
        } finally { root.deleteRecursively() }
    }

    private class Queue : AbstractExecutorService() {
        val pending = java.util.ArrayDeque<Runnable>()
        private var closed = false
        override fun execute(task: Runnable) { pending.add(task) }
        fun runNext() = pending.removeFirst().run()
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> { closed = true; return mutableListOf() }
        override fun isShutdown() = closed
        override fun isTerminated() = closed
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
    }

    private fun requestOwnership() {
        val work = Queue()
        val ui = Queue()
        val requests = SettingsRequests(work, ui)
        val published = mutableListOf<String>()
        requests.submit({ "old" }) { published.add(it.getOrThrow()) }
        work.runNext() // Old callback is already queued on UI before newer refresh.
        requests.submit({ "new" }) { published.add(it.getOrThrow()) }
        work.runNext()
        ui.runNext()
        ui.runNext()
        check(published == listOf("new")) { "A stale queued result overwrote a newer refresh" }
        requests.submit({ "cancelled" }) { published.add(it.getOrThrow()) }
        work.runNext()
        requests.cancel()
        ui.runNext()
        check(published == listOf("new")) { "Cancelled work published" }
        requests.submit({ "late-old" }) { published.add(it.getOrThrow()) }
        requests.submit({ "latest" }) { published.add(it.getOrThrow()) }
        work.runNext(); work.runNext()
        ui.pending.removeLast().run() // New delivery before old delivery.
        ui.runNext()
        check(published == listOf("new", "latest"))
        requests.submit({ error("failure") }) { published.add(it.exceptionOrNull()!!.message!!) }
        work.runNext(); ui.runNext()
        check(published.last() == "failure") { "Worker errors were hidden" }
        requests.submit({ "destroyed" }) { published.add(it.getOrThrow()) }
        work.runNext()
        requests.close()
        ui.runNext()
        check(published == listOf("new", "latest", "failure")) { "Destroyed owner published a callback" }
        println("PASS newer refresh, cancel, error delivery, owner destruction")

        val ownerThread = Thread.currentThread()
        val executor = Executors.newSingleThreadExecutor()
        val completed = CountDownLatch(1)
        val actual = SettingsRequests(executor, Executor { it.run() })
        actual.submit({ Thread.currentThread() !== ownerThread }) {
            check(it.getOrThrow()) { "Blocking construction ran on caller thread" }
            completed.countDown()
        }
        check(completed.await(5, TimeUnit.SECONDS))
        actual.close()
        println("PASS real executor runs blocking construction/work off caller thread")

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deliveries = java.util.concurrent.atomic.AtomicInteger()
        val stalledExecutor = Executors.newSingleThreadExecutor()
        val stalled = SettingsRequests(stalledExecutor, Executor { it.run() })
        stalled.submit({
            started.countDown()
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { /* like uninterruptible Python */ }
            }
            "late completion"
        }) { deliveries.incrementAndGet() }
        check(started.await(5, TimeUnit.SECONDS))
        stalled.close()
        release.countDown()
        // Wait for the real worker to finish, including synchronous delivery.
        check(stalledExecutor.awaitTermination(5, TimeUnit.SECONDS))
        check(deliveries.get() == 0)
        println("PASS uninterruptible work completing after owner close cannot publish")
    }
}
