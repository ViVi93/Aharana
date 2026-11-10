package com.vivi.aharana

import com.vivi.aharana.data.local.DownloadJobDao
import com.vivi.aharana.data.local.JobOutcomeIdentity
import java.io.IOException
import java.io.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext

/** Idempotent data cleanup, not a schema migration. One identity and <=8K text per SQL read.
 * Global DAO projections are independently safe BEFORE this runs or if it is interrupted.
 */
object QueueHistory {
    /** Request-scoped, bounded preview; raw metadata remains unchanged for direct consumers. */
    suspend fun metadataPreview(dao: DownloadJobDao, id: Long): org.json.JSONObject? = withContext(Dispatchers.IO) {
        val row = dao.outcomeIdentity(id) ?: return@withContext null
        if (row.type != "metadata" || row.status != "success") return@withContext null
        try {
            ChunkReader(dao, row, currentCoroutineContext()).use { DownloadOutcome.metadataPreview(it) }
        } catch (_: OutcomeChanged) { null }
    }

    suspend fun compactLegacy(dao: DownloadJobDao): Int = withContext(Dispatchers.IO) {
        var afterId = 0L
        var changed = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val row = dao.nextLegacyOutcome(afterId) ?: break
            afterId = row.id
            try {
                val outcome = ChunkReader(dao, row, currentCoroutineContext()).use { DownloadOutcome.compact(it) }
                changed += dao.compactLegacyOutcome(row.id, row.workId, row.type, row.status,
                    row.finishedAt, row.resultLength, outcome)
            } catch (_: OutcomeChanged) {
                // Retry/Cancel/Remove won. Never restore an old body or touch the new request.
            }
            yield()
        }
        changed
    }

    private class OutcomeChanged : IOException("Queue outcome changed while reading")

    private class ChunkReader(
        val dao: DownloadJobDao,
        val row: JobOutcomeIdentity,
        val context: CoroutineContext
    ) : Reader() {
        private var nextStart = 1
        private var chunk = ""
        private var offset = 0

        override fun read(buffer: CharArray, targetOffset: Int, length: Int): Int {
            if (length == 0) return 0
            if (offset == chunk.length) {
                context.ensureActive()
                if (nextStart > row.resultLength) return -1
                chunk = dao.readOutcomeChunk(row.id, row.workId, row.type, row.status,
                    row.finishedAt, row.resultLength, nextStart) ?: throw OutcomeChanged()
                if (chunk.isEmpty()) throw OutcomeChanged()
                // SQLite substr/length count code points, Reader/String indices count UTF-16 units.
                nextStart += chunk.codePointCount(0, chunk.length)
                offset = 0
            }
            val count = minOf(length, chunk.length - offset)
            chunk.toCharArray(buffer, targetOffset, offset, offset + count)
            offset += count
            return count
        }

        override fun close() { chunk = "" }
    }
}