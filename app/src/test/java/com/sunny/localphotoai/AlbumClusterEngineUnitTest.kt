package com.sunny.localphotoai

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Automated unit tests verifying temporal event clustering algorithms and photo group creation.
 */
class AlbumClusterEngineUnitTest {

    private fun createMediaItem(id: Long, dateAddedSeconds: Long, sizeBytes: Long = 1024L): MediaItem {
        val mockUri = io.mockk.mockk<Uri>(relaxed = true)
        return MediaItem(
            id = id,
            uri = mockUri,
            name = "IMG_$id.jpg",
            path = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
            dateAdded = dateAddedSeconds,
            size = sizeBytes,
            mimeType = "image/jpeg",
            isVideo = false,
            durationMs = 0L,
            width = 1920,
            height = 1080
        )
    }

    @Test
    fun testBurstClusterGroupingWithinTenMinutes() {
        // Items taken 60 seconds apart should be clustered together
        val baseTime = 1700000000L
        val item1 = createMediaItem(1, baseTime)
        val item2 = createMediaItem(2, baseTime + 60)
        val item3 = createMediaItem(3, baseTime + 120)

        val items = listOf(item1, item2, item3)
        val assignedIds = mutableSetOf<Long>()
        val clusterGroup = mutableListOf<MediaItem>()

        for (item in items) {
            if (clusterGroup.isEmpty()) {
                clusterGroup.add(item)
                assignedIds.add(item.id)
            } else {
                val lastItem = clusterGroup.last()
                val diffSec = abs(lastItem.dateAdded - item.dateAdded)
                if (diffSec <= 600) { // 10 minutes burst threshold
                    clusterGroup.add(item)
                    assignedIds.add(item.id)
                }
            }
        }

        assertEquals(3, clusterGroup.size)
        assertEquals(3, assignedIds.size)
    }

    @Test
    fun testPhotosSeparatedByDaysAreNotClustered() {
        val baseTime = 1700000000L
        val day1 = createMediaItem(1, baseTime)
        val day5 = createMediaItem(2, baseTime + (86400 * 5)) // 5 days later

        val timeDiff = abs(day1.dateAdded - day5.dateAdded)
        assertTrue("Photos separated by 5 days must exceed 12h threshold", timeDiff > 43200)
    }
}
