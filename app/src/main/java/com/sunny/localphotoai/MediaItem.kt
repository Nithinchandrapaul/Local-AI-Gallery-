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
    val isWhatsAppSent: Boolean = false,
    val isWhatsAppReceived: Boolean = false,
    val isLikelyForwarded: Boolean = false,
    val whatsAppForwardConfidence: Int = 0,
    val isBlurry: Boolean = false,
    val isLowResolution: Boolean = false,
    val qualityScore: Int = 50,
    val isHeavilyCompressed: Boolean = false,
    val isBadExposure: Boolean = false,
    val qualityReason: String? = null,
    val isBurstCandidate: Boolean = false,
    val isVideo: Boolean = false,
    val durationMs: Long = 0L
) {
    val sizeMb: Double get() = size / 1024.0 / 1024.0
    val isLargeVideo: Boolean get() = isVideo && size > 50 * 1024 * 1024

    val durationFormatted: String
        get() {
            if (!isVideo || durationMs <= 0) return ""
            val totalSec = durationMs / 1000
            val sec = totalSec % 60
            val min = (totalSec / 60) % 60
            val hrs = totalSec / 3600
            return if (hrs > 0) {
                "%d:%02d:%02d".format(hrs, min, sec)
            } else {
                "%02d:%02d".format(min, sec)
            }
        }
}