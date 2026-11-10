package com.vivi.aharana.data.local

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DownloadJobDao {

    companion object {
        // Never materialize a legacy body (including metadata covers) in an all-row cursor.
        // Core SQLite CASE/length/substr work on API 24; no JSON1 dependency or schema change.
        const val HISTORY_SELECT = "SELECT id, bookId, type, status, inputUrl, inputPath, outputPath, error, createdAt, finishedAt, workId, CASE WHEN type IN ('download','update','force_download') AND length(resultJson) <= 8192 AND substr(resultJson,1,19) = '{\"queue_outcome\":1,' THEN resultJson ELSE NULL END AS resultJson FROM download_jobs "
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: DownloadJobEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(jobs: List<DownloadJobEntity>): List<Long>

    @Update
    suspend fun update(job: DownloadJobEntity)

    /** Atomic compare-and-update: a late worker cannot overwrite Cancel, success, or Remove.
     * Keep input/work identity columns intact: storage recovery may have remapped them.
     */
    @Query("UPDATE download_jobs SET status = :status, bookId = :bookId, outputPath = :outputPath, " +
        "error = :error, finishedAt = :finishedAt, resultJson = :resultJson WHERE id = :id " +
        "AND workId IS :workId AND status NOT IN ('success','cancelled','superseded')")
    suspend fun updateWorkerState(id: Long, workId: String?, status: String, bookId: Long, outputPath: String?,
        error: String?, finishedAt: Long?, resultJson: String?): Int

    suspend fun updateFromWorker(job: DownloadJobEntity): Boolean =
        updateWorkerState(job.id, job.workId, job.status, job.bookId, job.outputPath,
            job.error, job.finishedAt, job.resultJson) > 0

    @Query("SELECT * FROM download_jobs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadJobEntity?

    @Query(HISTORY_SELECT + "WHERE bookId = :bookId ORDER BY createdAt ASC")
    fun observeByBookId(bookId: Long): LiveData<List<DownloadJobEntity>>

    @Query(HISTORY_SELECT + "WHERE status = :status ORDER BY createdAt ASC")
    fun observeByStatus(status: String): LiveData<List<DownloadJobEntity>>

    @Query(HISTORY_SELECT + "ORDER BY createdAt ASC")
    fun observeAll(): LiveData<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE workId = :workId LIMIT 1")
    suspend fun findByWorkId(workId: String): DownloadJobEntity?

    @Query("UPDATE download_jobs SET status = 'cancelled', error = :reason, finishedAt = :now " +
        "WHERE id = :id AND workId IS :workId AND (status IN ('queued','waiting','running') " +
        "OR (:includeInterrupted AND status = 'failed'))")
    suspend fun cancelActive(id: Long, workId: String?, includeInterrupted: Boolean, reason: String, now: Long): Int

    /** Claim the SAME row for a new work identity; old attempts can no longer write it. */
    @Query("UPDATE download_jobs SET workId = :workId, status = 'queued', type = :type, " +
        "bookId = :bookId, inputUrl = :inputUrl, inputPath = :inputPath, outputPath = NULL, " +
        "error = NULL, resultJson = NULL, finishedAt = NULL, createdAt = :now " +
        "WHERE id = :id AND workId IS :expectedWorkId AND status IN ('failed','cancelled')")
    suspend fun claimRetry(id: Long, expectedWorkId: String?, workId: String, type: String,
        bookId: Long, inputUrl: String?, inputPath: String?, now: Long): Int

    /** Read-only legacy lookup; never includes a newer, in-flight or other-type request. */
    @Query(
        HISTORY_SELECT + "WHERE inputUrl = :url AND id < :keepId " +
            "AND type = 'download' AND status = 'failed' " +
            "AND createdAt < (SELECT createdAt FROM download_jobs WHERE id = :keepId AND type = 'download')"
    )
    suspend fun findSuperseded(url: String, keepId: Long): List<DownloadJobEntity>

    @Query(HISTORY_SELECT + "ORDER BY createdAt ASC")
    suspend fun getAll(): List<DownloadJobEntity>

    @Query(HISTORY_SELECT + "WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getByStatus(status: String): List<DownloadJobEntity>

    @Query("SELECT id, workId, type, status, finishedAt, length(resultJson) AS resultLength FROM download_jobs " +
        "WHERE id > :afterId AND type IN ('download','update','force_download') " +
        "AND status IN ('success','failed','cancelled','superseded') AND resultJson IS NOT NULL " +
        "AND NOT (length(resultJson) <= 8192 AND substr(resultJson,1,19) = '{\"queue_outcome\":1,') ORDER BY id LIMIT 1")
    suspend fun nextLegacyOutcome(afterId: Long): JobOutcomeIdentity?

    @Query("SELECT id, workId, type, status, finishedAt, length(resultJson) AS resultLength FROM download_jobs " +
        "WHERE id = :id AND resultJson IS NOT NULL LIMIT 1")
    suspend fun outcomeIdentity(id: Long): JobOutcomeIdentity?

    // Synchronous only inside QueueHistory's IO context/Reader; every CursorWindow is <= 8K text.
    @Query("SELECT substr(resultJson,:start,8192) FROM download_jobs WHERE id = :id AND workId IS :workId " +
        "AND type = :type AND status = :status AND finishedAt IS :finishedAt AND length(resultJson) = :resultLength")
    fun readOutcomeChunk(id: Long, workId: String?, type: String, status: String, finishedAt: Long?, resultLength: Int, start: Int): String?

    @Query("UPDATE download_jobs SET resultJson = :resultJson WHERE id = :id AND workId IS :workId " +
        "AND type = :type AND type IN ('download','update','force_download') AND status = :status " +
        "AND status IN ('success','failed','cancelled','superseded') AND finishedAt IS :finishedAt " +
        "AND length(resultJson) = :resultLength " +
        "AND NOT (length(resultJson) <= 8192 AND substr(resultJson,1,19) = '{\"queue_outcome\":1,')")
    suspend fun compactLegacyOutcome(id: Long, workId: String?, type: String, status: String,
        finishedAt: Long?, resultLength: Int, resultJson: String): Int

    /** Recovery must not write projected/null JSON over uncompacted history or raw metadata. */
    @Query("UPDATE download_jobs SET inputPath = :inputPath, outputPath = :outputPath WHERE id = :id AND workId IS :workId")
    suspend fun updatePaths(id: Long, workId: String?, inputPath: String?, outputPath: String?): Int

    /** A delayed Remove must never delete the row after Retry claims a new work identity. */
    @Query("DELETE FROM download_jobs WHERE id = :id AND workId IS :workId AND status = :status " +
        "AND status IN ('failed','cancelled','success','superseded')")
    suspend fun removeFinished(id: Long, workId: String?, status: String): Int

    @Query("DELETE FROM download_jobs")
    suspend fun clear()
}
