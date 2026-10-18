package com.vivi.aharana

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Real-filesystem JVM regression tests; the runner sets java.io.tmpdir to TMPDIR. */
object LibraryFacetDiskStoreTest {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("facet-disk-tests-").toFile()
        var passed = 0
        fun test(name: String, block: (File) -> Unit) {
            if (args.isNotEmpty() && args.none { name.contains(it) }) return
            val directory = File(root, "case-${passed + 1}")
            try {
                block(directory)
                passed++
            } catch (failure: Throwable) {
                throw AssertionError("LibraryFacetDiskStore: $name", failure)
            }
        }
        try {
            test("first write survives reopen") { directory ->
                val record = row("/library/a.epub")
                LibraryFacetDiskStore(directory).write(record.path, "size:mtime", record, true)
                check(LibraryFacetDiskStore(directory).read(record.path, "size:mtime") == Pair(record, true))
            }
            test("every bit-flipped byte of a framed record is rejected") { directory ->
                val store = LibraryFacetDiskStore(directory)
                store.write("a", "1", row("a"), true)
                val file = entries(directory).single()
                val original = file.readBytes()
                for (offset in original.indices) {
                    val corrupt = original.copyOf()
                    corrupt[offset] = (corrupt[offset].toInt() xor 1).toByte()
                    file.writeBytes(corrupt)
                    check(store.read("a", "1") == null) { "Accepted changed byte $offset" }
                }
            }
            test("unchecked v1 facet records miss without deleting the file") { directory ->
                val store = LibraryFacetDiskStore(directory)
                store.write("a", "1", row("a"), true)
                val file = entries(directory).single()
                val bytes = ByteArrayOutputStream()
                DataOutputStream(bytes).use {
                    it.writeInt(0x4c464453); it.writeInt(1); it.writeInt(1)
                    it.string("a"); it.string("1"); it.writeByte(1); it.writeInt(0)
                }
                file.writeBytes(bytes.toByteArray())
                check(store.read("a", "1") == null)
                check(file.readBytes().contentEquals(bytes.toByteArray()))
            }
            test("missing directory is a miss without creating it") { directory ->
                val store = LibraryFacetDiskStore(directory)
                check(store.read("absent", "f") == null)
                check(store.readSelection().isEmpty())
                check(!directory.exists())
            }
            test("fingerprint mismatch does not destroy matching entry") { directory ->
                val store = LibraryFacetDiskStore(directory)
                val record = row("a")
                store.write("a", "original", record, true)
                check(store.read("a", "changed") == null)
                check(store.read("a", "original") == Pair(record, true))
            }
            test("changing one book preserves unchanged books") { directory ->
                val store = LibraryFacetDiskStore(directory)
                val a = row("a")
                val b = row("b")
                store.write("a", "1", a, true)
                store.write("b", "1", b, true)
                val unchangedFile = entries(directory).associateBy { it.name }.mapValues { it.value.readBytes() }
                val changed = row("a", setOf("Updated"))
                store.write("a", "2", changed, true)
                val reopened = LibraryFacetDiskStore(directory)
                check(reopened.read("a", "1") == null)
                check(reopened.read("a", "2") == Pair(changed, true))
                check(reopened.read("b", "1") == Pair(b, true))
                check(entries(directory).count { unchangedFile[it.name]!!.contentEquals(it.readBytes()) } == 1)
            }
            test("exact keys values unicode commas and empty sets") { directory ->
                val record = LibraryFacetRecord("content://provider/书.epub", linkedMapOf(
                    "authors" to linkedSetOf("Doe, Jane", "Alice", "alice", " 雪 "),
                    "tags" to linkedSetOf("A, B", "a, b", "\u0000", "line\nbreak", ""),
                    "Custom KEY" to emptySet()))
                val store = LibraryFacetDiskStore(directory)
                store.write(record.path, "", record, true)
                check(LibraryFacetDiskStore(directory).read(record.path, "") == Pair(record, true))
            }
            test("readability remains distinct from missing metadata") { directory ->
                val record = LibraryFacetRecord("a", emptyMap())
                val store = LibraryFacetDiskStore(directory)
                store.write("a", "1", record, false)
                check(LibraryFacetDiskStore(directory).read("a", "1") == Pair(record, false))
                store.write("a", "1", record, true)
                check(store.read("a", "1") == Pair(record, true))
            }
            test("long UTF8 strings do not use writeUTF") { directory ->
                val record = row("a", setOf("雪".repeat(100_000)))
                LibraryFacetDiskStore(directory).write("a", "1", record, true)
                check(LibraryFacetDiskStore(directory).read("a", "1") == Pair(record, true))
            }
            test("selection write drops unknown categories blank values and inactive sets") { directory ->
                val store = LibraryFacetDiskStore(directory)
                val selection = LibraryFilters.categories.keys.associateWith { linkedSetOf(" 雪 ", LibraryFilters.MISSING) }
                store.writeSelection(selection + mapOf("authors" to setOf("Jane", "", " ", "\t\n"),
                    "tags" to emptySet(), "unknown" to setOf("x")))
                check(store.readSelection() == selection - "tags" + ("authors" to setOf("Jane")))
            }
            test("selection persists exact multi-authors independently of records") { directory ->
                val selection = linkedMapOf("authors" to setOf("Doe, Jane", "Alice", "alice"),
                    "tags" to setOf("A, B", "雪"))
                val store = LibraryFacetDiskStore(directory)
                store.writeSelection(selection)
                store.write("a", "1", row("a"), true)
                check(LibraryFacetDiskStore(directory).readSelection() == selection)
            }
            test("selection read sanitizes current and legacy criteria without losing valid values") { directory ->
                check(directory.mkdirs())
                for (version in listOf(1, 2)) {
                    File(directory, "selection.bin").writeBytes(envelope(2, version) {
                        writeInt(4)
                        string("authors"); writeInt(5)
                        string("Jane"); string(" 雪 "); string(""); string(" "); string("\t\n")
                        string("tags"); writeInt(0)
                        string("unknown"); writeInt(1); string("x")
                        string("series"); writeInt(1); string(LibraryFilters.MISSING)
                    })
                    check(LibraryFacetDiskStore(directory).readSelection() == mapOf(
                        "authors" to setOf("Jane", " 雪 "), "series" to setOf(LibraryFilters.MISSING)))
                }
            }
            test("valid v1 selection survives reopen and next save upgrades framing") { directory ->
                check(directory.mkdirs())
                val file = File(directory, "selection.bin")
                val legacy = envelope(2, 1) {
                    writeInt(2)
                    string("authors"); writeInt(2); string("Doe, Jane"); string(" 雪 ")
                    string("tags"); writeInt(1); string(LibraryFilters.MISSING)
                }
                file.writeBytes(legacy)
                val store = LibraryFacetDiskStore(directory)
                val selection = mapOf("authors" to setOf("Doe, Jane", " 雪 "),
                    "tags" to setOf(LibraryFilters.MISSING))
                check(store.readSelection() == selection)
                check(file.readBytes().contentEquals(legacy)) // Reads never risk rewriting user state.
                store.writeSelection(selection)
                check(file.readBytes()[7].toInt() == 2)
                check(LibraryFacetDiskStore(directory).readSelection() == selection)
            }
            test("clear selection persists and does not clear books") { directory ->
                val store = LibraryFacetDiskStore(directory)
                store.write("a", "1", row("a"), true)
                store.writeSelection(mapOf("authors" to setOf("Doe, Jane")))
                store.writeSelection(emptyMap())
                val reopened = LibraryFacetDiskStore(directory)
                check(reopened.readSelection().isEmpty())
                check(reopened.read("a", "1") != null)
            }
            test("all record truncations are safe misses") { directory ->
                val store = LibraryFacetDiskStore(directory)
                store.write("a", "1", row("a"), true)
                val file = entries(directory).single()
                val original = file.readBytes()
                for (size in 0 until original.size) {
                    file.writeBytes(original.copyOf(size))
                    check(LibraryFacetDiskStore(directory).read("a", "1") == null)
                }
            }
            test("corrupt headers version kind and trailing bytes are misses") { directory ->
                val store = LibraryFacetDiskStore(directory)
                store.write("a", "1", row("a"), true)
                val file = entries(directory).single()
                val original = file.readBytes()
                for (offset in listOf(0, 4, 8)) {
                    val corrupt = original.copyOf()
                    corrupt[offset] = (corrupt[offset].toInt() xor 127).toByte()
                    file.writeBytes(corrupt)
                    check(store.read("a", "1") == null)
                }
                file.writeBytes(original + byteArrayOf(1))
                check(store.read("a", "1") == null)
            }
            test("bad lengths counts UTF8 duplicates and readability are misses") { directory ->
                val store = LibraryFacetDiskStore(directory)
                store.write("a", "1", row("a"), true)
                val file = entries(directory).single()
                fun reject(body: DataOutputStream.() -> Unit) {
                    // Valid checksum ensures the decoder, not the framing, rejects the payload.
                    file.writeBytes(envelope(1, body = body))
                    check(store.read("a", "1") == null)
                }
                reject { writeInt(Int.MAX_VALUE) }
                reject { writeInt(-1) }
                reject { writeInt(1); writeByte(255) }
                reject { string("a"); string("1"); writeByte(7); writeInt(0) }
                reject { string("a"); string("1"); writeByte(1); writeInt(Int.MAX_VALUE) }
                reject { string("a"); string("1"); writeByte(1); writeInt(-1) }
                reject { string("a"); string("1"); writeByte(1); writeInt(1); string("tags"); writeInt(Int.MAX_VALUE) }
                reject {
                    string("a"); string("1"); writeByte(1); writeInt(2)
                    repeat(2) { string("tags"); writeInt(0) }
                }
                reject {
                    string("a"); string("1"); writeByte(1); writeInt(1)
                    string("tags"); writeInt(2); string("same"); string("same")
                }
            }
            test("selection corruption truncation and unsupported versions are empty") { directory ->
                val store = LibraryFacetDiskStore(directory)
                store.writeSelection(mapOf("authors" to setOf("Doe, Jane")))
                val file = File(directory, "selection.bin")
                val original = file.readBytes()
                for (size in 0 until original.size) {
                    file.writeBytes(original.copyOf(size))
                    check(store.readSelection().isEmpty())
                }
                for (offset in listOf(0, 4, 8, 12)) {
                    val corrupt = original.copyOf()
                    corrupt[offset] = 127
                    file.writeBytes(corrupt)
                    check(store.readSelection().isEmpty())
                }
                file.writeBytes(original + byteArrayOf(1))
                check(store.readSelection().isEmpty())
            }
            test("oversized record files are rejected before allocation") { directory ->
                val store = LibraryFacetDiskStore(directory)
                store.write("a", "1", row("a"), true)
                RandomAccessFile(entries(directory).single(), "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
                check(store.read("a", "1") == null)
            }
            test("oversized selection files are rejected before allocation") { directory ->
                val store = LibraryFacetDiskStore(directory)
                store.writeSelection(emptyMap())
                RandomAccessFile(File(directory, "selection.bin"), "rw").use { it.setLength(2L * 1024 * 1024 + 1) }
                check(store.readSelection().isEmpty())
            }
            test("failed record writes retain old data and clean staging") { directory ->
                val store = LibraryFacetDiskStore(directory)
                store.write("a", "1", row("a"), true)
                expectWriteFailure { store.write("a", "2", row("a", setOf("x".repeat(1024 * 1024 + 1))), true) }
                check(LibraryFacetDiskStore(directory).read("a", "1") == Pair(row("a"), true))
                check(directory.listFiles()!!.size == 1)
                val big = (0..16).map { "$it" + "x".repeat(1024 * 1024 - 2) }.toSet()
                expectWriteFailure { store.write("a", "2", row("a", big), true) }
                check(store.read("a", "1") == Pair(row("a"), true))
                check(directory.listFiles()!!.size == 1)
            }
            test("failed selection writes retain prior selection and clean staging") { directory ->
                val store = LibraryFacetDiskStore(directory)
                val selection = mapOf("authors" to setOf("Doe, Jane"))
                store.writeSelection(selection)
                val big = (0..2).map { "$it" + "x".repeat(800_000) }.toSet()
                expectWriteFailure { store.writeSelection(mapOf("tags" to big)) }
                check(LibraryFacetDiskStore(directory).readSelection() == selection)
                check(directory.listFiles()!!.size == 1)
            }
            test("path identity is validated") { directory ->
                val store = LibraryFacetDiskStore(directory)
                expectWriteFailure { store.write("a", "1", row("b"), true) }
                store.write("a", "1", row("a"), true)
                val original = entries(directory).single().readBytes()
                store.write("b", "1", row("b"), true)
                val second = entries(directory).single { !it.readBytes().contentEquals(original) }
                second.writeBytes(original)
                check(store.read("b", "1") == null)
            }
            test("source files and traversal paths are never touched") { directory ->
                val source = File(root, "source.epub")
                val contents = byteArrayOf(80, 75, 0, 1, 127)
                source.writeBytes(contents)
                val timestamp = source.lastModified()
                val absent = File(root, "never-created.epub")
                val paths = listOf(source.absolutePath, absent.absolutePath, "../../escape.epub", "/not/a/real/book.epub", "content://provider/../../book")
                val store = LibraryFacetDiskStore(directory)
                paths.forEach { path ->
                    store.write(path, "opaque", row(path), true)
                    check(store.read(path, "opaque") == Pair(row(path), true))
                }
                check(source.readBytes().contentEquals(contents))
                check(source.lastModified() == timestamp)
                check(!absent.exists() && !File(root, "escape.epub").exists())
                check(entries(directory).size == paths.size)
                check(entries(directory).all { it.name.matches(Regex("[0-9a-f]{64}\\.facet")) })
            }
            test("unavailable cache directories fail safely") { directory ->
                directory.writeText("not a directory")
                val store = LibraryFacetDiskStore(directory)
                check(store.read("a", "1") == null && store.readSelection().isEmpty())
                expectWriteFailure { store.write("a", "1", row("a"), true) }
                check(directory.readText() == "not a directory")
            }
            test("concurrent instances observe only complete replacements") { directory ->
                LibraryFacetDiskStore(directory).write("a", "1", row("a", setOf("0")), true)
                val pool = Executors.newFixedThreadPool(4)
                try {
                    val tasks = (0..3).map { worker -> Callable {
                        val store = LibraryFacetDiskStore(directory)
                        repeat(100) {
                            if (worker < 2) store.write("a", "1", row("a", setOf(if (worker == 0) "0" else "1".repeat(65536))), true)
                            val cached = store.read("a", "1")
                            check(cached != null && cached.second && cached.first.path == "a")
                            check(cached.first.facets["tags"] in listOf(setOf("0"), setOf("1".repeat(65536))))
                        }
                    } }
                    pool.invokeAll(tasks).forEach { it.get() }
                } finally { pool.shutdownNow() }
                check(directory.listFiles()!!.size == 1)
            }
            test("3001 records survive repeated new stores with zero expensive misses") { directory ->
                val store = LibraryFacetDiskStore(directory)
                repeat(3001) { index -> store.write("book-$index", "f-$index", row("book-$index"), true) }
                check(entries(directory).size == 3001)
                var hits = 0
                var expensiveCallbacks = 0
                repeat(2) {
                    val reopened = LibraryFacetDiskStore(directory)
                    repeat(3001) { index ->
                        val cached = reopened.read("book-$index", "f-$index")
                        if (cached == null) expensiveCallbacks++ else {
                            check(cached == Pair(row("book-$index"), true))
                            hits++
                        }
                    }
                }
                check(hits == 6002 && expensiveCallbacks == 0)
                println("PASS disk-cache scale: 3001 records, $hits reopened hits, $expensiveCallbacks expensive callbacks")
            }
            println("PASS $passed LibraryFacetDiskStore tests (real JVM filesystem)")
        } finally {
            check(root.deleteRecursively()) { "Could not remove test cache" }
        }
    }

    private fun row(path: String, tags: Set<String> = setOf("Adventure")) =
        LibraryFacetRecord(path, mapOf("authors" to setOf("Doe, Jane", "Alice"), "tags" to tags))

    private fun entries(directory: File): List<File> = directory.listFiles()!!.filter { it.extension == "facet" }

    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun envelope(kind: Int, version: Int = 2, body: DataOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeInt(0x4c464453); it.writeInt(version); it.writeInt(kind); it.body()
        }
        val content = buffer.toByteArray()
        return if (version == 2) content + MessageDigest.getInstance("SHA-256").digest(content) else content
    }

    private fun expectWriteFailure(block: () -> Unit) {
        try {
            block()
            error("Expected IOException")
        } catch (failure: IOException) {
            check(failure.message == "Facet cache write failed")
            check(failure.cause == null)
        }
    }
}
