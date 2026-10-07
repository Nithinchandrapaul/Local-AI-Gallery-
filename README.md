# Local Photo AI

A privacy-first Android photo search and cleanup app.

## What it does

- Semantic natural-language photo search using MediaPipe Universal Embedder + EmbeddingGemma 2.
- Exact duplicate detection using SHA-256.
- Visual similarity detection using dHash.
- Screenshot detection.
- WhatsApp-origin detection.
- Large-file detection.
- Review-before-delete workflow.
- Android protected deletion confirmation through MediaStore.createDeleteRequest().
- AI model is bundled into the APK and photo processing stays on-device. No model download is required after installation.\n\n## V1.2 Smart Cleanup\n- Explainable keeper recommendation for exact duplicate groups.\n- Reviewable recommended deletion set.\n- Incremental AI indexing and compact 256-dimensional stored embeddings.

## Build

This project is intentionally arranged at repository root so you can drag-and-drop all files directly into a GitHub repository.

GitHub Actions:
1. Open Actions.
2. Select `Build Local Photo AI APK`.
3. Run workflow.
4. Download `LocalPhotoAI-debug-apk` from the successful run.

Minimum Android version: Android 12 / API 31.
