package com.sunny.localphotoai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class EmbeddingEngine(private val context: Context) {
    companion object {
        const val STORAGE_DIMENSION = 256
        const val MODEL_ASSET_PATH = "models/embeddinggemma-2-text-vision-440m.litertlm"
        private const val TAG = "EmbeddingEngine"
    }

    private var embedder: UniversalEmbedder? = null
    var isGpuAccelerated: Boolean = false
        private set
    var lastError: String? = null
        private set

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (embedder != null) return@withContext true
        lastError = null

        // Explicitly attempt native library load if needed
        runCatching {
            System.loadLibrary("litertlm_jni")
            Log.i(TAG, "Preloaded liblitertlm_jni.so successfully")
        }.onFailure { t ->
            Log.w(TAG, "Notice: System.loadLibrary('litertlm_jni'): ${t.message}")
        }

        // 1. Try Hardware-Accelerated GPU Delegate (Vulkan/OpenCL) via Asset
        try {
            val options = UniversalEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET_PATH)
                        .setDelegate(Delegate.GPU)
                        .build()
                )
                .setL2Normalize(true)
                .build()
            embedder = UniversalEmbedder.createFromOptions(context, options)
            isGpuAccelerated = true
            Log.i(TAG, "Loaded model successfully with GPU acceleration from asset: $MODEL_ASSET_PATH")
            return@withContext true
        } catch (eGpu: Throwable) {
            Log.w(TAG, "GPU asset initialization fallback to CPU: ${eGpu.message}")
        }

        // 2. Try High-Performance CPU Delegate via Asset
        try {
            val options = UniversalEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET_PATH)
                        .setDelegate(Delegate.CPU)
                        .build()
                )
                .setL2Normalize(true)
                .build()
            embedder = UniversalEmbedder.createFromOptions(context, options)
            isGpuAccelerated = false
            Log.i(TAG, "Loaded model successfully with CPU from asset: $MODEL_ASSET_PATH")
            return@withContext true
        } catch (eCpu: Throwable) {
            Log.w(TAG, "Direct asset load failed: ${eCpu.message}, trying FileDescriptor fallback...", eCpu)
            lastError = eCpu.message ?: eCpu.javaClass.simpleName
        }

        // 3. Fallback: Load via extracted internal file descriptor (GPU then CPU)
        try {
            val file = ModelDownloader.modelFile(context)
            if (file.exists() && file.length() > 0) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    // Attempt CPU on extracted file
                    val options = UniversalEmbedderOptions.builder()
                        .setBaseOptions(
                            BaseOptions.builder()
                                .setModelAssetFileDescriptor(pfd.fd)
                                .setDelegate(Delegate.CPU)
                                .build()
                        )
                        .setL2Normalize(true)
                        .build()
                    embedder = UniversalEmbedder.createFromOptions(context, options)
                    isGpuAccelerated = false
                    Log.i(TAG, "Loaded model successfully from file descriptor: ${file.absolutePath}")
                    lastError = null
                    return@withContext true
                }
            }
        } catch (e2: Throwable) {
            Log.e(TAG, "Fallback file descriptor load failed: ${e2.message}", e2)
            lastError = "Asset error: ${lastError ?: "unknown"} | File error: ${e2.message ?: e2.javaClass.simpleName}"
        }

        false
    }

    suspend fun embedText(text: String): FloatArray? = withContext(Dispatchers.Default) {
        runCatching {
            embedder?.embedText(text)?.embeddings()?.firstOrNull()?.floatEmbedding()?.let(::compact)
        }.getOrNull()
    }

    suspend fun embedImage(uri: Uri): FloatArray? = withContext(Dispatchers.Default) {
        runCatching {
            val bmp = loadOptimizedBitmap(uri, 224) ?: return@withContext null
            val mp = BitmapImageBuilder(bmp).build()
            val result = embedder?.embedImage(mp)
            bmp.recycle()
            result?.embeddings()?.firstOrNull()?.floatEmbedding()?.let(::compact)
        }.getOrNull()
    }

    suspend fun embedBitmap(bmp: Bitmap): FloatArray? = withContext(Dispatchers.Default) {
        runCatching {
            val softwareBmp = if (bmp.config == Bitmap.Config.HARDWARE) {
                bmp.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bmp
            }
            val mp = BitmapImageBuilder(softwareBmp).build()
            val result = embedder?.embedImage(mp)
            if (softwareBmp !== bmp) {
                softwareBmp.recycle()
            }
            result?.embeddings()?.firstOrNull()?.floatEmbedding()?.let(::compact)
        }.getOrNull()
    }

    fun loadOptimizedBitmap(uri: Uri, targetSize: Int = 224): Bitmap? {
        // Fast Path 1: System MediaStore thumbnail (2-6ms, bypasses multi-megabyte disk file completely)
        val thumb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(targetSize, targetSize), null)
            }.getOrNull()
        } else null

        if (thumb != null) {
            val softwareBmp = if (thumb.config == Bitmap.Config.HARDWARE) {
                val copy = thumb.copy(Bitmap.Config.ARGB_8888, false)
                thumb.recycle()
                copy
            } else {
                thumb
            }
            if (softwareBmp != null) {
                return if (softwareBmp.width > targetSize || softwareBmp.height > targetSize) {
                    val scaled = resize(softwareBmp, targetSize)
                    if (scaled !== softwareBmp) softwareBmp.recycle()
                    scaled
                } else {
                    softwareBmp
                }
            }
        }

        // Fast Path 2: Sub-sampled decode directly using RGB_565 (2x faster decoding for 5-10MB files)
        return runCatching {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null
            val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
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

            val softwareBmp = if (raw.config != Bitmap.Config.ARGB_8888) {
                val converted = raw.copy(Bitmap.Config.ARGB_8888, false)
                raw.recycle()
                converted
            } else {
                raw
            }

            if (softwareBmp.width > targetSize || softwareBmp.height > targetSize) {
                val scaled = resize(softwareBmp, targetSize)
                if (scaled !== softwareBmp) softwareBmp.recycle()
                scaled
            } else {
                softwareBmp
            }
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