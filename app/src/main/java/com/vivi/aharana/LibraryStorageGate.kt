package com.vivi.aharana

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide storage transaction gate. Hold before resolving any book path, through its
 * filesystem mutation AND Room persistence. Lock ordering is storage first, then engine.
 * Never acquire from the UI thread or re-enter it (Mutex is deliberately non-reentrant).
 */
object LibraryStorageGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }

    fun <T> runBlocking(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { run(block) }
}
