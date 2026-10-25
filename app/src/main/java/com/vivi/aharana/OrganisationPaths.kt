package com.vivi.aharana

/** Resolve aliases only in Android's trusted app-private base, never in the child. */
fun organisationPrivatePath(filesDir: java.io.File, childName: String): java.io.File {
    require(childName.isNotBlank() && childName != "." && childName != ".." &&
        !childName.contains('/') && !childName.contains('\\')) { "Invalid app-private child name" }
    return java.io.File(filesDir.canonicalFile, childName)
}

fun requireExpectedBookIdentity(expected: Long, actual: Long?) {
    check(expected <= 0 || expected == actual) {
        "The selected library book was deleted or replaced. Reload the library before applying."
    }
}

/** Remap exact file identities from one durable recovery snapshot, never URL/title. */
fun remapOrganisedPaths(
    filePath: String,
    sourcePath: String?,
    moves: Map<String, String>
): Pair<String, String?> = Pair(moves[filePath] ?: filePath, sourcePath?.let { moves[it] ?: it })

/** Check even absent files: Room's unique path constraint still owns their destinations. */
fun requireUnoccupiedOrganisationTargets(rows: List<Pair<Long, String>>, moves: List<Pair<String, String>>) {
    val occupants = rows.groupBy({ it.second }, { it.first })
    val planned = mutableSetOf<String>()
    for ((source, target) in moves) {
        if (source == target) continue
        check(planned.add(target)) { "Two books propose the same destination: $target. Preview again." }
        check(occupants[target].isNullOrEmpty()) {
            "Library record already owns destination $target (row ${occupants[target]?.joinToString()}). " +
                "No files moved. Remove the stale library entry without deleting files, or choose another folder."
        }
    }
}

/** Lexical only: never follow Android's storage symlinks while comparing preview input. */
fun organisationRootMatches(input: String, planned: String): Boolean {
    val file = java.io.File(input.trim())
    return file.isAbsolute && file.normalize().path == java.io.File(planned).normalize().path
}
