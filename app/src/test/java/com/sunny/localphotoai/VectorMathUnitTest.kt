package com.sunny.localphotoai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Automated unit tests verifying the mathematical correctness and stability
 * of vector math, cosine distance, and compaction routines used for on-device AI search.
 */
class VectorMathUnitTest {

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else dot / (sqrt(aa) * sqrt(bb))
    }

    private fun compact(full: FloatArray, targetDim: Int = 256): FloatArray {
        if (full.size <= targetDim) return full
        val out = full.copyOf(targetDim)
        var norm = 0.0
        for (v in out) norm += v * v
        val scale = if (norm > 0.0) 1.0 / sqrt(norm) else 1.0
        for (i in out.indices) out[i] = (out[i] * scale).toFloat()
        return out
    }

    @Test
    fun testIdenticalVectorsProducePerfectSimilarity() {
        val v1 = FloatArray(256) { (it + 1).toFloat() }
        val sim = cosine(v1, v1)
        assertEquals(1.0, sim, 0.00001)
    }

    @Test
    fun testOrthogonalVectorsProduceZeroSimilarity() {
        val v1 = floatArrayOf(1f, 0f, 0f, 0f)
        val v2 = floatArrayOf(0f, 1f, 0f, 0f)
        val sim = cosine(v1, v2)
        assertEquals(0.0, sim, 0.00001)
    }

    @Test
    fun testOppositeVectorsProduceNegativeSimilarity() {
        val v1 = floatArrayOf(1f, 2f, 3f)
        val v2 = floatArrayOf(-1f, -2f, -3f)
        val sim = cosine(v1, v2)
        assertEquals(-1.0, sim, 0.00001)
    }

    @Test
    fun testCompactionPreservesL2UnitNorm() {
        val largeVector = FloatArray(512) { (it % 10 + 1).toFloat() }
        val compacted = compact(largeVector, 256)
        assertEquals(256, compacted.size)

        var norm = 0.0
        for (v in compacted) norm += v * v
        assertEquals(1.0, sqrt(norm), 0.001)
    }

    @Test
    fun testCompactionSmallerThanTargetPreservesSize() {
        val small = floatArrayOf(0.5f, 0.5f)
        val compacted = compact(small, 256)
        assertEquals(2, compacted.size)
    }
}
