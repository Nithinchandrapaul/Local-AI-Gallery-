package com.sunny.localphotoai

import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** V3 recommendation engine. It never deletes anything by itself. */
class SmartCleanup(private val resolver: ContentResolver) {
    data class Group(
        val items: List<MediaItem>,
        val keeper: MediaItem,
        val removable: List<MediaItem>,
        val reason: String,
        val reclaimableBytes: Long,
    )

    suspend fun analyze(items: List<MediaItem>, threshold: Int = 9, onProgress: (Int) -> Unit): List<Group> = withContext(Dispatchers.IO) {
        val hashes = mutableListOf<Pair<MediaItem, LongArray>>()
        items.forEachIndexed { i, item ->
            PhotoAnalyzer.averageHash(resolver, item.uri)?.let { hashes += item to it }
            onProgress((i + 1) * 100 / items.size.coerceAtLeast(1))
        }

        val used = HashSet<String>()
        val groups = mutableListOf<Group>()
        for (i in hashes.indices) {
            val (base, hash) = hashes[i]
            if (base.id in used) continue
            val group = mutableListOf(base)
            for (j in i + 1 until hashes.size) {
                val (candidate, candidateHash) = hashes[j]
                if (candidate.id !in used && PhotoAnalyzer.hamming(hash, candidateHash) <= threshold) group += candidate
            }
            if (group.size < 2) continue
            val scored = group.map { it to qualityScore(it) }.sortedWith(compareByDescending<Pair<MediaItem, Double>> { it.second }.thenByDescending { it.first.dateTaken })
            val keeper = scored.first().first
            val removable = group.filter { it.id != keeper.id }
            removable.forEach { used += it.id }
            used += keeper.id
            groups += Group(group, keeper, removable, "Visually similar photos", removable.sumOf { it.sizeBytes })
        }
        groups
    }

    private fun qualityScore(item: MediaItem): Double {
        val pixels = item.width.toLong() * item.height.toLong()
        val megapixels = pixels / 1_000_000.0
        val sizeBonus = (item.sizeBytes / 1_000_000.0).coerceAtMost(25.0) / 25.0
        return megapixels * 3.0 + sizeBonus
    }
}
