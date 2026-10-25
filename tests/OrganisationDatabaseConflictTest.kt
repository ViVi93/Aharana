package com.vivi.aharana

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/** Exercise the production preflight against a real SQLite UNIQUE index, not an in-memory row model. */
object OrganisationDatabaseConflictTest {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("organisation-db-").toFile()
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${File(root, "library.db")}").use { db ->
                db.createStatement().use { it.execute("CREATE TABLE books(id INTEGER PRIMARY KEY, filePath TEXT UNIQUE NOT NULL, addedAt INTEGER)") }
                val source = File(root, "old.epub").apply { writeText("original EPUB bytes") }
                val target = File(root, "Writer/Title.epub")
                db.prepareStatement("INSERT INTO books VALUES(?,?,?)").use { insert ->
                    for ((id, path) in listOf(1 to source.path, 2 to target.path)) {
                        insert.setInt(1, id); insert.setString(2, path); insert.setLong(3, 123L + id); insert.executeUpdate()
                    }
                }
                fun applyMove(from: File, to: File): Boolean {
                    db.autoCommit = false
                    try {
                        val rows = db.createStatement().use { statement ->
                            statement.executeQuery("SELECT id,filePath FROM books").use { rs ->
                                buildList { while (rs.next()) add(rs.getLong(1) to rs.getString(2)) }
                            }
                        }
                        requireUnoccupiedOrganisationTargets(rows, listOf(from.path to to.path))
                        check(to.parentFile.isDirectory || to.parentFile.mkdirs())
                        check(from.renameTo(to))
                        db.prepareStatement("UPDATE books SET filePath=? WHERE filePath=?").use {
                            it.setString(1, to.path); it.setString(2, from.path); it.executeUpdate()
                        }
                        db.commit()
                        return true
                    } catch (e: IllegalStateException) {
                        db.rollback()
                        check(e.message.orEmpty().contains("destination"))
                        return false
                    } finally { db.autoCommit = true }
                }
                check(!applyMove(source, target))
                check(source.readText() == "original EPUB bytes" && !target.exists())
                db.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM books").use { check(it.next() && it.getInt(1) == 2) } }
                println("PASS: stale destination row blocks apply before any file move; both SQLite rows kept")
                db.createStatement().use { it.executeUpdate("DELETE FROM books WHERE id=2") }
                check(applyMove(source, target))
                db.createStatement().use { s -> s.executeQuery("SELECT id,addedAt FROM books").use { check(it.next() && it.getLong(1) == 1L && it.getLong(2) == 124L) } }
                db.prepareStatement("INSERT INTO books VALUES(2,?,999)").use { it.setString(1, source.path); it.executeUpdate() }
                check(!applyMove(target, source))
                check(target.readText() == "original EPUB bytes" && !source.exists())
                println("PASS: stale original-path row blocks undo before moving; healthy move preserves ID/date added")
            }
        } finally { root.deleteRecursively() }
    }
}
