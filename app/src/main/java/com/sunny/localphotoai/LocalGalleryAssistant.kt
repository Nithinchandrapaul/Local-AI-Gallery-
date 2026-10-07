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
            lower in listOf("hi", "hello", "hey", "help", "what can you do?", "what can you do") -> {
                AssistantOutcome(AssistantResult(
                    "Hello! I am your 100% on-device AI gallery assistant. You can ask me to:\n" +
                    "• Find specific photos ('receipts', 'family trip', 'car photos')\n" +
                    "• Inspect recoverable storage ('how much space can I recover?')\n" +
                    "• Detect exact duplicates and review deletion candidates\n" +
                    "• Analyze WhatsApp clutter and forwarded images\n" +
                    "• Identify blurry or low-quality photos\n" +
                    "• Show all screenshots or large files (>10MB)",
                    "Capabilities overview"
                ))
            }
            lower.contains("screenshot") -> {
                val shots = items.filter { it.isScreenshot }
                AssistantOutcome(
                    AssistantResult("Found ${shots.size} screenshots across your device. You can review them for cleanup.", "Filter screenshots"),
                    shots
                )
            }
            lower.contains("large") || lower.contains("big file") || lower.contains("10mb") -> {
                val largeFiles = items.filter { it.size > 10 * 1024 * 1024 }
                val totalMb = largeFiles.sumOf { it.size } / 1024.0 / 1024.0
                AssistantOutcome(
                    AssistantResult("Found ${largeFiles.size} large photos occupying %.1f MB total (>10MB each).".format(totalMb), "Inspect large photos"),
                    largeFiles
                )
            }
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
                val distinctCandidates = candidates.distinctBy { it.id }
                AssistantOutcome(
                    AssistantResult("I prepared ${distinctCandidates.size} review candidates from duplicates, WhatsApp clutter, and low-quality photos. Nothing was deleted.", "Prepare cleanup review"),
                    distinctCandidates, report
                )
            }
            lower.contains("duplicate") -> {
                val report = existingReport ?: analyzer.analyze(items)
                val candidates = report.recommendedDeleteIds.mapNotNull { id -> items.firstOrNull { item -> item.id == id } }
                AssistantOutcome(
                    AssistantResult("I found ${report.exactDuplicates.size} exact-duplicate groups and selected ${candidates.size} non-keeper photos for review. Deletion still requires your approval.", "Find duplicates"),
                    candidates, report
                )
            }
            lower.contains("whatsapp") || lower.contains("whats app") -> {
                val report = existingReport ?: analyzer.analyze(items)
                val wa = report.whatsAppReport
                AssistantOutcome(
                    AssistantResult("WhatsApp intelligence found ${wa.totalCount} images, including ${wa.receivedCount} received and ${wa.sentCount} sent. ${wa.likelyForwarded.size} are likely-forwarded by heuristic.", "Inspect WhatsApp media"),
                    wa.likelyForwarded, report
                )
            }
            lower.contains("blurry") || lower.contains("blur") || lower.contains("low quality") -> {
                val report = existingReport ?: analyzer.analyze(items)
                AssistantOutcome(
                    AssistantResult("I found ${report.qualityReport.lowQualityPhotos.size} low-quality candidates, including ${report.qualityReport.blurryCount} blurry photos. Review them before deletion.", "Inspect quality"),
                    report.qualityReport.lowQualityPhotos, report
                )
            }
            else -> {
                if (!embedder.initialize()) {
                    val errorDetail = embedder.lastError ?: "model unavailable"
                    AssistantOutcome(AssistantResult("The on-device AI model could not be loaded: $errorDetail"))
                } else {
                    val vector = embedder.embedText(q)
                    if (vector == null || vector.isEmpty()) {
                        AssistantOutcome(AssistantResult("I could not create a local search embedding for that request."))
                    } else {
                        val matches = index.search(vector, items).map { it.item }
                        AssistantOutcome(
                            AssistantResult("I searched your indexed gallery locally and found ${matches.size} semantic matches for \"$q\".", "Semantic photo search"),
                            matches
                        )
                    }
                }
            }
        }
    }
}