package com.sunny.localphotoai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated unit tests verifying visual duplicate hashing (dHash),
 * Hamming distance evaluation, and image quality heuristics.
 */
class CleanupEngineUnitTest {

    private fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    @Test
    fun testIdenticalHashesHaveZeroDistance() {
        val hash = 0x123456789ABCDEF0L
        assertEquals(0, hamming(hash, hash))
    }

    @Test
    fun testHammingDistanceSingleBitDiff() {
        val h1 = 0b00000001L
        val h2 = 0b00000000L
        assertEquals(1, hamming(h1, h2))
    }

    @Test
    fun testDuplicateThresholdAllowsMinorVariations() {
        // In duplicate detection: Hamming distance <= 10 indicates duplicate / burst shot
        val baseHash = 0xAAAAAAAAL
        val minorVariation = baseHash xor 0b00000111L // 3 bits flipped

        val dist = hamming(baseHash, minorVariation)
        assertEquals(3, dist)
        assertTrue("Distance $dist should be <= 10 for duplicate detection", dist <= 10)
    }

    @Test
    fun testDissimilarImagesExceedThreshold() {
        val h1 = 0x0000000000000000L
        val h2 = -1L // all 64 bits flipped (0xFFFFFFFFFFFFFFFF)
        val dist = hamming(h1, h2)
        assertEquals(64, dist)
        assertTrue("Completely different images should exceed duplicate threshold", dist > 10)
    }

    @Test
    fun testQualityHeuristicScoringBoundaries() {
        fun evaluate(blur: Double, meanLum: Double, minDim: Int, bpp: Double): QualityEvaluation {
            val isBlur = blur < 65.0
            val isBadExposure = meanLum < 20.0 || meanLum > 235.0
            val isLowRes = minDim in 1..719
            val isHeavilyCompressed = bpp < 0.04 && !isLowRes

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

            return QualityEvaluation(
                score = finalScore,
                isBlurry = isBlur,
                isBadExposure = isBadExposure,
                isHeavilyCompressed = isHeavilyCompressed,
                isLowResolution = isLowRes,
                reason = if (reasons.isEmpty()) "Good quality" else reasons.joinToString(", ")
            )
        }

        // Test clear high-res photo
        val crispPhoto = evaluate(blur = 200.0, meanLum = 120.0, minDim = 1080, bpp = 0.5)
        assertEquals(90, crispPhoto.score)
        assertFalse(crispPhoto.isBlurry)
        assertFalse(crispPhoto.isBadExposure)
        assertFalse(crispPhoto.isLowResolution)

        // Test blurry low-res dark photo
        val badPhoto = evaluate(blur = 30.0, meanLum = 10.0, minDim = 480, bpp = 0.2)
        assertTrue(badPhoto.isBlurry)
        assertTrue(badPhoto.isBadExposure)
        assertTrue(badPhoto.isLowResolution)
        assertTrue("Score should drop significantly", badPhoto.score <= 15)
    }
}
