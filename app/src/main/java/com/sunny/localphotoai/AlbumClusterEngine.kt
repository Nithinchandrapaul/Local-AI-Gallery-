package com.sunny.localphotoai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class MediaCluster(
    val id: String,
    val title: String,
    val category: String,
    val items: List<MediaItem>,
    val coverItem: MediaItem,
    val dateRangeFormatted: String,
    val totalSizeMb: Double
)

class AlbumClusterEngine(
    private val index: SemanticMediaIndex
) {
    suspend fun clusterMedia(items: List<MediaItem>): List<MediaCluster> = withContext(Dispatchers.Default) {
        if (items.isEmpty()) return@withContext emptyList()

        val embeddings = index.getAllEmbeddings()
        val clusters = mutableListOf<MediaCluster>()
        val assignedIds = mutableSetOf<Long>()

        val sortedByDate = items.sortedByDescending { it.dateAdded }

        // Event clustering: sliding window of temporal proximity (<= 12 hours) + semantic coherence
        var i = 0
        while (i < sortedByDate.size) {
            val anchor = sortedByDate[i]
            if (anchor.id in assignedIds) {
                i++
                continue
            }

            val anchorEmb = embeddings[anchor.id]
            val eventGroup = mutableListOf(anchor)
            assignedIds += anchor.id

            for (j in i + 1 until sortedByDate.size) {
                val candidate = sortedByDate[j]
                if (candidate.id in assignedIds) continue

                // Check time distance in seconds (12 hours = 43200 seconds)
                val timeDiffSec = abs(anchor.dateAdded - candidate.dateAdded)
                if (timeDiffSec <= 43200) {
                    val candEmb = embeddings[candidate.id]
                    val similarity = if (anchorEmb != null && candEmb != null) {
                        index.cosine(anchorEmb, candEmb)
                    } else 0.5

                    // High semantic affinity or close burst
                    if (similarity >= 0.65 || timeDiffSec <= 600) {
                        eventGroup += candidate
                        assignedIds += candidate.id
                    }
                }
            }

            if (eventGroup.size >= 3) {
                val cover = eventGroup.maxByOrNull { it.qualityScore } ?: eventGroup.first()
                val (title, category) = generateClusterTitle(eventGroup)
                val dateFmt = formatDateRange(eventGroup)
                val totalMb = eventGroup.sumOf { it.size } / 1024.0 / 1024.0
                clusters += MediaCluster(
                    id = "cluster_${anchor.id}",
                    title = title,
                    category = category,
                    items = eventGroup,
                    coverItem = cover,
                    dateRangeFormatted = dateFmt,
                    totalSizeMb = totalMb
                )
            }
            i++
        }

        // Dedicated System Collections:
        // Screenshots Collection
        val unassignedScreenshots = items.filter { it.isScreenshot && it.id !in assignedIds }
        if (unassignedScreenshots.size >= 2) {
            val cover = unassignedScreenshots.first()
            clusters += MediaCluster(
                id = "cluster_screenshots",
                title = "Screenshots",
                category = "Digital Captures",
                items = unassignedScreenshots,
                coverItem = cover,
                dateRangeFormatted = formatDateRange(unassignedScreenshots),
                totalSizeMb = unassignedScreenshots.sumOf { it.size } / 1024.0 / 1024.0
            )
            unassignedScreenshots.forEach { assignedIds += it.id }
        }

        // WhatsApp Media Collection
        val unassignedWa = items.filter { it.isWhatsApp && it.id !in assignedIds }
        if (unassignedWa.size >= 3) {
            val cover = unassignedWa.first()
            clusters += MediaCluster(
                id = "cluster_whatsapp",
                title = "WhatsApp Media",
                category = "Social Media",
                items = unassignedWa,
                coverItem = cover,
                dateRangeFormatted = formatDateRange(unassignedWa),
                totalSizeMb = unassignedWa.sumOf { it.size } / 1024.0 / 1024.0
            )
            unassignedWa.forEach { assignedIds += it.id }
        }

        // Video Highlights Collection
        val unassignedVideos = items.filter { it.isVideo && it.id !in assignedIds }
        if (unassignedVideos.isNotEmpty()) {
            val cover = unassignedVideos.first()
            clusters += MediaCluster(
                id = "cluster_videos",
                title = "Video Highlights",
                category = "Motion & Videos",
                items = unassignedVideos,
                coverItem = cover,
                dateRangeFormatted = formatDateRange(unassignedVideos),
                totalSizeMb = unassignedVideos.sumOf { it.size } / 1024.0 / 1024.0
            )
            unassignedVideos.forEach { assignedIds += it.id }
        }

        // Recent Moments
        val remaining = items.filter { it.id !in assignedIds }
        if (remaining.isNotEmpty()) {
            val cover = remaining.first()
            clusters += MediaCluster(
                id = "cluster_moments",
                title = "Gallery Highlights",
                category = "Moments",
                items = remaining,
                coverItem = cover,
                dateRangeFormatted = formatDateRange(remaining),
                totalSizeMb = remaining.sumOf { it.size } / 1024.0 / 1024.0
            )
        }

        clusters.sortedByDescending { it.items.size }
    }

    private fun generateClusterTitle(group: List<MediaItem>): Pair<String, String> {
        val count = group.size
        val hasScreenshots = group.count { it.isScreenshot } > count / 2
        val hasWhatsApp = group.count { it.isWhatsApp } > count / 2
        val hasVideos = group.any { it.isVideo }
        val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
        val dateStr = if (group.isNotEmpty()) dateFmt.format(Date(group.first().dateAdded * 1000L)) else "Recent"

        return when {
            hasScreenshots -> "Screenshots & Captures ($dateStr)" to "Documents"
            hasWhatsApp -> "WhatsApp Memories ($dateStr)" to "Chat Media"
            hasVideos -> "Event & Clips ($dateStr)" to "Mixed Media"
            count >= 10 -> "Big Outing ($dateStr)" to "Events"
            else -> "Event Memories ($dateStr)" to "Moments"
        }
    }

    private fun formatDateRange(group: List<MediaItem>): String {
        if (group.isEmpty()) return ""
        val sorted = group.sortedBy { it.dateAdded }
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val start = fmt.format(Date(sorted.first().dateAdded * 1000L))
        val end = fmt.format(Date(sorted.last().dateAdded * 1000L))
        return if (start == end) start else "$start - $end"
    }
}
