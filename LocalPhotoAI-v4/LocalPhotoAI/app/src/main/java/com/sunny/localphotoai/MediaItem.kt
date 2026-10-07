package com.sunny.localphotoai

import android.net.Uri

data class MediaItem(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val dateTaken: Long,
    val mimeType: String?,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val isVideo: Boolean = false,
    val relativePath: String = "",
)
