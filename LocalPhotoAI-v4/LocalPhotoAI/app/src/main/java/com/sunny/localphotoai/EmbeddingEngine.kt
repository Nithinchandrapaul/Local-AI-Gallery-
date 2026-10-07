package com.sunny.localphotoai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions

/** Real EmbeddingGemma 2 engine. The model is downloaded into filesDir/models. */
class EmbeddingEngine(private val context: Context, private val modelFile: java.io.File) : AutoCloseable {
    private var embedder: UniversalEmbedder? = null

    fun initialize(useGpu: Boolean = true) {
        val base = BaseOptions.builder().setModelAssetPath(modelFile.absolutePath).build()
        val builder = UniversalEmbedderOptions.builder()
            .setBaseOptions(base)
            .setL2Normalize(true)
        if (useGpu) {
            builder.setTextDelegate(Delegate.GPU)
            builder.setVisionDelegate(Delegate.GPU)
        }
        embedder = UniversalEmbedder.createFromOptions(context, builder.build())
    }

    fun embedText(text: String): FloatArray {
        val e = requireNotNull(embedder) { "Embedding engine not initialized" }
        return e.embedText(text).embeddings()[0].floatEmbedding()
    }

    fun embedBitmap(bitmap: Bitmap): FloatArray {
        val e = requireNotNull(embedder) { "Embedding engine not initialized" }
        val image = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
        return e.embedImage(image).embeddings()[0].floatEmbedding()
    }

    fun embedUri(uri: Uri): FloatArray {
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
        } ?: error("Unable to decode $uri")
        return embedBitmap(bitmap)
    }

    override fun close() { embedder?.close(); embedder = null }
}
