package com.vivi.aharana

import java.io.File
import java.nio.file.Files

/** Execute the same path boundary used by PythonBridge, without an Android mock. */
object OrganisationPrivatePathTest {
    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "--path") {
            print(organisationPrivatePath(File(args[1]), args[2]).absolutePath)
            return
        }
        val root = Files.createTempDirectory("organisation-private-path-").toFile()
        try {
            val actual = File(root, "data/data/com.vivi.aharana.debug/files").apply { mkdirs() }
            val user = File(root, "data/user").apply { mkdirs() }
            Files.createSymbolicLink(File(user, "0").toPath(), File(root, "data/data").toPath())
            val aliasedFiles = File(user, "0/com.vivi.aharana.debug/files")
            val journal = organisationPrivatePath(aliasedFiles, "organisation")
            check(journal == File(actual.canonicalFile, "organisation"))
            check(!journal.exists()) { "Computing the path must not create directories" }
            check(organisationPrivatePath(actual, "organisation") == journal)
            journal.mkdirs()
            val evidence = File(journal, "organizer.json").apply { writeText("existing journal") }
            check(File(organisationPrivatePath(aliasedFiles, "organisation"), evidence.name).readText() == "existing journal")
            val outside = File(root, "outside").apply { mkdirs() }
            val linkedChild = File(actual, "linked-journal")
            Files.createSymbolicLink(linkedChild.toPath(), outside.toPath())
            check(organisationPrivatePath(aliasedFiles, linkedChild.name) == linkedChild) {
                "Only the trusted filesDir may resolve aliases, not the journal child"
            }
            check(runCatching { organisationPrivatePath(aliasedFiles, "../escape") }.isFailure)
            check(runCatching { organisationPrivatePath(aliasedFiles, "/escape") }.isFailure)
            println("PASS: trusted filesDir alias resolved; missing/existing journal preserved; child symlinks not followed; traversal rejected")
        } finally { root.deleteRecursively() }
    }
}
