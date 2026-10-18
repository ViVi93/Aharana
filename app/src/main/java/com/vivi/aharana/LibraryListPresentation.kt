package com.vivi.aharana

/** Availability is distinct from match count, particularly while restoring derived metadata. */
internal enum class LibraryListPresentation {
    CONTENT, LOADING, REFRESHING, UNAVAILABLE, NO_MATCHES, EMPTY;

    companion object {
        fun choose(visibleCount: Int, restoring: Boolean, refreshing: Boolean, narrowed: Boolean, indexReady: Boolean = true): LibraryListPresentation = when {
            visibleCount > 0 -> CONTENT
            restoring -> LOADING
            refreshing -> REFRESHING
            narrowed && !indexReady -> UNAVAILABLE
            narrowed -> NO_MATCHES
            else -> EMPTY
        }
    }
}
