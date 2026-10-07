package com.sunny.localphotoai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
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
        const val MODEL_ASSET_PATH = "models/embeddinggemma-2-text-vision-440m.litertlm"
    }

    private var embedder: UniversalEmbedder? = null
    var lastError: String? = null
        private set

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (embedder != null) return@withContext true
        lastError = null

        // 1. Primary path: Direct zero-copy loading from APK assets
        try {
            val options = UniversalEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET_PATH).build())
                .setL2Normalize(true)
                .build()
            embedder = UniversalEmbedder.createFromOptions(context, options)
            Log.i("EmbeddingEngine", "Model loaded successfully from asset path: $MODEL_ASSET_PATH")
            return@withContext true
        } catch (e: Throwable) {
            Log.w("EmbeddingEngine", "Direct asset load failed: ${e.message}, trying FileDescriptor fallback...", e)
            lastError = e.message ?: e.javaClass.simpleName
        }

        // 2. Fallback path: Load via extracted internal file descriptor
        try {
            val file = ModelDownloader.modelFile(context)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val options = UniversalEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetFileDescriptor(pfd.fd).build())
                    .setL2Normalize(true)
                    .build()
                embedder = UniversalEmbedder.createFromOptions(context, options)
                Log.i("EmbeddingEngine", "Model loaded successfully from extracted file descriptor: ${file.absolutePath}")
                lastError = null
                return@withContext true
            }
        } catch (e2: Throwable) {
            Log.e("EmbeddingEngine", "Fallback file descriptor load failed: ${e2.message}", e2)
            lastError = "Asset error: ${lastError ?: "unknown"} | File error: ${e2.message ?: e2.javaClass.simpleName}"
            false
        }
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