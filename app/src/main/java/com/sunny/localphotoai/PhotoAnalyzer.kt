package com.sunny.localphotoai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WhatsAppReport(
    val totalCount: Int,
    val totalBytes: Long,
    val receivedCount: Int,
    val sentCount: Int,
    val sentMedia: List<MediaItem>,
    val likelyForwarded: List<MediaItem>,
    val largeMedia: List<MediaItem>,
    val duplicateGroups: List<List<MediaItem>>,
    val recoverableBytes: Long
)

data class CleanupReport(
    val exactDuplicates: List<List<MediaItem>>,
    val visualGroups: List<List<MediaItem>>,
    val screenshots: List<MediaItem>,
    val whatsapp: List<MediaItem>,
    val whatsAppReport: WhatsAppReport,
    val likelyForwarded: List<MediaItem>,
    val largeFiles: List<MediaItem>,
    val blurry: List<MediaItem>,
    val lowResolution: List<MediaItem>,
    val keeperIds: Set<Long>,
    val recommendedDeleteIds: Set<Long>,
    val totalRecoverableBytes: Long
)

class PhotoAnalyzer(context: Context) {
    private val cleanup = CleanupEngine(context)

    suspend fun analyze(items: List<MediaItem>, largeMb: Double = 10.0): CleanupReport =
        withContext(Dispatchers.Default) {
            val enriched = items.map { item ->
                val blur = cleanup.blurScore(item.uri)
                item.copy(
                    sha256 = cleanup.sha256(item.uri),
                    dHash = cleanup.dHash(item.uri),
                    isBlurry = blur != null && blur < 80.0,
                    isLowResolution = minOf(item.width, item.height) < 720
                )
            }

            val exact = enriched.groupBy { it.sha256 }
                .filterKeys { it != null }.values.filter { it.size > 1 }

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

            val keeperIds = mutableSetOf<Long>()
            val deleteIds = mutableSetOf<Long>()
            exact.forEach { group ->
                val keeper = group.maxWithOrNull(
                    compareBy<MediaItem> { it.width.toLong() * it.height.toLong() }
                        .thenBy { it.size }
                        .thenBy { it.dateAdded }
                ) ?: return@forEach
                keeperIds += keeper.id
                group.filter { it.id != keeper.id }.forEach { deleteIds += it.id }
            }

            val duplicateBytes = exact.sumOf { group ->
                group.filter { it.id in deleteIds }.sumOf { it.size }
            }

            val waItems = enriched.filter { it.isWhatsApp }
            val waSent = waItems.filter { it.isWhatsAppSent }
            val waLikelyForwarded = waItems.filter { it.isLikelyForwarded }
            val waLarge = waItems.filter { it.size >= (2 * 1024 * 1024).toLong() }
            val waDuplicates = waItems.groupBy { it.sha256 }
                .filterKeys { it != null }.values.filter { it.size > 1 }
            val waRecoverable = waDuplicates.sumOf { group ->
                group.drop(1).sumOf { it.size }
            }
            val whatsAppReport = WhatsAppReport(
                totalCount = waItems.size,
                totalBytes = waItems.sumOf { it.size },
                receivedCount = waItems.count { it.isWhatsAppReceived },
                sentCount = waSent.size,
                sentMedia = waSent,
                likelyForwarded = waLikelyForwarded,
                largeMedia = waLarge,
                duplicateGroups = waDuplicates,
                recoverableBytes = waRecoverable
            )

            CleanupReport(
                exactDuplicates = exact,
                visualGroups = visual,
                screenshots = enriched.filter { it.isScreenshot },
                whatsapp = waItems,
                whatsAppReport = whatsAppReport,
                likelyForwarded = waLikelyForwarded,
                largeFiles = enriched.filter { it.size >= (largeMb * 1024 * 1024).toLong() },
                blurry = enriched.filter { it.isBlurry },
                lowResolution = enriched.filter { it.isLowResolution },
                keeperIds = keeperIds,
                recommendedDeleteIds = deleteIds,
                totalRecoverableBytes = duplicateBytes
            )
        }
}