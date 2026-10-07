package com.sunny.localphotoai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class EmbeddingEngine(private val context: Context) {
    companion object {
        const val STORAGE_DIMENSION = 256
    }

    private var embedder: UniversalEmbedder? = null

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (embedder != null) return@withContext true
        runCatching {
            val model = ModelDownloader.modelFile(context)
            val options = UniversalEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.absolutePath).build())
                .setL2Normalize(true)
                .build()
            embedder = UniversalEmbedder.createFromOptions(context, options)
            true
        }.getOrDefault(false)
    }

    suspend fun embedText(text: String): FloatArray? = withContext(Dispatchers.Default) {
        runCatching {
            embedder?.embedText(text)?.embeddings()?.firstOrNull()?.floatEmbedding()?.let(::compact)
        }.getOrNull()
    }

    suspend fun embedImage(uri: Uri): FloatArray? = withContext(Dispatchers.Default) {
        runCatching {
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return@withContext null
            val resized = resize(bmp, 768)
            if (resized !== bmp) bmp.recycle()
            val mp = BitmapImageBuilder(resized).build()
            val result = embedder?.embedImage(mp)
            resized.recycle()
            result?.embeddings()?.firstOrNull()?.floatEmbedding()?.let(::compact)
        }.getOrNull()
    }

    fun close() {
        embedder?.close()
        embedder = null
    }

    private fun compact(full: FloatArray): FloatArray {
        if (full.size <= STORAGE_DIMENSION) return full
        val out = full.copyOf(STORAGE_DIMENSION)
        var norm = 0.0
        for (v in out) norm += v * v
        val scale = if (norm > 0.0) 1.0 / sqrt(norm) else 1.0
        for (i in out.indices) out[i] = (out[i] * scale).toFloat()
        return out
    }

    private fun resize(src: Bitmap, max: Int): Bitmap {
        val scale = minOf(1f, max.toFloat() / maxOf(src.width, src.height))
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }
}