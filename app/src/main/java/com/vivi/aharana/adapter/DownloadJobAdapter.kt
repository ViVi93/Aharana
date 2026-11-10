package com.vivi.aharana.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vivi.aharana.R
import com.vivi.aharana.DownloadWarnings
import com.vivi.aharana.QueueJobPolicy
import com.vivi.aharana.data.local.DownloadJobEntity

class DownloadJobAdapter(
    private val onRetry: (DownloadJobEntity) -> Unit,
    private val onCancel: (DownloadJobEntity) -> Unit,
    private val onRemove: (DownloadJobEntity) -> Unit
) : ListAdapter<DownloadJobEntity, DownloadJobAdapter.JobViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JobViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_job, parent, false)
        return JobViewHolder(view)
    }

    override fun onBindViewHolder(holder: JobViewHolder, position: Int) {
        holder.bind(getItem(position), onRetry, onCancel, onRemove)
    }

    class JobViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textTitle: TextView = itemView.findViewById(R.id.textTitle)
        private val textMeta: TextView = itemView.findViewById(R.id.textMeta)
        private val textStatus: TextView = itemView.findViewById(R.id.textStatus)
        private val badgeBg: View = itemView.findViewById(R.id.statusBadgeBackground)
        private val textError: TextView = itemView.findViewById(R.id.textError)
        private val iconStatus: ImageView = itemView.findViewById(R.id.iconStatus)
        private val buttonRetry: Button = itemView.findViewById(R.id.buttonRetry)
        private val buttonCancel: Button = itemView.findViewById(R.id.buttonCancel)
        private val buttonRemove: Button = itemView.findViewById(R.id.buttonRemove)

        fun bind(
            job: DownloadJobEntity,
            onRetry: (DownloadJobEntity) -> Unit,
            onCancel: (DownloadJobEntity) -> Unit,
            onRemove: (DownloadJobEntity) -> Unit
        ) {
            val context = itemView.context

            // Title — the story, not just the host. Every StoriesOnline row used to
            // read "storiesonline.net" and every Literotica row "literotica.com", so
            // the queue gave no clue which download was which.
            textTitle.text = when {
                job.inputUrl?.isNotBlank() == true -> storyLabel(job.inputUrl)
                job.inputPath?.isNotBlank() == true -> {
                    java.io.File(job.inputPath).nameWithoutExtension
                }
                else -> context.getString(R.string.job_unknown_title)
            }

            // Meta line
            val typeLabel = when (job.type) {
                "download" -> context.getString(R.string.job_type_download)
                "update" -> context.getString(R.string.job_type_update)
                "force_download" -> context.getString(R.string.job_type_force_download)
                "metadata" -> context.getString(R.string.job_type_metadata)
                else -> job.type
            }
            val createdStr = android.text.format.DateFormat.format(
                "MMM d, HH:mm", job.createdAt
            ).toString()
            val host = job.inputUrl?.let { android.net.Uri.parse(it).host } ?: ""
            textMeta.text = if (host.isNotBlank()) {
                "$typeLabel · $host · $createdStr"
            } else {
                "$typeLabel · $createdStr"
            }

            // Status badge
            val warnings = DownloadWarnings.parse(job.resultJson)
            val (statusText, statusColorRes, iconVisible) = when (job.status) {
                "queued" -> Triple(
                    context.getString(R.string.status_queued),
                    R.color.fanficfare_tertiary,
                    true
                )
                "waiting" -> Triple(
                    context.getString(R.string.status_waiting),
                    R.color.fanficfare_tertiary,
                    true
                )
                "running" -> Triple(
                    context.getString(R.string.status_running),
                    R.color.fanficfare_secondary,
                    true
                )
                "failed" -> Triple(
                    context.getString(R.string.status_failed),
                    R.color.fanficfare_error,
                    false
                )
                "cancelled" -> Triple(
                    context.getString(R.string.status_cancelled),
                    R.color.fanficfare_on_surface_variant,
                    false
                )
                "success" -> Triple(
                    warnings.completionMessage(context.getString(R.string.status_complete)),
                    R.color.fanficfare_secondary,
                    false
                )
                else -> Triple(
                    job.status.replaceFirstChar { it.uppercase() },
                    R.color.fanficfare_on_surface_variant,
                    false
                )
            }
            textStatus.text = statusText
            val baseColor = context.getColor(statusColorRes)
            textStatus.setTextColor(baseColor)

            val badgeBgDrawable = android.graphics.drawable.GradientDrawable()
            badgeBgDrawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            badgeBgDrawable.setCornerRadius(16f * context.resources.displayMetrics.density)
            badgeBgDrawable.setColor(
                android.graphics.Color.argb(
                    40,
                    android.graphics.Color.red(baseColor),
                    android.graphics.Color.green(baseColor),
                    android.graphics.Color.blue(baseColor)
                )
            )
            badgeBg.background = badgeBgDrawable

            iconStatus.visibility = if (iconVisible) View.VISIBLE else View.GONE

            // Error
            val failureReason = QueueJobPolicy.failureReason(job.status, job.error)
            if (failureReason != null) {
                textError.visibility = View.VISIBLE
                textError.text = failureReason
            } else if (job.status == "success" && warnings.hasWarnings) {
                textError.visibility = View.VISIBLE
                textError.text = warnings.messages.joinToString("\n").ifBlank {
                    warnings.completionMessage("EPUB saved")
                }
            } else {
                textError.visibility = View.GONE
            }

            // In-flight jobs (queued/waiting/running) get Cancel; failed ones get
            // Retry. Remove only appears once a job is no longer in flight: for a
            // live job it merely deletes the Room row while the WorkManager job
            // keeps running and re-inserts a row when it starts, which is exactly
            // the confusing "it came back" behaviour we are removing here.
            val inFlight = job.status == "queued" ||
                job.status == "waiting" ||
                job.status == "running"

            buttonCancel.visibility = if (inFlight) View.VISIBLE else View.GONE
            buttonCancel.setOnClickListener { onCancel(job) }

            val showRetry = job.status == "failed" || job.status == "cancelled"
            buttonRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
            buttonRetry.setOnClickListener { onRetry(job) }

            buttonRemove.visibility = if (inFlight) View.GONE else View.VISIBLE
            buttonRemove.setOnClickListener { onRemove(job) }
        }
    }

    companion object {
        /** Path segments that are site scaffolding, not a story name. */
        private val FILLER_SEGMENTS = setOf("series", "se", "s", "n", "story", "stories", "work", "works", "fiction")

        /**
         * Human-readable label for a job URL.
         *
         * Prefers the story slug in the path (`/n/62187/the-bridge-club-illustrated`
         * -> "The Bridge Club Illustrated"). When the URL carries no slug
         * (`/s/31154`, `/series/se/495651145`) it falls back to host plus the last
         * path segments, which at least identifies the story by id.
         */
        private fun storyLabel(url: String): String {
            val uri = android.net.Uri.parse(url)
            val host = uri.host ?: return url
            val segments = (uri.path ?: "").split('/').filter { it.isNotBlank() }
            val slug = segments.lastOrNull { seg ->
                seg.length > 2 && seg.any { it.isLetter() } && !FILLER_SEGMENTS.contains(seg.lowercase())
            }
            if (slug != null) {
                return slug.split('-', '_')
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            }
            val tail = segments.takeLast(2).joinToString("/")
            return if (tail.isBlank()) host else "$host/$tail"
        }

        private val DiffCallback = object : DiffUtil.ItemCallback<DownloadJobEntity>() {
            override fun areItemsTheSame(old: DownloadJobEntity, new: DownloadJobEntity): Boolean =
                old.id == new.id

            override fun areContentsTheSame(old: DownloadJobEntity, new: DownloadJobEntity): Boolean =
                old == new
        }
    }
}
