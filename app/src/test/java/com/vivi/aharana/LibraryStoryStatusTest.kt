package com.vivi.aharana

object LibraryStoryStatusTest {
    @JvmStatic fun main(args: Array<String>) {
        val cases = listOf(
            "Completed" to "Complete", "Complete" to "Complete",
            "In-Progress" to "In-progress", "In Progress" to "In-progress",
            "Incomplete" to "Unfinished", "Unfinished" to "Unfinished",
            "Paused" to "Paused", "Hiatus" to "Hiatus", "Cancelled" to "Cancelled",
            "" to "Unknown", "  " to "Unknown", " Completed " to "Complete")
        for ((raw, expected) in cases) check(LibraryStoryStatus.label(raw) == expected) { "$raw != $expected" }
        check(LibraryStoryStatus.label(null) == "Unknown")
        println("PASS 13 library status label cases; missing status never guessed unfinished")
    }
}
