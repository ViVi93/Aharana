package com.vivi.aharana

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read-only review harness for the concurrently supplied LibraryFacetDiskStore.
 * No mocks, Android, network, source edits, timing sleeps, or shared fixture directories.
 * Contract violations remain failing tests so the owner can reconcile them explicitly.
 */
object LibraryFilterDiskStoreTest {
    private var passed = 0
    private val failures = mutableListOf<String>()

    @JvmStatic fun main(args: Array<String>) {
        test("cold miss does not create directories") { directory ->
            val store = LibraryFacetDiskStore(directory)
            check(store.read("missing.epub", "v1") == null)
            check(store.readSelection().isEmpty())
            check(!directory.exists())
        }
        test("exact Unicode record survives reopening") { directory ->
            val path = "content://library/日本語/📖, One.epub"
            val record = LibraryFacetRecord(path, linkedMapOf(
                "authors" to linkedSetOf("Doe, Jane", "é", "e\u0301", "Case", "case", "中文 📚"),
                "tags" to linkedSetOf("", " ", "line\nbreak", "zero\u0000inside"),
                "languages" to emptySet()))
            LibraryFacetDiskStore(directory).write(path, "fp\u0000é📖", record, true)
            check(LibraryFacetDiskStore(directory).read(path, "fp\u0000é📖") == (record to true))
        }
        test("changed fingerprints miss without destroying previous entry") { directory ->
            val record = record("book.epub", "Jane")
            val store = LibraryFacetDiskStore(directory)
            store.write(record.path, "mtime=1:size=4:author=Jane:chapters=2", record, true)
            for (fp in listOf("mtime=2:size=4:author=Jane:chapters=2", "mtime=1:size=5:author=Jane:chapters=2",
                    "mtime=1:size=4:author=Jack:chapters=2", "mtime=1:size=4:author=Jane:chapters=3")) {
                check(store.read(record.path, fp) == null)
            }
            check(store.read(record.path, "mtime=1:size=4:author=Jane:chapters=2") == (record to true))
        }
        test("opaque paths use isolated SHA256 filenames") { directory ->
            val paths = listOf("../library.epub", "/outside/book.epub", "content://tree/id:book", "Case", "case")
            val store = LibraryFacetDiskStore(directory)
            paths.forEach { store.write(it, "fp", record(it, it), true) }
            check(directory.listFiles()!!.map { it.name }.toSet() == paths.map { hash(it) + ".facet" }.toSet())
            paths.forEach { check(store.read(it, "fp") == (record(it, it) to true)) }
            check(directory.parentFile.listFiles()!!.single() == directory)
        }
        test("record-path mismatch is rejected before replacement") { directory ->
            val store = LibraryFacetDiskStore(directory)
            val old = record("old.epub", "Jane")
            store.write(old.path, "fp", old, true)
            expectIOException { store.write(old.path, "new", record("other.epub", "Jack"), true) }
            check(store.read(old.path, "fp") == (old to true))
        }
        test("all API methods synchronize shared-instance access") { _ ->
            for (name in listOf("read", "write", "readSelection", "writeSelection")) {
                val method = LibraryFacetDiskStore::class.java.declaredMethods.single { it.name == name }
                check(Modifier.isSynchronized(method.modifiers)) { "$name must synchronize" }
            }
        }
        test("truncated unsupported wrong-kind and trailing files miss") { directory ->
            val store = LibraryFacetDiskStore(directory)
            val originalRecord = record("book.epub", "Jane")
            store.write(originalRecord.path, "fp", originalRecord, true)
            val file = directory.listFiles()!!.single()
            val original = file.readBytes()
            val invalid = mutableListOf(byteArrayOf(), byteArrayOf(1, 2, 3), original + byteArrayOf(1))
            invalid += (0 until original.size).map { original.copyOf(it) }
            invalid += original.copyOf().also { it[0] = 0 }
            invalid += original.copyOf().also { it[7] = 127 } // schema version
            invalid += original.copyOf().also { it[11] = 2 } // selection instead of record
            invalid.forEach { bytes ->
                file.writeBytes(bytes)
                check(store.read(originalRecord.path, "fp") == null) { "malformed record must miss" }
            }
            file.writeBytes(original)
            check(store.read(originalRecord.path, "fp") == (originalRecord to true))
        }
        test("payload bit-flip corruption is detected") { directory ->
            val store = LibraryFacetDiskStore(directory)
            store.write("book.epub", "fp", record("book.epub", "Jane"), true)
            val file = directory.listFiles()!!.single()
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            check(store.read("book.epub", "fp") == null) { "bit-flipped author was accepted as valid cached metadata" }
        }
        test("hostile read sizes counts duplicate keys and UTF8 are bounded") { directory ->
            val store = LibraryFacetDiskStore(directory)
            store.write("book.epub", "fp", record("book.epub", "Jane"), true)
            val file = directory.listFiles()!!.single()
            val mapPayloads = listOf<(DataOutputStream) -> Unit>(
                { it.writeInt(-1) }, { it.writeInt(4097) },
                { it.writeInt(1); string(it, "authors"); it.writeInt(-1) },
                { it.writeInt(1); string(it, "authors"); it.writeInt(65537) },
                { it.writeInt(1); it.writeInt(1_048_577) },
                { it.writeInt(1); it.writeInt(Int.MAX_VALUE) },
                { it.writeInt(1); it.writeInt(1); it.writeByte(0xff); it.writeInt(0) },
                { it.writeInt(2); repeat(2) { _ -> string(it, "authors"); it.writeInt(0) } },
                { it.writeInt(1); string(it, "authors"); it.writeInt(2); string(it, "Jane"); string(it, "Jane") })
            mapPayloads.forEach { map ->
                file.writeBytes(envelope(1) { out ->
                    string(out, "book.epub"); string(out, "fp"); out.writeByte(1); map(out)
                })
                check(store.read("book.epub", "fp") == null)
            }
            RandomAccessFile(file, "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
            check(store.read("book.epub", "fp") == null)
        }
        test("large Unicode string survives but bounded failed writes retain old record") { directory ->
            val store = LibraryFacetDiskStore(directory)
            val large = record("book.epub", "日本語📖".repeat(10_000))
            store.write(large.path, "old", large, true)
            check(LibraryFacetDiskStore(directory).read(large.path, "old") == (large to true))
            val invalid = listOf(
                mapOf("authors" to setOf("x".repeat(1_048_577))),
                (0..4096).associate { "key$it" to emptySet<String>() },
                mapOf("tags" to (0..65536).map { "tag$it" }.toSet()),
                mapOf("tags" to (0 until 17).map { "x".repeat(1_000_000) + it }.toSet()))
            invalid.forEach { facets ->
                expectIOException { store.write(large.path, "new", LibraryFacetRecord(large.path, facets), true) }
                check(store.read(large.path, "old") == (large to true))
                check(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
            }
        }
        test("selection survives exact reload and durable clear") { directory ->
            val selection = linkedMapOf("authors" to linkedSetOf("Doe, Jane", "é", "e\u0301", "Case", "case"),
                "tags" to setOf("中文 📚", "line\nbreak", LibraryFilters.MISSING))
            LibraryFacetDiskStore(directory).writeSelection(selection)
            check(LibraryFacetDiskStore(directory).readSelection() == selection)
            LibraryFacetDiskStore(directory).writeSelection(emptyMap())
            check(LibraryFacetDiskStore(directory).readSelection().isEmpty())
        }
        test("selection rejects invalid categories and empty values on write") { directory ->
            val store = LibraryFacetDiskStore(directory)
            store.writeSelection(mapOf("authors" to setOf("Jane", ""), "not-a-category" to setOf("x"),
                "tags" to emptySet()))
            check(store.readSelection() == mapOf("authors" to setOf("Jane"))) {
                "selection persisted unknown categories, empty values, or inactive empty sets"
            }
        }
        test("selection validates categories and values when reading existing bytes") { directory ->
            check(directory.mkdirs())
            File(directory, "selection.bin").writeBytes(envelope(2, version = 1) { out ->
                out.writeInt(2)
                string(out, "authors"); out.writeInt(2); string(out, "Jane"); string(out, "")
                string(out, "not-a-category"); out.writeInt(1); string(out, "x")
            })
            check(LibraryFacetDiskStore(directory).readSelection() == mapOf("authors" to setOf("Jane"))) {
                "readSelection returned invalid persisted criteria"
            }
        }
        test("corrupt selection is empty and never destroys facet entries") { directory ->
            val store = LibraryFacetDiskStore(directory)
            val book = record("book.epub", "Jane")
            store.write(book.path, "fp", book, true)
            store.writeSelection(mapOf("authors" to setOf("Jane")))
            val file = File(directory, "selection.bin")
            val original = file.readBytes()
            for (bytes in listOf(byteArrayOf(), original.copyOf(original.size / 2),
                    original.copyOf().also { it[7] = 127 }, original + byteArrayOf(0))) {
                file.writeBytes(bytes)
                check(store.readSelection().isEmpty())
                check(store.read(book.path, "fp") == (book to true))
            }
            RandomAccessFile(file, "rw").use { it.setLength(2L * 1024 * 1024 + 1) }
            check(store.readSelection().isEmpty())
        }
        test("selection oversized write retains previous criteria") { directory ->
            val store = LibraryFacetDiskStore(directory)
            val selection = mapOf("authors" to setOf("Jane"))
            store.writeSelection(selection)
            expectIOException { store.writeSelection(mapOf("tags" to
                (0 until 3).map { "x".repeat(1_000_000) + it }.toSet())) }
            check(store.readSelection() == selection)
            check(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
        }
        test("staging remnants are never published or consumed") { directory ->
            val store = LibraryFacetDiskStore(directory)
            val book = record("book.epub", "Jane")
            store.write(book.path, "fp", book, true)
            File(directory, "facet-interrupted.tmp").writeBytes(byteArrayOf(0, 1, 2))
            check(LibraryFacetDiskStore(directory).read(book.path, "fp") == (book to true))
            check(LibraryFacetDiskStore(directory).read("other.epub", "fp") == null)
        }
        test("write failures surface without altering a source file") { directory ->
            val source = File(directory.parentFile, "book.epub").also { it.writeText("source must stay untouched") }
            val blocker = File(directory.parentFile, "blocked").also { it.writeText("not a directory") }
            val store = LibraryFacetDiskStore(blocker)
            expectIOException { store.write(source.absolutePath, "fp", record(source.absolutePath, "Jane"), true) }
            expectIOException { store.writeSelection(mapOf("authors" to setOf("Jane"))) }
            check(store.read(source.absolutePath, "fp") == null)
            check(source.readText() == "source must stay untouched")
            check(blocker.readText() == "not a directory")
        }
        test("independent concurrent readers see only whole old or new records") { directory ->
            val path = "concurrent.epub"
            val old = record(path, "old-" + "é".repeat(8192))
            val new = record(path, "new-" + "📚".repeat(4096))
            LibraryFacetDiskStore(directory).write(path, "fp", old, true)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val writer = pool.submit {
                    start.await()
                    val store = LibraryFacetDiskStore(directory)
                    repeat(250) { store.write(path, "fp", if (it % 2 == 0) new else old, true) }
                }
                val readers = (0 until 3).map {
                    pool.submit {
                        start.await()
                        val store = LibraryFacetDiskStore(directory)
                        repeat(1000) {
                            val value = store.read(path, "fp")
                            check(value == (old to true) || value == (new to true)) { "partial or absent published record" }
                        }
                    }
                }
                start.countDown()
                writer.get(60, TimeUnit.SECONDS)
                readers.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
                check(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
            check(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
        }
        test("3500 books reopen with zero reparses and one changed fingerprint reparses once") { directory ->
            var parses = 0
            val paths = (0 until 3500).map { "content://tree/book-$it.epub" }
            fun scan(store: LibraryFacetDiskStore, changed: Int = -1) {
                paths.forEachIndexed { index, path ->
                    val fp = if (index == changed) "changed" else "original"
                    val cached = store.read(path, fp)
                    val result = cached ?: (record(path, "Author, $index") to true).also {
                        parses++
                        store.write(path, fp, it.first, it.second)
                    }
                    check(result == (record(path, "Author, $index") to true))
                }
            }
            scan(LibraryFacetDiskStore(directory))
            check(parses == 3500)
            parses = 0
            scan(LibraryFacetDiskStore(directory))
            check(parses == 0) { "warm restart reparsed $parses books" }
            scan(LibraryFacetDiskStore(directory), 1729)
            check(parses == 1) { "one changed book required $parses reparses" }
            check(directory.listFiles()!!.size == 3500)
            println("METRIC: books=3500 cold_parses=3500 warm_parses=0 one_changed_parses=$parses")
        }
        test("cancelled partial scan checkpoints reuse only completed entries") { directory ->
            val paths = (0 until 120).map { "book-$it.epub" }
            val first = LibraryFacetDiskStore(directory)
            paths.take(37).forEach { first.write(it, "fp", record(it, "Jane"), true) }
            var parses = 0
            val resumed = LibraryFacetDiskStore(directory)
            paths.forEach { path ->
                if (resumed.read(path, "fp") == null) {
                    parses++
                    resumed.write(path, "fp", record(path, "Jane"), true)
                }
            }
            check(parses == 83)
            check(paths.all { LibraryFacetDiskStore(directory).read(it, "fp") == (record(it, "Jane") to true) })
            println("METRIC: checkpoint_saved=37 resumed_parses=$parses total=120")
        }
        println("RESULT: passed=$passed failed=${failures.size} total=${passed + failures.size}")
        failures.forEach { System.err.println("FINDING: $it") }
        check(failures.isEmpty()) { "${failures.size} disk-store contract violation(s); production was not modified" }
    }

    private fun record(path: String, author: String) = LibraryFacetRecord(path, mapOf("authors" to setOf(author)))
    private fun hash(path: String) = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun envelope(kind: Int, version: Int = 2, body: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeInt(0x4c464453); it.writeInt(version); it.writeInt(kind); body(it)
        }
        val content = buffer.toByteArray()
        return if (version == 2) content + MessageDigest.getInstance("SHA-256").digest(content) else content
    }

    private fun string(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size); output.write(bytes)
    }

    private fun expectIOException(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        error("expected IOException")
    }

    private fun test(name: String, block: (File) -> Unit) {
        val root = Files.createTempDirectory("library-cache-").toFile()
        try {
            block(File(root, "private-cache"))
            passed++
            println("PASS: $name")
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            failures.add("$name: ${error.message}")
            println("FAIL: $name: ${error.message}")
        } finally {
            check(root.deleteRecursively()) { "failed to clean test directory" }
        }
    }
}
