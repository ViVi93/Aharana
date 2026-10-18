package com.vivi.aharana

object LibraryFiltersTest {
    @JvmStatic fun main(args: Array<String>) {
        val rows = listOf(
            LibraryFacetRecord("a", mapOf("authors" to setOf("Alice", "Smith, Bob"), "status" to setOf("Complete"), "tags" to setOf("Adventure", "Romance"))),
            LibraryFacetRecord("b", mapOf("authors" to setOf("Bob"), "status" to setOf("In-progress"), "tags" to setOf("Adventure"))),
            LibraryFacetRecord("c", mapOf("authors" to setOf("Alice"), "status" to setOf("Unfinished"), "tags" to setOf("Drama"))))
        check(LibraryFilters.matches(rows[0], emptyMap()))
        check(LibraryFilters.matches(rows[0], mapOf("authors" to setOf("Alice", "Bob"))))
        check(!LibraryFilters.matches(rows[1], mapOf("authors" to setOf("Alice"))))
        check(!LibraryFilters.matches(rows[0], mapOf("authors" to setOf("Smith"))))
        check(LibraryFilters.matches(rows[0], mapOf("authors" to setOf("Smith, Bob"))))
        check(LibraryFilters.matches(rows[0], mapOf("authors" to setOf("Alice"), "status" to setOf("Complete"))))
        check(!LibraryFilters.matches(rows[2], mapOf("authors" to setOf("Alice"), "status" to setOf("Complete"))))
        check(LibraryFilters.matches(rows[0], mapOf("tags" to setOf("Romance", "Drama"))))
        check(!LibraryFilters.matches(rows[1], mapOf("tags" to setOf("Romance", "Drama"))))
        val opts = LibraryFilters.options(rows, "authors")
        check(opts["Alice"] == 2 && opts["Smith, Bob"] == 1 && opts["Bob"] == 1)
        check(LibraryFilters.options(rows, "tags")["Adventure"] == 2)
        check(LibraryFilters.matches(LibraryFacetRecord("none", emptyMap()), mapOf("series" to setOf(LibraryFilters.MISSING))))
        check(!LibraryFilters.matches(LibraryFacetRecord("none", emptyMap()), mapOf("series" to setOf("Series A"))))
        check(LibraryFilters.chapterBucket(0) == LibraryFilters.MISSING)
        check(LibraryFilters.chapterBucket(1) == "1 chapter")
        check(LibraryFilters.chapterBucket(10) == "2–10 chapters")
        check(LibraryFilters.chapterBucket(50) == "11–50 chapters")
        check(LibraryFilters.chapterBucket(51) == "51+ chapters")
        val caseRows = rows + LibraryFacetRecord("d", mapOf("authors" to setOf("alice")))
        val caseOptions = LibraryFilters.options(caseRows, "authors")
        check(caseOptions["Alice"] == 2 && caseOptions["alice"] == 1)
        check(LibraryFilters.options(emptyList(), "tags").isEmpty())
        check(LibraryFilters.matches(rows[0], mapOf("tags" to emptySet())))
        println("PASS 21 library filter policy cases (OR within, AND across, exact authors, counts, missing, chapter ranges, case variants)")
    }
}
