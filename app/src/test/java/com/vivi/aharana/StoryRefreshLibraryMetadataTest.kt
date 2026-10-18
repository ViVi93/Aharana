package com.vivi.aharana

/** Regression checks for selective Room metadata persistence (no Android mocks). */
object StoryRefreshLibraryMetadataTest {
    private var passed = 0
    private fun test(name: String, block: () -> Unit) { block(); passed++; println("PASS $name") }
    @JvmStatic fun main(args: Array<String>) {
        test("cover-only preserves both unchecked manual title and multiple authors") {
            val result = StoryRefreshLibraryMetadata.merge("Manual title", "Alice, Bob", emptySet(), "EPUB title", listOf("Alice"))
            check(result.title == "Manual title")
            check(result.author == "Alice, Bob")
        }
        test("other selected fields never overwrite title or authors") {
            val result = StoryRefreshLibraryMetadata.merge("Local title", "Local A, Local B", setOf("tags", "status"), "Site title", listOf("Site A"))
            check(result.title == "Local title" && result.author == "Local A, Local B")
        }
        test("selected title changes title only") {
            val result = StoryRefreshLibraryMetadata.merge("Old title", "Alice, Bob", setOf("title"), "New title", listOf("Alice"))
            check(result.title == "New title" && result.author == "Alice, Bob")
        }
        test("selected authors retains every saved author in order") {
            val result = StoryRefreshLibraryMetadata.merge("Local title", "Old author", setOf("authors"), "Unselected title", listOf("Alice", "Bob", "Chandra"))
            check(result.title == "Local title" && result.author == "Alice, Bob, Chandra")
        }
        test("combined apply changes only explicitly changed library fields") {
            val result = StoryRefreshLibraryMetadata.merge("Old title", "Old author", setOf("title", "authors"), "New title", listOf("A", "B"))
            check(result.title == "New title" && result.author == "A, B")
        }
        test("invalid saved values cannot erase an explicitly changed field") {
            for (field in listOf("title", "authors")) {
                var rejected = false
                try { StoryRefreshLibraryMetadata.merge("Kept title", "Kept author", setOf(field), "", emptyList()) }
                catch (_: IllegalStateException) { rejected = true }
                check(rejected)
            }
        }
        println("$passed StoryRefreshLibraryMetadata tests passed")
    }
}
