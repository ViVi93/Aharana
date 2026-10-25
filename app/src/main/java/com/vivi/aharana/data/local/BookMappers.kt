package com.vivi.aharana.data.local

import com.vivi.aharana.model.BookItem

fun BookItem.toEntity(now: Long = System.currentTimeMillis()): BookEntity = BookEntity(
    id = libraryId,
    title = title,
    author = author,
    url = url.ifBlank { null },
    filePath = uriString,
    sourcePath = sourceUriString,
    lastModified = lastModified,
    sizeBytes = sizeBytes,
    coverData = coverUriString,
    chapters = chapters,
    addedAt = if (addedAt > 0) addedAt else now
)

fun BookEntity.toBookItem(): BookItem = BookItem(
    libraryId = id,
    title = title,
    author = author,
    uriString = filePath,
    lastModified = lastModified,
    sizeBytes = sizeBytes,
    coverUriString = coverData,
    url = url.orEmpty(),
    chapters = chapters,
    sourceUriString = sourcePath,
    addedAt = if (addedAt > 0) addedAt else lastModified
)
