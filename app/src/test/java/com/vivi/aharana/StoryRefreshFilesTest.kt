package com.vivi.aharana

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

/** Real filesystem tests, no Android mocks or network. */
object StoryRefreshFilesTest {
    private var passed = 0
    private fun test(name: String, block: () -> Unit) { block(); passed++; println("PASS $name") }
    private fun save(source: File, folder: File, name: String): File {
        val type = try { Class.forName("com.vivi.aharana.StoryRefreshFiles") }
        catch (_: ClassNotFoundException) { throw AssertionError("Safe verified refresh copy persistence is missing") }
        try {
            return type.getMethod("saveNewCopy", File::class.java, File::class.java, String::class.java)
                .invoke(type.getField("INSTANCE").get(null), source, folder, name) as File
        } catch (e: InvocationTargetException) { throw e.targetException }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("story_refresh_test_").toFile()
        try {
            test("saved copy matches source bytes and leaves original unchanged") {
                val source = File(root, "source.epub").apply { writeBytes(ByteArray(250_000) { (it % 251).toByte() }) }
                val before = source.readBytes()
                val folder = File(root, "output")
                val result = save(source, folder, "refreshed.epub")
                check(result == File(folder, "refreshed.epub"))
                check(result.readBytes().contentEquals(before))
                check(source.readBytes().contentEquals(before))
                check(folder.listFiles()!!.map { it.name } == listOf("refreshed.epub"))
            }
            test("path traversal is rejected before creating any output") {
                val source = File(root, "safe-source.epub").apply { writeText("unchanged original") }
                val folder = File(root, "safe-output").apply { mkdirs() }
                for (name in listOf("../escape.epub", "nested/escape.epub", ".", "")) {
                    var rejected = false
                    try { save(source, folder, name) } catch (_: IllegalStateException) { rejected = true }
                    check(rejected) { "Unsafe output name was accepted: $name" }
                }
                check(!File(root, "escape.epub").exists())
                check(folder.listFiles()!!.isEmpty())
            }
            test("missing or empty source never creates a replacement") {
                val folder = File(root, "missing-output")
                for (source in listOf(File(root, "does-not-exist.epub"), File(root, "empty.epub").apply { createNewFile() })) {
                    var rejected = false
                    try { save(source, folder, "new.epub") } catch (_: Exception) { rejected = true }
                    check(rejected) { "Invalid source was accepted: $source" }
                    check(!File(folder, "new.epub").exists())
                    check(folder.listFiles().orEmpty().isEmpty())
                }
            }
            test("existing destination is never overwritten") {
                val source = File(root, "collision-source.epub").apply { writeText("new content") }
                val folder = File(root, "collision-output").apply { mkdirs() }
                val existing = File(folder, "held.epub").apply { writeText("held book") }
                var rejected = false
                try { save(source, folder, existing.name) } catch (_: IllegalStateException) { rejected = true }
                check(rejected)
                check(existing.readText() == "held book")
                check(source.readText() == "new content")
                check(folder.listFiles()!!.map { it.name } == listOf("held.epub"))
            }
            test("readback verification rejects truncated or changed bytes") {
                val source = File(root, "verify.epub").apply { writeText("complete original bytes") }
                for (bytes in listOf("complete original bytes".toByteArray().dropLast(1).toByteArray(), "different bytes".toByteArray())) {
                    var rejected = false
                    try { StoryRefreshFiles.verifyCopy(source, bytes.inputStream()) }
                    catch (_: IllegalStateException) { rejected = true }
                    check(rejected)
                }
                source.inputStream().use { StoryRefreshFiles.verifyCopy(source, it) }
                check(source.readText() == "complete original bytes")
            }
            test("invalid destination directory leaves source unchanged") {
                val source = File(root, "blocked-source.epub").apply { writeText("original") }
                val blocked = File(root, "not-a-folder").apply { writeText("unrelated file") }
                var rejected = false
                try { save(source, blocked, "new.epub") } catch (_: IllegalStateException) { rejected = true }
                check(rejected)
                check(source.readText() == "original")
                check(blocked.readText() == "unrelated file")
            }
            println("$passed StoryRefreshFiles tests passed")
        } finally { root.deleteRecursively() }
    }
}
