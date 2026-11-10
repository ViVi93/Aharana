package com.vivi.aharana

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Track already-submitted library mutations; a flush never rewrites a stale UI snapshot. */
internal class LibraryWrites {
    private val pending = mutableSetOf<Job>()

    fun launch(scope: CoroutineScope, block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        synchronized(pending) { pending.add(job) }
        job.invokeOnCompletion { synchronized(pending) { pending.remove(job) } }
        job.start()
    }

    suspend fun awaitPending() {
        val snapshot = synchronized(pending) { pending.toList() }
        snapshot.joinAll()
    }
}
