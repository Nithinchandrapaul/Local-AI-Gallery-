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
                val wa = lower.contains("whatsapp")
                val sent = lower.contains("whatsapp images/sent") || lower.contains("whatsapp/whatsapp images/sent")
                val likelyForwarded = wa && !sent && (
                    lower.contains("images") || lower.contains("media") ||
                    c.getLong(size) < 900_000 || n.startsWith("IMG-")
                )
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
                    isLikelyForwarded = likelyForwarded
                )
            }
        }
        return result
    }
}
