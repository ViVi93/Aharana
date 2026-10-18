package com.vivi.aharana

data class LibraryFacetRecord(val path: String, val facets: Map<String, Set<String>>)

/** Pure filter policy. Multiple values OR; independent categories AND. */
object LibraryFilters {
    const val MISSING = "(Not stored)"
    val categories = linkedMapOf("authors" to "Authors", "status" to "Completion status",
        "site" to "Source sites", "languages" to "Languages", "series" to "Series",
        "tags" to "Tags / genres / fandoms", "chapters" to "Chapter count")

    fun values(record: LibraryFacetRecord, key: String): Set<String> =
        record.facets[key]?.filter { it.isNotBlank() }?.toSet()?.takeIf { it.isNotEmpty() } ?: setOf(MISSING)

    fun matches(record: LibraryFacetRecord, selection: Map<String, Set<String>>): Boolean =
        selection.all { (key, selected) -> selected.isEmpty() || values(record, key).any { it in selected } }

    fun options(records: Collection<LibraryFacetRecord>, key: String): Map<String, Int> =
        records.flatMap { values(it, key) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { it.key.lowercase(java.util.Locale.ROOT) }.thenBy { it.key })
            .associate { it.key to it.value }

    fun chapterBucket(count: Int): String = when {
        count <= 0 -> MISSING
        count == 1 -> "1 chapter"
        count <= 10 -> "2–10 chapters"
        count <= 50 -> "11–50 chapters"
        else -> "51+ chapters"
    }
}
