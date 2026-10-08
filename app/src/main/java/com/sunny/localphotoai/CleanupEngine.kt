package com.sunny.localphotoai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import java.security.MessageDigest
import kotlin.math.abs

data class VisualAnalysisResult(
    val dHash: Long?,
    val quality: QualityEvaluation
)

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

    /** Loads hardware-cached or sub-sampled fast thumbnail (5-15ms). */
    fun loadThumbnailFast(uri: Uri, targetSize: Int = 128): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return context.contentResolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
            }
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxDim / (sample * 2) >= targetSize) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null
            if (raw.width > targetSize || raw.height > targetSize) {
                val scale = minOf(1f, targetSize.toFloat() / maxOf(raw.width, raw.height))
                val scaled = Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true)
                if (scaled !== raw) raw.recycle()
                scaled
            } else {
                raw
            }
        }.getOrNull()
    }

    /** Single-pass visual analysis: computes dHash, blur score, and exposure from 1 thumbnail in <10ms. */
    fun analyzeVisuals(uri: Uri, width: Int, height: Int, size: Long): VisualAnalysisResult {
        val thumb = loadThumbnailFast(uri, 128)
        if (thumb == null) {
            return VisualAnalysisResult(
                dHash = null,
                quality = QualityEvaluation(
                    score = 60,
                    isBlurry = false,
                    isBadExposure = false,
                    isHeavilyCompressed = false,
                    isLowResolution = minOf(width, height) in 1..719,
                    reason = "Standard"
                )
            )
        }

        try {
            // 1. dHash (9x8 matrix)
            val small9x8 = Bitmap.createScaledBitmap(thumb, 9, 8, true)
            var hash = 0L
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val a = luminance(small9x8.getPixel(x, y))
                    val b = luminance(small9x8.getPixel(x + 1, y))
                    hash = (hash shl 1) or if (a > b) 1L else 0L
                }
            }
            if (small9x8 !== thumb) small9x8.recycle()

            // 2. Fast Laplacian Blur & Exposure from the 128px thumbnail
            val w = thumb.width
            val h = thumb.height
            var sum = 0.0
            var sumSq = 0.0
            var count = 0
            var totalLum = 0L

            for (y in 1 until h - 1 step 2) {
                for (x in 1 until w - 1 step 2) {
                    val c = luminance(thumb.getPixel(x, y))
                    totalLum += c
                    val top = luminance(thumb.getPixel(x, y - 1))
                    val bot = luminance(thumb.getPixel(x, y + 1))
                    val left = luminance(thumb.getPixel(x - 1, y))
                    val right = luminance(thumb.getPixel(x + 1, y))
                    val lap = abs(4 * c - top - bot - left - right).toDouble()
                    sum += lap
                    sumSq += lap * lap
                    count++
                }
            }

            val blur = if (count > 0) {
                val mean = sum / count
                maxOf(0.0, (sumSq / count) - mean * mean)
            } else 50.0

            val meanLum = if (count > 0) totalLum.toDouble() / count else 128.0
            val isBlur = blur < 65.0
            val isBadExposure = meanLum < 20.0 || meanLum > 235.0
            val minDim = minOf(width, height)
            val isLowRes = minDim in 1..719

            val pixels = maxOf(1L, width.toLong() * height.toLong())
            val bytesPerPixel = size.toDouble() / pixels
            val isHeavilyCompressed = bytesPerPixel < 0.04 && !isLowRes

            var score = 70
            if (isBlur) score -= 35 else if (blur > 180.0) score += 10
            if (isLowRes) score -= 20 else if (minDim >= 1080) score += 10
            if (isBadExposure) score -= 25
            if (isHeavilyCompressed) score -= 15

            val finalScore = score.coerceIn(5, 100)
            val reasons = mutableListOf<String>()
            if (isBlur) reasons += "Blurry"
            if (isLowRes) reasons += "Low res (${minDim}p)"
            if (isBadExposure) reasons += if (meanLum < 20.0) "Underexposed" else "Overexposed"
            if (isHeavilyCompressed) reasons += "Heavy compression"

            val quality = QualityEvaluation(
                score = finalScore,
                isBlurry = isBlur,
                isBadExposure = isBadExposure,
                isHeavilyCompressed = isHeavilyCompressed,
                isLowResolution = isLowRes,
                reason = if (reasons.isEmpty()) "Good quality" else reasons.joinToString(", ")
            )

            return VisualAnalysisResult(dHash = hash, quality = quality)
        } finally {
            thumb.recycle()
        }
    }

    fun dHash(uri: Uri): Long? = analyzeVisuals(uri, 0, 0, 0).dHash

    fun blurScore(uri: Uri): Double? = runCatching {
        loadThumbnailFast(uri, 96)?.let { small ->
            try {
                var sum = 0.0
                var sumSq = 0.0
                var count = 0
                for (y in 1 until small.height - 1) {
                    for (x in 1 until small.width - 1) {
                        val c = luminance(small.getPixel(x, y))
                        val top = luminance(small.getPixel(x, y - 1))
                        val bot = luminance(small.getPixel(x, y + 1))
                        val left = luminance(small.getPixel(x - 1, y))
                        val right = luminance(small.getPixel(x + 1, y))
                        val lap = abs(4 * c - top - bot - left - right).toDouble()
                        sum += lap
                        sumSq += lap * lap
                        count++
                    }
                }
                if (count == 0) null else {
                    val mean = sum / count
                    (sumSq / count) - mean * mean
                }
            } finally {
                small.recycle()
            }
        }
    }.getOrNull()

    fun evaluateQuality(uri: Uri, width: Int, height: Int, size: Long): QualityEvaluation =
        analyzeVisuals(uri, width, height, size).quality

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun luminance(pixel: Int): Int =
        ((pixel shr 16 and 0xff) * 299 +
         (pixel shr 8 and 0xff) * 587 +
         (pixel and 0xff) * 114) / 1000
}

data class QualityEvaluation(
    val score: Int,
    val isBlurry: Boolean,
    val isBadExposure: Boolean,
    val isHeavilyCompressed: Boolean,
    val isLowResolution: Boolean,
    val reason: String
)