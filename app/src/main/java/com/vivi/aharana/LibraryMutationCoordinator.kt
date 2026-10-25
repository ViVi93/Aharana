package com.vivi.aharana

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One accepted local mutation: recover, resolve stale inputs once, read/write, then persist.
 * No network calls here. If an engine gate is needed, acquire it INSIDE this transaction.
 * Callers must not re-enter this coordinator or LibraryStorageGate.
 */
internal object LibraryMutationCoordinator {
    suspend fun <T> run(
        paths: List<String>,
        recover: suspend () -> Map<String, String>,
        block: suspend (List<String>) -> T
    ): T = withContext(Dispatchers.IO + NonCancellable) {
        LibraryStorageGate.run {
            val moves = recover()
            // Recovery already flattened history. Following values transitively can select another book.
            block(paths.map { moves[it] ?: it })
        }
    }
}
