# Leo Ai local gallery (V3.2.0)

A 100% on-device, privacy-first Android photo and video search, intelligent album clustering, and cleanup gallery.

> **Your photos stay on your device. AI understands and cleans your gallery locally.**

## Features

- **Full-Screen Photo Review & Freedom in Cleanup (V3.2.0)**:
  - Replaced cramped detail cards with a sleek, 38dp single-row category chip reel, giving 85%+ screen real estate to the photos.
  - **Full-Screen Photo Inspector Dialog**: Tap any candidate photo to inspect it in full high resolution, view why it was flagged (blur score, duplicate match, WhatsApp sent copy), and freely mark or unmark it with "Mark for Deletion" vs "Keep Photo" toggles.
  - Quick previous/next navigation to effortlessly swipe through hundreds of flagged photos one by one.
  - On-demand **"ℹ Stats"** dialog to inspect deep clutter metrics without crowding the photo grid.
- **Instant Hybrid Search (V3.2.0)**:
  - Immediately searches across all 10,000+ photos using smart metadata, path, tag, and date classification, while AI semantic embeddings dynamically elevate top semantic matches.
- **Prioritized & Batched AI Semantic Indexing (V3.2.0)**:
  - Choose between Quick Index (Recent 500 photos in ~1 min), Extended Index (2,000 photos), or Full Library Index.
  - Search immediately with partial indexing or pause/resume anytime without losing progress.
  - Hardware-to-software ARGB_8888 conversion and 224px vision encoder matching prevents JNI stalls.
- **High-Speed Real-Time Live Feed (V3.1.0)**:
  - Real-time visual progress card displaying the currently analyzing photo thumbnail, filename, and smooth progress percentage.
  - Live horizontal strip streaming recently analyzed photos onto the screen in real time.
  - Interactive "Stop" button allowing users to pause/stop indexing at any time while retaining all indexed images.
  - **100x Speedup**: Hardware-cached thumbnail decoding (`ContentResolver.loadThumbnail`), sub-sampled fallback with RGB_565 (eliminating 50MP out-of-memory GC stalls).
  - **Collision-Only File Hashing**: Eliminates 99% of file reads by only running SHA-256 on byte-size collisions.
  - **$O(N)$ Temporal Sliding Window**: Replaces $O(N^2)$ 57-million-iteration loops with a sorted temporal sliding window for instant burst clustering.
  - **SQLite Batch Transactions**: High-throughput batched database insertions (`beginTransaction()`) cutting write latencies from 50ms down to 0.1ms.
- **Icon & Identity (V3.0.1)**: Brand logo and application icon updated to the official golden lion neural aperture emblem with sub-350MB compressed packaging.
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

