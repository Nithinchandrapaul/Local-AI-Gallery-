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

class PhotoAnalyzer(private val context: Context) {
    private val cleanup = CleanupEngine(context)
    private val repo = MediaStoreRepository(context)
    private val index = SemanticMediaIndex(context)

    suspend fun analyze(
        items: List<MediaItem>,
        largeMb: Double = 10.0,
        forceRefresh: Boolean = false,
        onProgress: ((current: Int, total: Int, currentItem: MediaItem) -> Unit)? = null
    ): CleanupReport = withContext(Dispatchers.Default) {
        val total = items.size

        // Step 1: Check persistent SQLite cache for instant reload
        val cachedMap = if (forceRefresh) emptyMap() else index.loadCleanupCache()

        // Step 2: Pre-identify candidates for exact SHA-256 duplicate checking by file size.
        val sizeCollisions = items.groupBy { it.size }.filter { it.value.size > 1 }.mapValues { (_, v) -> v.map { it.id }.toSet() }
        val collisionIds = sizeCollisions.values.flatten().toSet()

        val newlyAnalyzed = mutableListOf<MediaItem>()

        val enriched = items.mapIndexed { indexIdx, item ->
            val cached = cachedMap[item.id]
            val result = if (cached != null && cached.size == item.size && cached.dateAdded == item.dateAdded) {
                // Instant cache hit: 0ms overhead
                item.copy(
                    sha256 = cached.sha256,
                    dHash = cached.dHash,
                    qualityScore = cached.qualityScore,
                    isBlurry = cached.isBlurry,
                    isBadExposure = cached.isBadExposure,
                    isHeavilyCompressed = cached.isHeavilyCompressed,
                    isLowResolution = cached.isLowResolution,
                    qualityReason = cached.qualityReason,
                    isPersonalCameraPhoto = cached.isPersonalCamera,
                    isLikelyForwarded = item.isLikelyForwarded && !cached.isPersonalCamera
                )
            } else if (item.isVideo) {
                val sha = if (item.id in collisionIds) cleanup.sha256(item.uri) else null
                val res = item.copy(
                    sha256 = sha,
                    qualityScore = 70,
                    qualityReason = "Video (${item.durationFormatted})"
                )
                newlyAnalyzed += res
                res
            } else {
                // If received via WhatsApp, inspect camera EXIF so real photos are not flagged as forwards
                var isPersonal = item.isPersonalCameraPhoto
                var forwardConfidence = item.whatsAppForwardConfidence
                if (item.isWhatsAppReceived && !isPersonal) {
                    val (hasExifCamera, camModel) = repo.inspectCameraExif(item.uri)
                    if (hasExifCamera) {
                        isPersonal = true
                        forwardConfidence = 0
                    }
                }

                val visual = cleanup.analyzeVisuals(item.uri, item.width, item.height, item.size)
                val sha = if (item.id in collisionIds) cleanup.sha256(item.uri) else null
                val res = item.copy(
                    sha256 = sha,
                    dHash = visual.dHash,
                    qualityScore = visual.quality.score,
                    isBlurry = visual.quality.isBlurry,
                    isBadExposure = visual.quality.isBadExposure,
                    isHeavilyCompressed = visual.quality.isHeavilyCompressed,
                    isLowResolution = visual.quality.isLowResolution,
                    qualityReason = visual.quality.reason,
                    isPersonalCameraPhoto = isPersonal,
                    isLikelyForwarded = if (isPersonal) false else (forwardConfidence >= 70),
                    whatsAppForwardConfidence = if (isPersonal) 0 else forwardConfidence
                )
                newlyAnalyzed += res
                res
            }

            if (indexIdx % 8 == 0 || indexIdx == total - 1) {
                onProgress?.invoke(indexIdx + 1, total, result)
            }
            result
        }

        // Persist any newly computed analyses to SQLite so future opens are instantaneous
        if (newlyAnalyzed.isNotEmpty()) {
            index.saveCleanupCache(newlyAnalyzed)
        }

        // Exact duplicates from colliding sizes
        val exact = enriched.filter { it.sha256 != null }
            .groupBy { it.sha256 }
            .values.filter { it.size > 1 }

        // O(N log N) sorted temporal sliding window for burst sequences
        val sortedByDate = enriched.filter { !it.isVideo && it.dHash != null }.sortedBy { it.dateAdded }
        val burstGroups = mutableListOf<List<MediaItem>>()
        val burstUsed = mutableSetOf<Long>()

        for (i in sortedByDate.indices) {
            val a = sortedByDate[i]
            if (a.id in burstUsed) continue
            val bGroup = mutableListOf(a)
            for (j in i + 1 until minOf(sortedByDate.size, i + 30)) {
                val b = sortedByDate[j]
                if (b.id in burstUsed) continue
                if (kotlin.math.abs(a.dateAdded - b.dateAdded) > 15) break
                if (cleanup.hamming(a.dHash!!, b.dHash!!) <= 6) {
                    bGroup += b
                }
            }
            if (bGroup.size > 1) {
                bGroup.forEach { burstUsed += it.id }
                burstGroups += bGroup
            }
        }

        // Visual groups: check similar shots in temporal neighborhoods
        val visual = mutableListOf<List<MediaItem>>()
        val visualUsed = mutableSetOf<Long>()
        for (i in sortedByDate.indices) {
            val a = sortedByDate[i]
            if (a.id in visualUsed) continue
            val group = mutableListOf(a)
            for (j in i + 1 until minOf(sortedByDate.size, i + 50)) {
                val b = sortedByDate[j]
                if (b.id in visualUsed) continue
                if (kotlin.math.abs(a.dateAdded - b.dateAdded) > 120) break
                if (cleanup.hamming(a.dHash!!, b.dHash!!) <= 8) {
                    group += b
                }
            }
            if (group.size > 1) {
                group.forEach { visualUsed += it.id }
                visual += group
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
        val waLikelyForwarded = waItems.filter { it.isLikelyForwarded && !it.isPersonalCameraPhoto }
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