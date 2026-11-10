package com.vivi.aharana

import kotlin.coroutines.startCoroutine

object QueueJobPolicyTest {
    @JvmStatic fun main(args: Array<String>) {
        for (status in listOf("success", "cancelled", "superseded")) {
            check(!QueueJobPolicy.canWorkerWrite(status)) { "A stale worker must preserve $status" }
        }
        check(!QueueJobPolicy.canWorkerWrite(null)) { "Removed rows must not be resurrected" }
        for (status in listOf("queued", "waiting", "running", "failed")) {
            check(QueueJobPolicy.canWorkerWrite(status)) { "Scheduler reentry must be allowed for $status" }
        }
        val reason = QueueJobPolicy.interruptionReason("storage_wait", "3")
        check(reason.contains("storage_wait") && reason.contains("3") && reason.contains("Retry"))
        check(!reason.contains("cancelled")) { "Scheduler interruption is not explicit cancellation" }
        check(QueueJobPolicy.failureReason("cancelled", null).orEmpty().contains("Retry"))
        check(QueueJobPolicy.failureReason("failed", "  ").orEmpty().contains("Retry"))
        check(QueueJobPolicy.failureReason("cancelled", "User cancelled at bridge").orEmpty() == "User cancelled at bridge")
        check(QueueJobPolicy.failureReason("success", "old failure") == null)
        check(!QueueJobPolicy.shouldLogHeartbeatStop(normalCleanup = true, stopped = false))
        check(QueueJobPolicy.shouldLogHeartbeatStop(normalCleanup = false, stopped = false))
        check(QueueJobPolicy.shouldLogHeartbeatStop(normalCleanup = false, stopped = true))
        check(QueueJobPolicy.shouldLogHeartbeatStop(normalCleanup = true, stopped = true))
        val events = mutableListOf<String>()
        check(runImmediate { QueueJobPolicy.retryReplacement("update", { events.add("claim"); true },
            { events.add("submit") }) } == "update")
        check(events == listOf("claim", "submit"))
        events.clear()
        check(runImmediate { QueueJobPolicy.retryReplacement("download", { false },
            { events.add("submit") }) } == "none")
        check(events.isEmpty()) { "A lost retry claim must not submit another request" }
        val failure = runCatching { runImmediate { QueueJobPolicy.retryReplacement("download",
            { true }, { error("submission failed") }) } }
        check(failure.exceptionOrNull()?.message == "submission failed")
        check(events.isEmpty())
        println("PASS QueueJobPolicy: terminal protection, scheduler reentry, actionable stop reason, retry claim before submit")
    }

    private fun <T> runImmediate(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completed = result }
        })
        return checkNotNull(completed).getOrThrow()
    }
}
