package com.sunny.localphotoai

import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CleanupEngine(private val resolver: ContentResolver) {
    suspend fun findExactDuplicates(items: List<MediaItem>, onProgress: (Int) -> Unit): List<PhotoAnalyzer.DuplicateGroup> = withContext(Dispatchers.IO) {
        val groups = LinkedHashMap<String, MutableList<MediaItem>>()
        items.forEachIndexed { index, item ->
            runCatching { PhotoAnalyzer.sha256(resolver, item.uri) }.getOrNull()?.let { groups.getOrPut(it) { mutableListOf() }.add(item) }
            onProgress(((index + 1) * 100 / items.size.coerceAtLeast(1)))
        }
        groups.filterValues { it.size > 1 }.map { PhotoAnalyzer.DuplicateGroup(it.key, it.value) }
    }

    suspend fun findSimilarCandidates(items: List<MediaItem>, onProgress: (Int) -> Unit): List<List<MediaItem>> = withContext(Dispatchers.IO) {
        val hashes = mutableListOf<Pair<MediaItem, LongArray>>()
        items.forEachIndexed { index, item ->
            PhotoAnalyzer.averageHash(resolver, item.uri)?.let { hashes += item to it }
            onProgress(((index + 1) * 100 / items.size.coerceAtLeast(1)))
        }
        val consumed = HashSet<String>()
        val groups = mutableListOf<List<MediaItem>>()
        for (i in hashes.indices) {
            val (item, hash) = hashes[i]
            if (item.id in consumed) continue
            val group = mutableListOf(item)
            for (j in i + 1 until hashes.size) {
                val (other, otherHash) = hashes[j]
                if (other.id !in consumed && PhotoAnalyzer.hamming(hash, otherHash) <= 10) group += other
            }
            if (group.size > 1) {
                group.forEach { consumed += it.id }
                groups += group
            }
        }
        groups
    }

    suspend fun findBlurry(items: List<MediaItem>, onProgress: (Int) -> Unit): List<MediaItem> = withContext(Dispatchers.IO) {
        items.filterIndexed { index, item ->
            val blurry = runCatching { PhotoAnalyzer.isLikelyBlurry(resolver, item.uri) }.getOrDefault(false)
            onProgress(((index + 1) * 100 / items.size.coerceAtLeast(1)))
            blurry
        }
    }
}
