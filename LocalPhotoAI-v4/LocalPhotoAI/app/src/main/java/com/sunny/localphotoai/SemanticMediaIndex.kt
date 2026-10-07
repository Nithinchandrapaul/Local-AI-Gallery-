package com.sunny.localphotoai

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.retrieval.components.SqliteVectorStore
import com.google.mediapipe.tasks.retrieval.semanticretriever.RetrievalResult
import com.google.mediapipe.tasks.retrieval.semanticretriever.SemanticRetriever
import com.google.mediapipe.tasks.retrieval.semanticretriever.SemanticRetrieverComponents
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate

class SemanticMediaIndex(private val context: Context, private val modelFile: java.io.File) : AutoCloseable {
    private var retriever: SemanticRetriever? = null
    private var embedder: UniversalEmbedder? = null

    fun initialize(useGpu: Boolean = true) {
        val base = BaseOptions.builder().setModelAssetPath(modelFile.absolutePath).build()
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(base)
            .setL2Normalize(true)
            .apply {
                if (useGpu) {
                    setTextDelegate(Delegate.GPU)
                    setVisionDelegate(Delegate.GPU)
                }
            }
            .build()
        embedder = UniversalEmbedder.createFromOptions(context, options)
        val components = SemanticRetrieverComponents()
            .setVectorStore(SqliteVectorStore(context, "photo_ai_vectors"))
            .addProvider(embedder!!.provider)
        retriever = SemanticRetriever.createFromComponents(context, components)
    }

    fun indexImage(item: MediaItem) {
        requireNotNull(retriever).insertImage(item.id, item.uri)
    }

    fun indexAll(items: List<MediaItem>, onProgress: (Int) -> Unit) {
        items.forEachIndexed { index, item ->
            runCatching { indexImage(item) }
            onProgress(((index + 1) * 100 / items.size.coerceAtLeast(1)))
        }
    }

    fun search(query: String, topK: Int = 200): List<RetrievalResult> =
        requireNotNull(retriever).retrieve(query, topK)

    fun deleteRecords(ids: List<String>) { retriever?.delete(ids) }

    override fun close() {
        retriever?.close(); retriever = null
        embedder?.close(); embedder = null
    }
}
