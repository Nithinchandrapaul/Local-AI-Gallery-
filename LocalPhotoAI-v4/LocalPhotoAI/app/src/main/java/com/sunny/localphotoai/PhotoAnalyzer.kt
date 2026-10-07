package com.sunny.localphotoai

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.abs

/** Local, non-AI cleanup signals. These complement semantic search. */
object PhotoAnalyzer {
    data class DuplicateGroup(val hash: String, val items: List<MediaItem>)

    fun sha256(resolver: ContentResolver, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open $uri" }
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun averageHash(resolver: ContentResolver, uri: Uri, size: Int = 16): LongArray? {
        val bitmap = decode(resolver, uri, 256) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        bitmap.recycle()
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        scaled.recycle()
        val values = pixels.map { p ->
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            0.299 * r + 0.587 * g + 0.114 * b
        }
        val avg = values.average()
        return LongArray((size * size + 63) / 64) { word ->
            var v = 0L
            for (bit in 0 until 64) {
                val index = word * 64 + bit
                if (index < values.size && values[index] >= avg) v = v or (1L shl bit)
            }
            v
        }
    }

    fun hamming(a: LongArray, b: LongArray): Int {
        val n = minOf(a.size, b.size)
        var d = 0
        for (i in 0 until n) d += java.lang.Long.bitCount(a[i] xor b[i])
        return d + abs(a.size - b.size) * 64
    }

    fun sharpnessScore(resolver: ContentResolver, uri: Uri): Double {
        val bitmap = decode(resolver, uri, 512) ?: return 0.0
        val w = bitmap.width; val h = bitmap.height
        if (w < 3 || h < 3) { bitmap.recycle(); return 0.0 }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        var sum = 0.0; var sumSq = 0.0; var count = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            fun gray(p: Int): Double {
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                return 0.299 * r + 0.587 * g + 0.114 * b
            }
            val c = gray(pixels[y * w + x])
            val lap = gray(pixels[(y - 1) * w + x]) + gray(pixels[(y + 1) * w + x]) +
                    gray(pixels[y * w + x - 1]) + gray(pixels[y * w + x + 1]) - 4 * c
            sum += lap; sumSq += lap * lap; count++
        }
        val mean = sum / count
        val variance = (sumSq / count) - mean * mean
        return variance
    }

    fun isLikelyBlurry(resolver: ContentResolver, uri: Uri, threshold: Double = 55.0): Boolean =
        sharpnessScore(resolver, uri) < threshold

    private fun decode(resolver: ContentResolver, uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        return resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
