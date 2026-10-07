package com.sunny.localphotoai

/**
 * Detects WhatsApp-origin images and ranks images that are likely forwarded/received
 * media. Android MediaStore does not expose WhatsApp's internal "forwarded" flag,
 * so this is deliberately a heuristic and never claims certainty.
 */
object WhatsAppCleaner {
    data class Candidate(
        val item: MediaItem,
        val score: Int,
        val reason: String,
        val category: Category
    )

    enum class Category { LIKELY_FORWARD, RECEIVED_MEDIA, SENT_MEDIA, UNKNOWN_WHATSAPP }

    fun isWhatsApp(item: MediaItem): Boolean {
        val p = item.relativePath.lowercase()
        val n = item.displayName.lowercase()
        return p.contains("whatsapp") || n.contains("whatsapp")
    }

    fun classify(item: MediaItem): Candidate? {
        if (!isWhatsApp(item)) return null
        val p = item.relativePath.lowercase()
        val n = item.displayName.lowercase()
        val sent = p.contains("whatsapp images/sent") || p.contains("whatsapp/video/sent")
        if (sent) return Candidate(item, 0, "WhatsApp Sent", Category.SENT_MEDIA)

        var score = 0
        val reasons = mutableListOf<String>()

        // Strong signals for received/forward-style media.
        if (p.contains("whatsapp images") || p.contains("whatsapp image")) {
            score += 2; reasons += "WhatsApp Images"
        }
        if (n.startsWith("img-") || n.startsWith("wa")) {
            score += 1; reasons += "WhatsApp filename"
        }
        if (item.width > 0 && item.height > 0) {
            val mp = item.width.toLong() * item.height / 1_000_000.0
            if (mp <= 3.0) { score += 1; reasons += "compressed/standard resolution" }
        }
        if (item.sizeBytes in 40_000L..3_000_000L) {
            score += 1; reasons += "small received-media size"
        }
        // A WhatsApp image with a very wide/tall aspect ratio is often a status,
        // poster, meme, screenshot or quote image rather than a camera photo.
        if (item.width > 0 && item.height > 0) {
            val ratio = maxOf(item.width, item.height).toDouble() / minOf(item.width, item.height).coerceAtLeast(1)
            if (ratio >= 1.65) { score += 2; reasons += "poster/status-like aspect ratio" }
        }

        val category = if (score >= 4) Category.LIKELY_FORWARD else Category.RECEIVED_MEDIA
        return Candidate(item, score, reasons.joinToString(" • ").ifBlank { "WhatsApp received media" }, category)
    }
}
