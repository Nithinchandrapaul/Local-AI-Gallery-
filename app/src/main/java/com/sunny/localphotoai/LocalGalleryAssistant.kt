package com.sunny.localphotoai

data class AssistantResult(val message: String, val action: String? = null)
data class AssistantOutcome(val result: AssistantResult, val items: List<MediaItem> = emptyList(), val report: CleanupReport? = null)

object LocalGalleryAssistant {
    suspend fun execute(
        command: String,
        items: List<MediaItem>,
        existingReport: CleanupReport?,
        analyzer: PhotoAnalyzer,
        embedder: EmbeddingEngine,
        index: SemanticMediaIndex
    ): AssistantOutcome {
        val q = command.trim()
        if (q.isBlank()) return AssistantOutcome(AssistantResult("Tell me what you want to find or clean."))
        val lower = q.lowercase()
        return when {
            lower.contains("storage") || lower.contains("recover") || lower.contains("space") -> {
                val report = existingReport ?: analyzer.analyze(items)
                val duplicateMb = report.totalRecoverableBytes / 1024.0 / 1024.0
                val waMb = report.whatsAppReport.recoverableBytes / 1024.0 / 1024.0
                AssistantOutcome(AssistantResult(
                    "I found about %.1f MB of recoverable exact-duplicate space. WhatsApp duplicate candidates account for about %.1f MB. Review the Cleanup tab before deleting anything.".format(duplicateMb, waMb),
                    "Explain storage"
                ), report = report)
            }
            lower.contains("clean") || lower.contains("cleanup") -> {
                val report = existingReport ?: analyzer.analyze(items)
                val candidates = report.recommendedDeleteIds.mapNotNull { id -> items.firstOrNull { item -> item.id == id } } +
                    report.whatsAppReport.likelyForwarded + report.qualityReport.lowQualityPhotos
                AssistantOutcome(
                    AssistantResult("I prepared \$candidates.distinctBy { it.id }.size review candidates from duplicates, WhatsApp clutter, and low-quality photos. Nothing was deleted.", "Prepare cleanup review"),
                    candidates.distinctBy { it.id }, report
                )
            }
            lower.contains("duplicate") -> {
                val report = existingReport ?: analyzer.analyze(items)
                val candidates = report.recommendedDeleteIds.mapNotNull { id -> items.firstOrNull { item -> item.id == id } }
                AssistantOutcome(
                    AssistantResult("I found \$report.exactDuplicates.size exact-duplicate groups and selected \$candidates.size non-keeper photos for review. Deletion still requires your approval.", "Find duplicates"),
                    candidates, report
                )
            }
            lower.contains("whatsapp") || lower.contains("whats app") -> {
                val report = existingReport ?: analyzer.analyze(items)
                val wa = report.whatsAppReport
                AssistantOutcome(
                    AssistantResult("WhatsApp intelligence found \$wa.totalCount images, including \$wa.receivedCount received and \$wa.sentCount sent. \$wa.likelyForwarded.size are likely-forwarded by heuristic.", "Inspect WhatsApp media"),
                    wa.likelyForwarded, report
                )
            }
            lower.contains("blurry") || lower.contains("blur") || lower.contains("low quality") -> {
                val report = existingReport ?: analyzer.analyze(items)
                AssistantOutcome(
                    AssistantResult("I found \$report.qualityReport.lowQualityPhotos.size low-quality candidates, including \$report.qualityReport.blurryCount blurry photos. Review them before deletion.", "Inspect quality"),
                    report.qualityReport.lowQualityPhotos, report
                )
            }
            else -> {
                if (!embedder.initialize()) {
                    AssistantOutcome(AssistantResult("The on-device AI model could not be loaded: \$embedder.lastError ?: "model unavailable""))
                } else {
                    val vector = embedder.embedText(q)
                    if (vector == null || vector.isEmpty()) {
                        AssistantOutcome(AssistantResult("I could not create a local search embedding for that request."))
                    } else {
                        val matches = index.search(vector, items).map { it.item }
                        AssistantOutcome(
                            AssistantResult("I searched your indexed gallery locally and found \$matches.size semantic matches for “\$q”.", "Semantic photo search"),
                            matches
                        )
                    }
                }
            }
        }
    }
}