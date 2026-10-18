package com.vivi.aharana

/** Display policy only: missing status is never guessed to be unfinished. */
internal object LibraryStoryStatus {
    fun label(raw: String?): String = when (val value = raw?.trim().orEmpty()) {
        "Completed", "Complete" -> "Complete"
        "In-Progress", "In Progress", "In-progress" -> "In-progress"
        "Incomplete", "Unfinished" -> "Unfinished"
        "" -> "Unknown"
        else -> value // Preserve Paused/Hiatus/Cancelled and other explicit statuses.
    }
}
