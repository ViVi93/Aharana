package com.vivi.aharana.data.local

import com.vivi.aharana.model.BookItem

/** Shelf/coordination projection. Deliberately cannot hold inline coverData. */
data class LibraryBookRow(
    val id: Long,
    val title: String,
    val author: String,
    val url: String?,
    val filePath: String,
    val sourcePath: String?,
    val lastModified: Long,
    val sizeBytes: Long,
    val chapters: Int,
    val addedAt: Long
)

fun LibraryBookRow.toBookItem(): BookItem = BookItem(
    libraryId = id,
    title = title,
    author = author,
    uriString = filePath,
    lastModified = lastModified,
    sizeBytes = sizeBytes,
    url = url.orEmpty(),
    chapters = chapters,
    sourceUriString = sourcePath,
    addedAt = if (addedAt > 0) addedAt else lastModified
)
