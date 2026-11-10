package com.vivi.aharana

import java.io.File

/** Source wiring checks supplement (not replace) the real coordinator/index coroutine tests. */
object LibraryFilterManualWiringTest {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single())
        val sources = File(root, "app/src/main/java/com/vivi/aharana")
        val model = File(sources, "LibraryViewModel.kt").readText()
        val dialog = File(sources, "LibraryFilterDialog.kt").readText()
        check("manualRefreshOnly = true" in model) { "production coordinator must explicitly use manual-only mode" }
        check("filterState.validateInBackground()" !in model) { "startup must not validate every EPUB/source" }
        check("cached(rows, allowStale = true)" in model) { "restart must retain changed-revision cached facets" }
        check("cachedCurrentPaths =" in model) { "retaining stale facets must not imply current revisions" }
        check("fun filterDataNeedsRefresh()" in model && "fun filterDataPendingCount()" in model &&
            "fun filterDataUnknownCount()" in model) { "manual policy needs actionable pending/unknown UI state" }
        check("filterIndexReady()" !in dialog) { "opening a stale/empty filter cache must not launch a scan" }
        check("Refresh filter data" in dialog && "Rebuild metadata cache" !in dialog)
        check("show(force = true)" in dialog && "model.prepareFilterIndex(force)" in dialog)
        check("only new/changed books need EPUB reads" !in dialog) { "manual refresh rereads all sources" }
        check("not checked automatically" in dialog && "new/changed" in dialog) { "explain manual cached-data policy" }
        check("filterDataStatus()" in dialog) { "missing data must explain refresh, not appear as an empty library" }
        check("val filterDataUpdates:" in model &&
            Regex("filterDataUpdates\\.observe\\(activity").findAll(dialog).count() == 2) {
            "open dialogs must update their explanation/options when membership changes without scanning"
        }
        val loader = File(sources, "LibraryFacetLoader.kt").readText()
        check("beginRefresh =" in model && "beginRefresh()" in loader && "database.replaceSnapshot(" in loader) {
            "manual refresh must stage privately and commit one complete durable transaction"
        }
        check("cacheResult = false" in loader && "cacheResult = force" !in loader) {
            "neither visible-row nor standalone forced reads may replace the manual filter snapshot"
        }
        check("filterReadFailureSummary()" in model && "filterReadFailureSummary()" in dialog) {
            "refresh must show a bounded unreadable-book summary, not claim all unreadable facets were replaced"
        }
        check("metadata_read_failed" in loader && "appendException" in loader) {
            "failed book reads must log their reason and safe identity for diagnostic export"
        }
        check("filter_refresh_failed" in dialog && "appendException" in dialog) {
            "unexpected refresh failures must not be discarded behind the generic error dialog"
        }
        println("PASS manual-only ViewModel wiring, stale-cache restore, dialog open/refresh split and actionable policy text")
    }
}
