package com.vivi.aharana

import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs extracted, unmodified worker coroutine methods with only Android/bridge collaborators stubbed. */
object WorkerLifecycleHostTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val normal = WorkerLifecycleHarness()
        check(normal.heartbeat { delay(5); "saved" } == "saved")
        check(normal.events.none { it.startsWith("heartbeat_cancelled") })

        // A synchronous Python-style call continues after cancellation: the heartbeat
        // must report the stop BEFORE it returns, not wait for the worker's outer catch.
        val blocked = WorkerLifecycleHarness()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val holder = launch(Dispatchers.Default) {
            blocked.heartbeat {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                returned.set(true)
            }
        }
        try {
            check(entered.await(2, TimeUnit.SECONDS))
            holder.cancel()
            withTimeout(2_000) {
                while (blocked.events.none { it.startsWith("heartbeat_cancelled") }) delay(1)
            }
            check(!returned.get()) { "Stop must be visible while the synchronous bridge is still blocked" }
        } finally {
            release.countDown()
            holder.join()
        }

        val retry = WorkerLifecycleHarness()
        val bridge = PythonBridge()
        var attempts = 0
        val raw = retry.bridge(bridge) {
            attempts++
            if (attempts < 3) """{"ok":false,"http_status":503,"error":"fixture"}""" else """{"ok":true}"""
        }
        check(JSONObject(raw).getBoolean("ok") && attempts == 3)
        check(bridge.contexts.map { JSONObject(it).getInt("bridge_attempt") } == listOf(1,2,3))
        for (context in bridge.contexts.map(::JSONObject)) {
            check(context.getLong("job_id") == 19L)
            check(context.getString("work_name") == "logical-work-name")
            check(context.getString("request_id") == retry.id.toString())
            check(context.getInt("run_attempt") == 2)
            check(context.getString("operation") == "metadata")
        }

        val stopped = WorkerLifecycleHarness()
        val stoppedBridge = PythonBridge()
        val result = runCatching {
            stopped.bridge(stoppedBridge) {
                stopped.isStopped = true
                """{"ok":true,"warnings":["fixture"]}"""
            }
        }
        check(result.exceptionOrNull() is CancellationException)
        check(stoppedBridge.contexts.size == 1)
        check(stopped.events.any { it.startsWith("bridge_return") && it.contains("stopped=true") })
        check(stopped.events.none { it.contains("fixture") }) { "Raw bridge bodies must not enter lifecycle logs" }

        // Cancellation can precede isStopped becoming visible. The deprecated
        // CoroutineWorker.coroutineContext dispatcher has no Job to check.
        val contextCancelled = WorkerLifecycleHarness()
        var contextFailure: Throwable? = null
        val contextAttempt = launch {
            val runningJob = currentCoroutineContext()[Job]!!
            contextFailure = runCatching {
                contextCancelled.bridge(PythonBridge()) {
                    runningJob.cancel()
                    """{"ok":true}"""
                }
            }.exceptionOrNull()
        }
        contextAttempt.join()
        check(contextFailure is CancellationException) {
            "Cancelled coroutine must not accept bridge success even before isStopped is set"
        }

        AppDatabase.row = HostJob("running", workId = "replacement-work-name")
        var staleBridgeCalled = false
        val staleAttempt = runCatching {
            WorkerLifecycleHarness().bridge(PythonBridge()) {
                staleBridgeCalled = true
                """{"ok":true}"""
            }
        }
        check(staleAttempt.exceptionOrNull() is CancellationException && !staleBridgeCalled) {
            "An old attempt must stop when Retry has claimed the row for a new work identity"
        }

        // Execute the actual outer worker catch with the real production storage
        // mutex. Room and Android are collaborators; no scheduler is simulated.
        for (explicitCancel in listOf(false, true)) {
            AppDatabase.row = HostJob("queued")
            val lockHeld = CompletableDeferred<Unit>()
            val releaseLock = CompletableDeferred<Unit>()
            val storageOwner = launch {
                LibraryStorageGate.run { lockHeld.complete(Unit); releaseLock.await() }
            }
            lockHeld.await()
            val waiting = WorkerLifecycleHarness("download")
            val attempt = launch(start = CoroutineStart.UNDISPATCHED) { waiting.doWork() }
            check(waiting.events.any { it.startsWith("storage_wait ") })
            if (explicitCancel) AppDatabase.row = HostJob("cancelled", error = "Cancelled by user")
            attempt.cancelAndJoin()
            val persisted = checkNotNull(AppDatabase.row)
            if (explicitCancel) {
                check(persisted.status == "cancelled" && persisted.error == "Cancelled by user")
            } else {
                check(persisted.status == "failed")
                check(persisted.error.orEmpty().contains("storage_wait"))
                check(persisted.error.orEmpty().contains("Retry"))
            }
            releaseLock.complete(Unit)
            storageOwner.join()
            if (!explicitCancel) {
                check(WorkerLifecycleHarness("download").doWork().success) {
                    "An interrupted request must be eligible for a new scheduler attempt"
                }
            }
        }
        println("PASS Worker lifecycle coroutines: normal cleanup, early stop during blocking call, per-attempt context, stopped Python return")
        println("PASS Worker storage interruption: retryable failure, explicit-cancel preservation, scheduler reentry")
    }
}
