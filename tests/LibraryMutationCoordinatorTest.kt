package com.vivi.aharana

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*

object LibraryMutationCoordinatorTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = Files.createTempDirectory("mutation").toFile()
        try {
            val original = File(root, "original.epub").apply { writeText("old") }
            val moved = File(root, "author/book.epub").apply { parentFile.mkdirs() }
            val fileMoved = CompletableDeferred<Unit>()
            val releaseOrganiser = CompletableDeferred<Unit>()
            var roomPath = original.path
            val organise = launch(Dispatchers.Default) {
                LibraryStorageGate.run {
                    check(original.renameTo(moved))
                    fileMoved.complete(Unit)
                    releaseOrganiser.await()
                    roomPath = moved.path
                }
            }
            fileMoved.await()
            var recovered = false
            val edit = async(Dispatchers.Default) {
                LibraryMutationCoordinator.run(listOf(original.path), {
                    recovered = true
                    mapOf(original.path to moved.path, moved.path to "must-not-follow")
                }) { paths ->
                    check(roomPath == moved.path) { "Mutation ran before organiser Room commit" }
                    File(paths.single()).writeText("edited")
                    roomPath = paths.single()
                }
            }
            delay(75)
            check(!recovered) { "Recovery raced organiser filesystem transaction" }
            releaseOrganiser.complete(Unit)
            withTimeout(5000) { organise.join(); edit.await() }
            check(!original.exists()) { "Stale metadata save recreated moved original" }
            check(moved.readText() == "edited") { "Flattened snapshot must be applied exactly once" }
            check(roomPath == moved.path)

            // An undo paused between its transfer and Room commit must exclude deletion.
            val undoMoved = CompletableDeferred<Unit>()
            val releaseUndo = CompletableDeferred<Unit>()
            val undo = launch(Dispatchers.Default) {
                LibraryStorageGate.run {
                    check(moved.renameTo(original))
                    undoMoved.complete(Unit)
                    releaseUndo.await()
                    check(original.exists()) { "Delete poisoned pending undo recovery" }
                    roomPath = original.path
                }
            }
            undoMoved.await()
            val delete = async(Dispatchers.Default) {
                LibraryMutationCoordinator.run(listOf(moved.path), { mapOf(moved.path to original.path) }) {
                    check(roomPath == original.path)
                    check(File(it.single()).delete())
                    roomPath = "deleted"
                }
            }
            delay(75)
            check(original.exists()) { "Delete entered an unfinished undo" }
            releaseUndo.complete(Unit)
            withTimeout(5000) { undo.join(); delete.await() }
            check(!original.exists() && !moved.exists() && roomPath == "deleted")

            // Recovery errors must fail closed, and release the mutex for independent retries.
            var touched = false
            try {
                LibraryMutationCoordinator.run(listOf("source"), { error("recovery failed") }) { touched = true }
                error("Expected recovery failure")
            } catch (expected: IllegalStateException) { check(expected.message == "recovery failed") }
            check(!touched)
            withTimeout(5000) { LibraryMutationCoordinator.run(listOf("unmapped"), { emptyMap() }) { check(it == listOf("unmapped")) } }
            println("LibraryMutationCoordinatorTest PASS: metadata/apply, delete/undo, exact mapping, fail-closed recovery")
        } finally { root.deleteRecursively() }
    }
}
