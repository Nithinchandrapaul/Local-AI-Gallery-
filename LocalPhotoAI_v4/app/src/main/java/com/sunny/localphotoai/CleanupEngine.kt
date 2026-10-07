package com.sunny.localphotoai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.BufferedInputStream
import java.security.MessageDigest
import kotlin.math.abs

class CleanupEngine(private val context: Context) {
    fun sha256(uri: Uri): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        } ?: return null
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun dHash(uri: Uri): Long? = runCatching {
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(BufferedInputStream(it))
        } ?: return null
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        if (small !== bitmap) bitmap.recycle()
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val a = luminance(small.getPixel(x, y))
                val b = luminance(small.getPixel(x + 1, y))
                hash = (hash shl 1) or if (a > b) 1L else 0L
            }
        }
        small.recycle()
        hash
    }.getOrNull()

    private fun luminance(pixel: Int): Int =
        ((pixel shr 16 and 0xff) * 299 +
         (pixel shr 8 and 0xff) * 587 +
         (pixel and 0xff) * 114) / 1000

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
