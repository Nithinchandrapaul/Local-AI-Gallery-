package com.sunny.localphotoai

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val path: String,
    val size: Long,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val sha256: String? = null,
    val dHash: Long? = null,
    val isScreenshot: Boolean = false,
    val isWhatsApp: Boolean = false,
    val isLikelyForwarded: Boolean = false
) {
    val sizeMb: Double get() = size / 1024.0 / 1024.0
}
