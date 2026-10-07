package com.sunny.localphotoai

import android.content.ContentResolver
import android.provider.MediaStore

class MediaStoreRepository(private val resolver: ContentResolver) {
    fun loadImages(): List<MediaItem> = load(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
    fun loadVideos(): List<MediaItem> = load(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)

    private fun load(collection: android.net.Uri, video: Boolean): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val result = ArrayList<MediaItem>()
        resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val date = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val width = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val height = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val relativePath = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val mediaId = c.getLong(id).toString()
                val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(id))
                result += MediaItem(
                    mediaId, uri, c.getString(name) ?: "", c.getLong(date) * 1000, c.getString(mime),
                    c.getLong(size), if (width >= 0) c.getInt(width) else 0, if (height >= 0) c.getInt(height) else 0, video, if (relativePath >= 0) c.getString(relativePath) ?: "" else ""
                )
            }
        }
        return result
    }
}
