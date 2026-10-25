package com.vivi.aharana.model

data class BookItem(
    val title: String,
    val author: String,
    val uriString: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val coverUriString: String? = null,
    val url: String = "",
    val chapters: Int = 0,
    val sourceUriString: String? = null,
    val addedAt: Long = 0L,
    val libraryId: Long = 0L
)
