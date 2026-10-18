package com.vivi.aharana

/** Keep unchecked Room fields intact; use the complete saved authors, not the legacy first-author extractor. */
internal object StoryRefreshLibraryMetadata {
    data class Fields(val title: String, val author: String)

    fun merge(currentTitle: String, currentAuthor: String, changedFields: Set<String>,
              savedTitle: String, savedAuthors: List<String>): Fields {
        val title = if ("title" in changedFields) {
            check(savedTitle.isNotBlank()) { "Saved title could not be verified" }
            savedTitle
        } else currentTitle
        val author = if ("authors" in changedFields) {
            check(savedAuthors.isNotEmpty() && savedAuthors.all { it.isNotBlank() }) {
                "Saved authors could not be verified"
            }
            savedAuthors.joinToString(", ")
        } else currentAuthor
        return Fields(title, author)
    }
}
