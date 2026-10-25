package com.vivi.aharana

object OrganisationPathsTest {
    @JvmStatic fun main(args: Array<String>) {
        requireExpectedBookIdentity(4L, 4L)
        requireExpectedBookIdentity(0L, 5L)
        check(runCatching { requireExpectedBookIdentity(4L, 5L) }.isFailure)
        check(runCatching { requireExpectedBookIdentity(4L, null) }.isFailure)
        check(organisationRootMatches("/books/", "/books"))
        check(organisationRootMatches("/books/./Author/..", "/books"))
        check(!organisationRootMatches("/books", "/other"))
        check(!organisationRootMatches("books", "/books"))
        val staleRows = listOf(1L to "/books/old.epub", 2L to "/books/Writer/Title.epub")
        val forward = listOf("/books/old.epub" to "/books/Writer/Title.epub")
        check(runCatching { requireUnoccupiedOrganisationTargets(staleRows, forward) }.isFailure)
        check(runCatching { requireUnoccupiedOrganisationTargets(staleRows, forward.reversed().map { it.second to it.first }) }.isFailure)
        requireUnoccupiedOrganisationTargets(staleRows.take(1), forward)
        requireUnoccupiedOrganisationTargets(staleRows, listOf("/books/old.epub" to "/books/old.epub"))
        check(runCatching { requireUnoccupiedOrganisationTargets(staleRows.take(1),
            forward + ("/other" to "/books/Writer/Title.epub")) }.isFailure)
        val moves = mapOf("/books/old.epub" to "/books/Writer/Title.epub")
        check(remapOrganisedPaths("/books/old.epub", "/books/old.epub", moves) ==
            Pair("/books/Writer/Title.epub", "/books/Writer/Title.epub"))
        check(remapOrganisedPaths("/books/old.epub", null, moves) ==
            Pair("/books/Writer/Title.epub", null))
        check(remapOrganisedPaths("/books/old.epub", "content://external/original", moves) ==
            Pair("/books/Writer/Title.epub", "content://external/original"))
        check(remapOrganisedPaths("/elsewhere/book.epub", "/elsewhere/book.epub", moves) ==
            Pair("/elsewhere/book.epub", "/elsewhere/book.epub"))
        // Apply the recovery snapshot once, not transitive string rewrites.
        val reused = mapOf("/a" to "/b", "/b" to "/c")
        check(remapOrganisedPaths("/a", "/a", reused) == Pair("/b", "/b"))
        val alreadyMoved = remapOrganisedPaths("/books/Writer/Title.epub", null, moves)
        check(alreadyMoved == Pair("/books/Writer/Title.epub", null))
        println("OrganisationPathsTest: 6 remapping + 4 root comparison + 5 destination-conflict cases PASS")
    }
}
