package com.vivi.aharana

import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BulkQueueResult(val downloads: Int, val updates: Int, val skipped: Int, val failed: Int)

internal class BulkQueueCoordinator {
    companion object {
        // One lock for every repository instance, including a newly rotated Activity.
        private val submissionMutex = Mutex()
    }

    suspend fun enqueue(
        urls: List<String>, mode: String,
        snapshot: suspend () -> BulkQueueSnapshot,
        download: suspend (String) -> Unit,
        update: suspend (BulkQueueBook) -> Unit
    ): BulkQueueResult = submissionMutex.withLock {
        val current = snapshot()
        val state = current.preview(urls)
        var downloads = 0
        var updates = 0
        var skipped = 0
        var failed = 0
        val seen = HashSet<String>()
        for (url in urls) {
            if (!seen.add(BulkQueuePolicy.canonicalUrl(url))) {
                skipped++
                continue
            }
            try {
                require(BulkQueuePolicy.isValidUrl(url)) { "Invalid story URL" }
                if ((mode == "new" || mode == "smart") && state[url] == "new") {
                    download(url)
                    downloads++
                } else if ((mode == "update" || mode == "smart") && state[url] == "library") {
                    update(current.bookFor(url)!!)
                    updates++
                } else skipped++
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed++
            }
        }
        return BulkQueueResult(downloads, updates, skipped, failed)
    }
}

internal data class BulkQueueBook(val id: Long, val url: String?, val path: String, val available: Boolean)
internal data class BulkQueueJob(val type: String, val status: String, val url: String?, val bookId: Long, val path: String?)

/** Immutable indexes built once per preview/enqueue, not once per selected URL. */
internal class BulkQueueSnapshot(books: List<BulkQueueBook>, jobs: List<BulkQueueJob>) {
    private val library = books.filter { it.available && !it.url.isNullOrBlank() }
        .associateBy { BulkQueuePolicy.canonicalUrl(it.url!!) }

    private val byId = books.filter { it.id != 0L }.associateBy { it.id }
    private val byPath = books.filter { it.path.isNotBlank() }.associateBy { it.path }
    private val active = buildSet {
        for (job in jobs) {
            if (job.type !in setOf("download", "update", "force_download") ||
                job.status !in setOf("queued", "waiting", "running", "preparing", "downloading", "processing", "copying", "retrying")) continue
            listOf(job.url, byId[job.bookId]?.url, byPath[job.path]?.url).forEach { url ->
                if (!url.isNullOrBlank()) add(BulkQueuePolicy.canonicalUrl(url))
            }
        }
    }

    fun bookFor(url: String): BulkQueueBook? = library[BulkQueuePolicy.canonicalUrl(url)]

    fun preview(urls: List<String>): Map<String, String> = urls.associateWith {
        val key = BulkQueuePolicy.canonicalUrl(it)
        when {
            key in active -> "queued"
            key in library -> "library"
            else -> "new"
        }
    }
}

internal object BulkQueueMetadataPolicy {
    fun needsSubmission(successful: Boolean, live: Boolean): Boolean = !successful && !live
}

internal object BulkQueueStorage {
    fun isAvailable(path: String, contentProbe: (String) -> Boolean): Boolean = try {
        if (path.startsWith("content://")) contentProbe(path)
        else java.io.File(path).let { it.isFile && it.canRead() && it.length() > 0L }
    } catch (_: Exception) { false }
}

/** Story identity is deliberately independent of the old retry/reconcile matcher. */
internal object BulkQueuePolicy {
    private fun parse(url: String): URI? = try {
        URI(url.trim()).takeIf {
            (it.scheme.equals("http", true) || it.scheme.equals("https", true)) &&
                !it.host.isNullOrBlank() && it.rawUserInfo == null
        }
    } catch (_: java.net.URISyntaxException) { null }

    fun isValidUrl(url: String): Boolean = parse(url) != null

    fun canonicalUrl(url: String): String {
        val uri = parse(url) ?: return "invalid:${url.trim()}"
        var host = uri.host.lowercase().removePrefix("www.")
        if (host in setOf("archiveofourown.org", "archiveofourown.com", "archiveofourown.net", "ao3.org")) {
            host = "archiveofourown.org"
            Regex("^/works/([0-9]+)(?:/chapters/[0-9]+)?/?$").matchEntire(uri.rawPath ?: "")?.let {
                return "$host/works/${it.groupValues[1]}"
            }
        }
        if (host == "m.fanfiction.net" || host == "fanfiction.net") {
            host = "fanfiction.net"
            Regex("^/s/([0-9]+)(?:/.*)?$").matchEntire(uri.rawPath ?: "")?.let {
                return "$host/s/${it.groupValues[1]}"
            }
        }
        val query = uri.rawQuery?.split('&')?.filterNot {
            val key = it.substringBefore('=').lowercase()
            key.startsWith("utm_") || key in setOf("fbclid", "gclid", "dclid", "msclkid")
        }?.joinToString("&")?.takeIf { it.isNotEmpty() }
        val port = if (uri.port == -1 ||
            (uri.scheme.equals("http", true) && uri.port == 80) ||
            (uri.scheme.equals("https", true) && uri.port == 443)) "" else ":${uri.port}"
        return host + port + (uri.rawPath ?: "") + (query?.let { "?$it" } ?: "")
    }
}
