package com.vivi.aharana.data.local

/** No response body: safe even when the saved legacy JSON exceeds CursorWindow capacity. */
data class JobOutcomeIdentity(
    val id: Long,
    val workId: String?,
    val type: String,
    val status: String,
    val finishedAt: Long?,
    val resultLength: Int
)