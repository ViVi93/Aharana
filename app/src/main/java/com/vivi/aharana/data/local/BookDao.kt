package com.vivi.aharana.data.local

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface BookDao {

    // Never project coverData into a full-library cursor or retain it in shelf LiveData.
    @Query("SELECT id, title, author, url, filePath, sourcePath, lastModified, sizeBytes, chapters, addedAt FROM books ORDER BY lastModified ASC")
    fun observeAll(): LiveData<List<LibraryBookRow>>

    @Query("SELECT id, title, author, url, filePath, sourcePath, lastModified, sizeBytes, chapters, addedAt FROM books ORDER BY lastModified ASC")
    suspend fun getAll(): List<LibraryBookRow>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("SELECT id, title, author, url, filePath, sourcePath, lastModified, sizeBytes, chapters, addedAt FROM books WHERE id = :id LIMIT 1")
    suspend fun findRowById(id: Long): LibraryBookRow?

    @Query("SELECT id, title, author, url, filePath, sourcePath, lastModified, sizeBytes, chapters, addedAt FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun findRowByFilePath(filePath: String): LibraryBookRow?

    @Query("SELECT id, title, author, url, filePath, sourcePath, lastModified, sizeBytes, chapters, addedAt FROM books WHERE url = :url LIMIT 1")
    suspend fun findRowByUrl(url: String): LibraryBookRow?

    @Query("SELECT coverData FROM books WHERE id = :id LIMIT 1")
    suspend fun getCoverData(id: Long): String?

    @Query("SELECT coverData FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun getCoverDataByPath(filePath: String): String?

    // A shelf item has no cover payload. Missing/blank cover means retain, not erase.
    // Never replace the row: jobs and Activity snapshots hold its stable primary key.
    @Query("UPDATE books SET title = :title, author = :author, url = :url, filePath = :filePath, sourcePath = :sourcePath, lastModified = :lastModified, sizeBytes = :sizeBytes, chapters = :chapters, coverData = COALESCE(NULLIF(:coverData, ''), coverData) WHERE id = :id")
    suspend fun updateMetadata(id: Long, title: String, author: String, url: String?, filePath: String,
        sourcePath: String?, lastModified: Long, sizeBytes: Long, chapters: Int, coverData: String?)

    @Query("UPDATE books SET filePath = :filePath, sourcePath = :sourcePath WHERE id = :id")
    suspend fun updatePaths(id: Long, filePath: String, sourcePath: String?)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<BookEntity>)

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("DELETE FROM books")
    suspend fun clear()

    @Query("SELECT * FROM books WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String?): BookEntity?

    @Query("SELECT * FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun findByFilePath(filePath: String?): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): BookEntity?
}
