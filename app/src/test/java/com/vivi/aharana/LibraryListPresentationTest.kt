package com.vivi.aharana

object LibraryListPresentationTest {
    @JvmStatic fun main(args: Array<String>) {
        fun state(count: Int, restoring: Boolean = false, refreshing: Boolean = false, narrowed: Boolean = true) =
            LibraryListPresentation.choose(count, restoring, refreshing, narrowed)
        check(state(0, restoring = true) == LibraryListPresentation.LOADING)
        check(state(0, refreshing = true) == LibraryListPresentation.REFRESHING)
        check(state(5, refreshing = true) == LibraryListPresentation.CONTENT)
        check(state(0) == LibraryListPresentation.NO_MATCHES)
        check(state(0, narrowed = false) == LibraryListPresentation.EMPTY)
        check(state(3, restoring = true) == LibraryListPresentation.CONTENT)
        check(LibraryListPresentation.choose(0, false, false, true, indexReady = false) == LibraryListPresentation.UNAVAILABLE)
        println("PASS 7 library presentation states; unfinished or failed index never implies no matches")
    }
}
