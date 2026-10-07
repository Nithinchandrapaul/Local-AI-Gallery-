package com.sunny.localphotoai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CleanupReport(
    val exactDuplicates: List<List<MediaItem>>,
    val visualGroups: List<List<MediaItem>>,
    val screenshots: List<MediaItem>,
    val whatsapp: List<MediaItem>,
    val likelyForwarded: List<MediaItem>,
    val largeFiles: List<MediaItem>,
    val totalRecoverableBytes: Long
)

class PhotoAnalyzer(context: Context) {
    private val cleanup = CleanupEngine(context)

    suspend fun analyze(items: List<MediaItem>, largeMb: Double = 10.0): CleanupReport =
        withContext(Dispatchers.Default) {
            val enriched = items.map { item ->
                item.copy(
                    sha256 = cleanup.sha256(item.uri),
                    dHash = cleanup.dHash(item.uri)
                )
            }

            val exact = enriched.groupBy { it.sha256 }
                .filterKeys { it != null }
                .values.filter { it.size > 1 }

            val visual = mutableListOf<List<MediaItem>>()
            val used = mutableSetOf<Long>()
            for (i in enriched.indices) {
                val a = enriched[i]
                if (a.id in used || a.dHash == null) continue
                val group = mutableListOf(a)
                for (j in i + 1 until enriched.size) {
                    val b = enriched[j]
                    if (b.id in used || b.dHash == null) continue
                    if (cleanup.hamming(a.dHash, b.dHash) <= 8) group += b
                }
                if (group.size > 1) {
                    group.forEach { used += it.id }
                    visual += group
                }
            }

            val duplicateBytes = exact.sumOf { group -> group.drop(1).sumOf { it.size } }
            CleanupReport(
                exactDuplicates = exact,
                visualGroups = visual,
                screenshots = enriched.filter { it.isScreenshot },
                whatsapp = enriched.filter { it.isWhatsApp },
                likelyForwarded = enriched.filter { it.isLikelyForwarded },
                largeFiles = enriched.filter { it.size >= (largeMb * 1024 * 1024).toLong() },
                totalRecoverableBytes = duplicateBytes
            )
        }
}
