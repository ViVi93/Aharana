package com.vivi.aharana

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

/**
 * Private, disposable facet index; deliberately independent of the Room library schema.
 * Call every operation from Dispatchers.IO. Source paths are opaque keys: no method
 * opens, stats, modifies or deletes an EPUB or an existing library database.
 */
internal class LibraryFacetIndexDatabase(context: Context) {
    private val helper = Helper(context.applicationContext)

    /** One bounded query; bad rows are misses without preventing reuse of intact rows. */
    @Synchronized
    fun readAll(): Map<String, LibraryFacetIndexEntry> {
        val result = linkedMapOf<String, LibraryFacetIndexEntry>()
        try {
            helper.readableDatabase.rawQuery(READ_ALL_SQL, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val entry = LibraryFacetPayload.decodeEntry(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getBlob(3), cursor.getBlob(4)) ?: continue
                    result[entry.record.path] = entry
                }
            }
        } catch (_: SQLiteException) {
            // A failed/unreadable private cache must not make library startup fail.
        }
        return result
    }

    @Synchronized
    fun upsert(entry: LibraryFacetIndexEntry) = upsertAll(listOf(entry))

    /** A failed encode/write rolls back the entire batch, including earlier replacements. */
    @Synchronized
    fun upsertAll(entries: Collection<LibraryFacetIndexEntry>) {
        if (entries.isEmpty()) return
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.compileStatement(UPSERT_SQL).use { statement ->
                for (entry in entries) {
                    val payload = LibraryFacetPayload.encode(entry.record)
                    val checksum = LibraryFacetPayload.rowChecksum(
                        entry.record.path, entry.bookSignature, entry.fileSignature, payload)
                    statement.clearBindings()
                    statement.bindString(1, entry.record.path)
                    statement.bindString(2, entry.bookSignature)
                    statement.bindString(3, entry.fileSignature)
                    statement.bindBlob(4, payload)
                    statement.bindBlob(5, checksum)
                    statement.executeInsert()
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** A temporary key table avoids SQLite's bind-variable limit for large libraries. */
    @Synchronized
    fun removeExcept(paths: Set<String>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.execSQL(CREATE_KEEP_SQL)
            database.execSQL(CLEAR_KEEP_SQL)
            database.compileStatement(INSERT_KEEP_SQL).use { statement ->
                for (path in paths) {
                    statement.clearBindings()
                    statement.bindString(1, path)
                    statement.executeInsert()
                }
            }
            database.execSQL(REMOVE_EXCEPT_SQL)
            database.execSQL(CLEAR_KEEP_SQL)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun invalidate(path: String) {
        helper.writableDatabase.compileStatement(INVALIDATE_SQL).use {
            it.bindString(1, path)
            it.executeUpdateDelete()
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "library_facet_index_v2.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) { db.execSQL(CREATE_TABLE_SQL) }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Unsupported facet index schema: $oldVersion -> $newVersion")
        }
    }

    private companion object {
        // Kept as literal SQL so the portable test runner can execute these exact statements.
        const val CREATE_TABLE_SQL = """
            CREATE TABLE library_facet_index (
                path TEXT NOT NULL PRIMARY KEY,
                book_signature TEXT NOT NULL,
                file_signature TEXT NOT NULL,
                payload BLOB NOT NULL,
                payload_sha256 BLOB NOT NULL
            )
        """
        // INSERT OR REPLACE works on the SQLite shipped with minSdk 24; modern UPSERT does not.
        const val UPSERT_SQL = """
            INSERT OR REPLACE INTO library_facet_index
                (path, book_signature, file_signature, payload, payload_sha256)
                VALUES (?, ?, ?, ?, ?)
        """
        // Filter BEFORE projection into Android's CursorWindow (well below its row limit).
        // CAST to BLOB measures UTF-8 bytes, including embedded NULs, not text characters.
        const val READ_ALL_SQL = """
            SELECT path, book_signature, file_signature, payload, payload_sha256
            FROM library_facet_index
            WHERE typeof(path) = 'text' AND length(CAST(path AS BLOB)) <= 65536
                AND typeof(book_signature) = 'text' AND length(CAST(book_signature AS BLOB)) <= 65536
                AND typeof(file_signature) = 'text' AND length(CAST(file_signature AS BLOB)) <= 65536
                AND typeof(payload) = 'blob' AND length(payload) BETWEEN 48 AND 524288
                AND typeof(payload_sha256) = 'blob' AND length(payload_sha256) = 32
        """
        const val CREATE_KEEP_SQL = """
            CREATE TEMP TABLE IF NOT EXISTS library_facet_keep (path TEXT NOT NULL PRIMARY KEY)
        """
        const val CLEAR_KEEP_SQL = """DELETE FROM library_facet_keep"""
        const val INSERT_KEEP_SQL = """INSERT OR IGNORE INTO library_facet_keep (path) VALUES (?)"""
        const val REMOVE_EXCEPT_SQL = """
            DELETE FROM library_facet_index WHERE NOT EXISTS (
                SELECT 1 FROM library_facet_keep WHERE library_facet_keep.path = library_facet_index.path
            )
        """
        const val INVALIDATE_SQL = """DELETE FROM library_facet_index WHERE path = ?"""
    }
}
