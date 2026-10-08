# Leo Ai local gallery (V3.0.1)

A 100% on-device, privacy-first Android photo and video search, intelligent album clustering, and cleanup gallery.

> **Your photos stay on your device. AI understands and cleans your gallery locally.**

## Features

- **Icon & Identity (V3.0.1)**: Brand logo and application icon updated to the official golden lion neural aperture emblem. App size optimized to sub-350MB packaging.
- **Semantic Natural Language Search**: Search photos and media locally using Google EmbeddingGemma 2 (Text + Vision 440M LiteRT-LM) and MediaPipe Universal Embedder.
- **Smart Event & Album Clustering Engine (V3.0)**: Automatically organizes your photos into event and story albums using temporal proximity sliding windows and 256d compact vector cosine similarity without any cloud processing.
- **Multi-Modal & Multi-Attribute Search (V3.0)**: Filter queries by media types (Photos/Videos), temporal windows (Past 30 days, 1 year, All time), and visual quality thresholds.
- **Video Intelligence & Multi-Format Timeline (V2.5)**: MediaStore video scanning, video playback indicators, duration badges, and large video (>50MB) clutter triage.
- **Local Gallery AI Assistant (V2.0)**: Conversational on-device assistant with natural language queries, storage explanations, and interactive candidate review.
- **Visual Quality Intelligence (V1.5)**: 4-factor quality evaluation (sharpness via Laplacian variance, exposure, compression artifacts, and resolution) with burst sequence grouping and best-shot keeper ranking.
- **WhatsApp Clutter Intelligence (V1.4)**: Sent vs. received classification, heuristic forwarded confidence scoring, and chat duplicate analysis.
- **Exact Duplicates & Storage Recovery**: SHA-256 cryptographic duplicate detection with explainable keeper retention and recoverable storage estimation.
- **Protected MediaStore Deletion**: Strictly uses Android's native `MediaStore.createDeleteRequest()` flow. Never silently deletes user photos.
- **Zero Cloud AI Dependency**: The 440M EmbeddingGemma 2 LiteRT-LM model is bundled directly inside the APK assets. Zero network downloads required.

## Target Platform & Requirements
- **JDK**: 21
- **Android SDK**: Compile SDK 36, Min SDK 31 (Android 12+)
- **ABIs**: 64-bit `arm64-v8a` and `x86_64`

