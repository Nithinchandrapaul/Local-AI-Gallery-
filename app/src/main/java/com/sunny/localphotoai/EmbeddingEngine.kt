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

class EmbeddingEngine(private val context: Context) {
    private var embedder: UniversalEmbedder? = null

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (embedder != null) return@withContext true
        val model = ModelDownloader.ensureModel(context) ?: return@withContext false
        runCatching {
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
            embedder?.embedText(text)?.embeddings()?.firstOrNull()?.floatEmbedding()?.toFloatArray()
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
            result?.embeddings()?.firstOrNull()?.floatEmbedding()?.toFloatArray()
        }.getOrNull()
    }

    fun close() {
        embedder?.close()
        embedder = null
    }

    private fun resize(src: Bitmap, max: Int): Bitmap {
        val scale = minOf(1f, max.toFloat() / maxOf(src.width, src.height))
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }
}
