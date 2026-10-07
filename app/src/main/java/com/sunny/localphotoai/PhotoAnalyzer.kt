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

data class QualityReport(
    val blurryCount: Int,
    val badExposureCount: Int,
    val heavilyCompressedCount: Int,
    val lowResolutionCount: Int,
    val burstGroupsCount: Int,
    val lowQualityPhotos: List<MediaItem>,
    val burstGroups: List<List<MediaItem>>
)

data class VideoReport(
    val totalVideos: Int,
    val totalVideoBytes: Long,
    val largeVideos: List<MediaItem>,
    val whatsAppVideos: List<MediaItem>
)

data class CleanupReport(
    val exactDuplicates: List<List<MediaItem>>,
    val visualGroups: List<List<MediaItem>>,
    val burstGroups: List<List<MediaItem>>,
    val screenshots: List<MediaItem>,
    val whatsapp: List<MediaItem>,
    val whatsAppReport: WhatsAppReport,
    val qualityReport: QualityReport,
    val videoReport: VideoReport,
    val likelyForwarded: List<MediaItem>,
    val largeFiles: List<MediaItem>,
    val largeVideos: List<MediaItem>,
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
                if (item.isVideo) {
                    item.copy(
                        sha256 = cleanup.sha256(item.uri),
                        qualityScore = 70,
                        qualityReason = "Video (${item.durationFormatted})"
                    )
                } else {
                    val qual = cleanup.evaluateQuality(item.uri, item.width, item.height, item.size)
                    item.copy(
                        sha256 = cleanup.sha256(item.uri),
                        dHash = cleanup.dHash(item.uri),
                        qualityScore = qual.score,
                        isBlurry = qual.isBlurry,
                        isBadExposure = qual.isBadExposure,
                        isHeavilyCompressed = qual.isHeavilyCompressed,
                        isLowResolution = qual.isLowResolution,
                        qualityReason = qual.reason
                    )
                }
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

            // Burst / Similar-shot sequence intelligence
            val burstGroups = mutableListOf<List<MediaItem>>()
            val burstUsed = mutableSetOf<Long>()
            for (i in enriched.indices) {
                val a = enriched[i]
                if (a.id in burstUsed || a.dHash == null) continue
                val bGroup = mutableListOf(a)
                for (j in i + 1 until enriched.size) {
                    val b = enriched[j]
                    if (b.id in burstUsed || b.dHash == null) continue
                    val timeClose = kotlin.math.abs(a.dateAdded - b.dateAdded) <= 15
                    val visualClose = cleanup.hamming(a.dHash, b.dHash) <= 6
                    if (timeClose && visualClose) {
                        bGroup += b
                    }
                }
                if (bGroup.size > 1) {
                    bGroup.forEach { burstUsed += it.id }
                    burstGroups += bGroup
                }
            }

            val keeperIds = mutableSetOf<Long>()
            val deleteIds = mutableSetOf<Long>()
            exact.forEach { group ->
                val keeper = group.maxWithOrNull(
                    compareBy<MediaItem> { it.qualityScore }
                        .thenBy { it.width.toLong() * it.height.toLong() }
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

            val lowQuality = enriched.filter { it.qualityScore < 45 || it.isBlurry || it.isBadExposure }
            val qualityReport = QualityReport(
                blurryCount = enriched.count { it.isBlurry },
                badExposureCount = enriched.count { it.isBadExposure },
                heavilyCompressedCount = enriched.count { it.isHeavilyCompressed },
                lowResolutionCount = enriched.count { it.isLowResolution },
                burstGroupsCount = burstGroups.size,
                lowQualityPhotos = lowQuality,
                burstGroups = burstGroups
            )

            val videos = enriched.filter { it.isVideo }
            val largeVideos = videos.filter { it.size >= (50 * 1024 * 1024).toLong() }
            val waVideos = videos.filter { it.isWhatsApp }
            val videoReport = VideoReport(
                totalVideos = videos.size,
                totalVideoBytes = videos.sumOf { it.size },
                largeVideos = largeVideos,
                whatsAppVideos = waVideos
            )

            CleanupReport(
                exactDuplicates = exact,
                visualGroups = visual,
                burstGroups = burstGroups,
                screenshots = enriched.filter { it.isScreenshot },
                whatsapp = waItems,
                whatsAppReport = whatsAppReport,
                qualityReport = qualityReport,
                videoReport = videoReport,
                likelyForwarded = waLikelyForwarded,
                largeFiles = enriched.filter { it.size >= (largeMb * 1024 * 1024).toLong() },
                largeVideos = largeVideos,
                blurry = enriched.filter { it.isBlurry },
                lowResolution = enriched.filter { it.isLowResolution },
                keeperIds = keeperIds,
                recommendedDeleteIds = deleteIds,
                totalRecoverableBytes = duplicateBytes
            )
        }
}