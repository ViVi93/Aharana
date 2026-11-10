package com.vivi.aharana

import android.os.Bundle
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import com.vivi.aharana.adapter.DownloadJobAdapter
import com.vivi.aharana.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadQueueActivity : BaseActivity() {

    private lateinit var adapter: DownloadJobAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_queue)
        applyTopInset(findViewById<android.view.View>(R.id.appBar))
        applyBottomInset(findViewById<android.view.View>(R.id.contentContainer))

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeActionContentDescription("Back")

        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerJobs)
        val emptyState = findViewById<LinearLayout>(R.id.emptyState)

        adapter = DownloadJobAdapter(
            onRetry = { job ->
                DiagnosticLog.append(this, "Queue.Retry", "jobId=${job.id} type=${job.type}")
                val repo = BookRepository.getInstance(this)
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val action = repo.retryJob(job)
                        if (action == "none") {
                            showMessage("Unable to retry: this request has no usable input or is no longer retryable.")
                        } else {
                            showMessage(if (action == "update") R.string.retry_update_toast else R.string.retry_failed_toast)
                        }
                    } catch (e: Exception) {
                        DiagnosticLog.append(this@DownloadQueueActivity, "Queue.Retry",
                            "submission_failed job_id=${job.id} error_class=${e.javaClass.simpleName}")
                        showMessage("Retry could not be queued. The original request was kept.")
                    }
                }
            },
            onCancel = { job ->
                DiagnosticLog.append(this, "Queue.Cancel", "button jobId=${job.id} type=${job.type}")
                BookRepository.getInstance(this).cancelJob(job)
                showMessage(R.string.queue_cancelled_toast)
            },
            onRemove = { job ->
                DiagnosticLog.append(this, "Queue.Remove", "jobId=${job.id}")
                val db = AppDatabase.getInstance(this)
                lifecycleScope.launch {
                    try {
                        val removed = withContext(Dispatchers.IO) {
                            db.downloadJobDao().removeFinished(job.id, job.workId, job.status)
                        }
                        if (removed > 0) showMessage(R.string.queue_removed_toast)
                        else showMessage("This request changed. Its current queue entry was kept.")
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        DiagnosticLog.append(this@DownloadQueueActivity, "Queue.Remove",
                            "remove_failed job_id=${job.id} error_class=${e.javaClass.simpleName}")
                        showMessage("Could not remove this request. Please try again.")
                    }
                }
            }
        )

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val db = AppDatabase.getInstance(this)
        db.downloadJobDao().observeAll().observe(this) { allJobs ->
            // Keep successful partial downloads visible until explicitly removed.
            // Metadata-fetch jobs (type == "metadata") are internal background lookups
            // and should NOT appear in the download queue — they are tracked
            // separately in AddFromPageActivity via observer callbacks.
            val visible = allJobs
                .filter { DownloadWarnings.showInQueue(it.type, it.status, it.resultJson) }
                .sortedByDescending { it.createdAt }
            adapter.submitList(visible.toList())
            if (visible.isEmpty()) {
                emptyState.visibility = LinearLayout.VISIBLE
                recycler.visibility = LinearLayout.GONE
            } else {
                emptyState.visibility = LinearLayout.GONE
                recycler.visibility = LinearLayout.VISIBLE
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
