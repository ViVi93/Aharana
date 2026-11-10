package com.vivi.aharana

/** Pure queue decisions shared by persistence and host regression tests. */
object QueueJobPolicy {
    fun canWorkerWrite(currentStatus: String?): Boolean =
        currentStatus != null && currentStatus !in setOf("success", "cancelled", "superseded")

    fun shouldLogHeartbeatStop(normalCleanup: Boolean, stopped: Boolean): Boolean =
        !normalCleanup || stopped

    /** Older queue rows may have no persisted reason; never leave them unexplained. */
    fun failureReason(status: String, error: String?): String? {
        if (status !in setOf("failed", "cancelled")) return null
        return error?.takeIf { it.isNotBlank() } ?: if (status == "cancelled") {
            "This request was cancelled. No reason was recorded. Retry or remove it."
        } else {
            "This request did not finish. No reason was recorded. Retry or remove it."
        }
    }

    /** Only the winner of an atomic row/work-identity claim may submit a retry. */
    suspend fun retryReplacement(action: String, claim: suspend () -> Boolean,
        submit: suspend () -> Unit): String {
        if (!claim()) return "none"
        submit()
        return action
    }

    fun interruptionReason(stage: String, stopReason: String): String =
        "Worker interrupted at $stage (stop reason $stopReason). WorkManager may reschedule; Retry if it does not resume."
}
