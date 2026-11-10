package com.vivi.aharana

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One serial Settings work lane. Generations are checked at delivery, not just completion.
 * Superseded mutations still execute in order: cancelling publication must not skip an import
 * or remove. close() releases queued work, but never assumes a Python call is interruptible.
 */
class SettingsRequests(
    private val worker: ExecutorService,
    private val delivery: Executor
) : AutoCloseable {
    constructor(delivery: Executor) : this(Executors.newSingleThreadExecutor { task ->
        Thread(task, "Settings-worker").apply { isDaemon = true }
    }, delivery)

    private var generation = 0L
    private var closed = false

    @Synchronized fun <T> submit(work: () -> T, publish: (Result<T>) -> Unit) {
        if (closed) return
        val token = ++generation
        worker.execute {
            val result = runCatching(work)
            delivery.execute {
                synchronized(this) {
                    if (!closed && token == generation) publish(result)
                }
            }
        }
    }

    @Synchronized fun cancel() { generation++ }

    @Synchronized override fun close() {
        closed = true
        generation++
        worker.shutdownNow()
    }
}
