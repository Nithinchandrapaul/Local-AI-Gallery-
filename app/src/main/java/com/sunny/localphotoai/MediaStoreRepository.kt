package com.sunny.localphotoai

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

class MediaStoreRepository(private val context: Context) {
    fun scanImages(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE
        )
        val selection = "${MediaStore.Images.Media.SIZE} > 0"
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sort
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val date = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val width = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val n = c.getString(name) ?: ""
                val p = c.getString(path) ?: ""
                val lower = "$n $p".lowercase()
                val wa = lower.contains("whatsapp") || lower.contains("com.whatsapp")
                val sent = wa && (
                    lower.contains("/sent") || lower.contains("\\sent") ||
                    lower.contains("whatsapp images/sent") || lower.contains("sent/")
                )
                val received = wa && !sent

                // Multi-factor confidence heuristic for likely-forwarded media (0-100)
                var forwardConfidence = 0
                if (received) {
                    forwardConfidence += 25
                    if (n.startsWith("IMG-") && n.contains("-WA")) {
                        forwardConfidence += 25
                    }
                    val w = c.getInt(width)
                    val h = c.getInt(height)
                    val maxDim = maxOf(w, h)
                    if (maxDim in 800..1600) {
                        forwardConfidence += 25
                    }
                    val fileSize = c.getLong(size)
                    if (fileSize in 20_000..650_000) {
                        forwardConfidence += 25
                    }
                }
                val likelyForwarded = forwardConfidence >= 50

                result += MediaItem(
                    id = c.getLong(id),
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                    name = n,
                    path = p,
                    size = c.getLong(size),
                    dateAdded = c.getLong(date),
                    width = c.getInt(width),
                    height = c.getInt(height),
                    mimeType = c.getString(mime) ?: "image/*",
                    isScreenshot = lower.contains("screenshot") || lower.contains("screen_shot"),
                    isWhatsApp = wa,
                    isWhatsAppSent = sent,
                    isWhatsAppReceived = received,
                    isLikelyForwarded = likelyForwarded,
                    whatsAppForwardConfidence = forwardConfidence,
                    isVideo = false,
                    durationMs = 0L
                )
            }
        }
        return result
    }

    fun scanVideos(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION
        )
        val selection = "${MediaStore.Video.Media.SIZE} > 0"
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sort
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val date = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val width = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val mime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                val n = c.getString(name) ?: ""
                val p = c.getString(path) ?: ""
                val lower = "$n $p".lowercase()
                val wa = lower.contains("whatsapp") || lower.contains("com.whatsapp")
                val sent = wa && (
                    lower.contains("/sent") || lower.contains("\\sent") ||
                    lower.contains("whatsapp video/sent") || lower.contains("sent/")
                )
                val received = wa && !sent

                val dur = c.getLong(duration)

                result += MediaItem(
                    id = c.getLong(id),
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                    name = n,
                    path = p,
                    size = c.getLong(size),
                    dateAdded = c.getLong(date),
                    width = c.getInt(width),
                    height = c.getInt(height),
                    mimeType = c.getString(mime) ?: "video/*",
                    isScreenshot = false,
                    isWhatsApp = wa,
                    isWhatsAppSent = sent,
                    isWhatsAppReceived = received,
                    isLikelyForwarded = wa && !sent && (n.startsWith("VID-") || lower.contains("whatsapp")),
                    whatsAppForwardConfidence = if (wa && !sent) 70 else 0,
                    isVideo = true,
                    durationMs = dur
                )
            }
        }
        return result
    }

    fun scanAll(): List<MediaItem> {
        val images = scanImages()
        val videos = scanVideos()
        return (images + videos).sortedByDescending { it.dateAdded }
    }
}
