package com.sunny.localphotoai

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface

class MediaStoreRepository(private val context: Context) {

    fun inspectCameraExif(uri: Uri): Pair<Boolean, String?> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                val hasCamera = (!make.isNullOrBlank() || !model.isNullOrBlank() || !dt.isNullOrBlank() || !focal.isNullOrBlank() || !iso.isNullOrBlank())
                val cameraName = listOfNotNull(make, model).joinToString(" ").trim()
                Pair(hasCamera, if (cameraName.isNotBlank()) cameraName else null)
            } ?: Pair(false, null)
        } catch (_: Throwable) {
            Pair(false, null)
        }
    }

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
                val isDoc = lower.contains("document") || lower.contains("documents")

                // Multi-factor confidence heuristic for forwarded media
                // Personal camera photos sent via WhatsApp have camera aspect ratios or document flags
                val w = c.getInt(width)
                val h = c.getInt(height)
                val maxDim = maxOf(w, h)
                val minDim = minOf(w, h)
                val fileSize = c.getLong(size)

                var forwardConfidence = 0
                var isPersonalCamera = false

                if (received) {
                    if (isDoc || fileSize > 1_500_000 || maxDim > 1920) {
                        // High-res or document transfer: Almost certainly an original/personal photo
                        isPersonalCamera = true
                        forwardConfidence = 0
                    } else {
                        // Standard WhatsApp image: start with low baseline
                        forwardConfidence += 15
                        if (n.startsWith("IMG-") && n.contains("-WA")) {
                            forwardConfidence += 15
                        }
                        // Non-standard camera aspect ratio or square/banner usually indicates memes or flyers
                        val aspect = if (minDim > 0) maxDim.toFloat() / minDim else 1f
                        if (aspect < 1.1f || aspect > 2.2f) {
                            forwardConfidence += 25
                        }
                        if (fileSize in 15_000..350_000) {
                            forwardConfidence += 15
                        }
                    }
                }

                // Strict threshold (70+) before labeling as likely forwarded
                val likelyForwarded = forwardConfidence >= 70 && !isPersonalCamera

                result += MediaItem(
                    id = c.getLong(id),
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                    name = n,
                    path = p,
                    size = fileSize,
                    dateAdded = c.getLong(date),
                    width = w,
                    height = h,
                    mimeType = c.getString(mime) ?: "image/*",
                    isScreenshot = lower.contains("screenshot") || lower.contains("screen_shot"),
                    isWhatsApp = wa,
                    isWhatsAppSent = sent,
                    isWhatsAppReceived = received,
                    isLikelyForwarded = likelyForwarded,
                    whatsAppForwardConfidence = forwardConfidence,
                    isPersonalCameraPhoto = isPersonalCamera,
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
                    isLikelyForwarded = wa && !sent && (n.startsWith("VID-") && c.getLong(size) < 10_000_000),
                    whatsAppForwardConfidence = if (wa && !sent) 40 else 0,
                    isVideo = true,
                    durationMs = dur
                )
            }
        }
        return result
    }

    fun scanTrashed(): List<MediaItem> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val result = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.MediaColumns.DATE_EXPIRES
        )
        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_EXPIRES} ASC")
        }
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                bundle,
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val widthCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val expCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
                val nowSec = System.currentTimeMillis() / 1000
                while (c.moveToNext()) {
                    val exp = if (expCol >= 0) c.getLong(expCol) else 0L
                    val days = if (exp > nowSec) ((exp - nowSec) / 86400).toInt() else 30
                    result += MediaItem(
                        id = c.getLong(idCol),
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)),
                        name = c.getString(nameCol) ?: "",
                        path = c.getString(pathCol) ?: "",
                        size = c.getLong(sizeCol),
                        dateAdded = c.getLong(dateCol),
                        width = c.getInt(widthCol),
                        height = c.getInt(heightCol),
                        mimeType = c.getString(mimeCol) ?: "image/*",
                        isTrashed = true,
                        trashedDaysRemaining = days
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("MediaStoreRepo", "scanTrashed note: ${e.message}")
        }
        return result
    }

    fun scanAll(): List<MediaItem> {
        val images = scanImages()
        val videos = scanVideos()
        return (images + videos).sortedByDescending { it.dateAdded }
    }
}
