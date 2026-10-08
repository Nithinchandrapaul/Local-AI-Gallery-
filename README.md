# Leo Ai local gallery (V3.3.0)

A 100% on-device, privacy-first Android photo and video gallery featuring GPU-accelerated semantic search, intelligent album clustering, biometric private vault, date timeline headers, side-by-side duplicate comparison, and comprehensive storage cleanup.

> **Your photos stay on your device. AI understands and cleans your gallery locally.**

## What's New in V3.3.0

- **Accurate WhatsApp Forward vs Personal Camera Discrimination**:
  - Distinguishes genuine personal camera photos sent over WhatsApp from forwarded memes/flyers using `ExifInterface` camera metadata inspection (`TAG_MAKE`, `TAG_MODEL`, `TAG_DATETIME_ORIGINAL`, exposure, and document directories).
  - Personal camera photos are tagged `📷 Camera` and never falsely flagged as 100% forward.
- **Persistent SQLite Analysis Cache (0ms Instant Reopen)**:
  - Analysis results and visual quality scores are automatically saved to `local_photo_ai.db` (`cleanup_cache` table).
  - Reopening the app or returning to the Cleanup tab restores analyzed clutter instantly without re-scanning or waiting.
- **Collapsible Full-Screen Headers on Scroll**:
  - In Search, Cleanup, and Albums tabs, keywords, suggestion chips, and filter bars collapse smoothly with `AnimatedVisibility` when you scroll into the photos, giving 100% screen real estate to photo browsing.
- **4x–8x AI Indexing Speedup & Background Foreground Service**:
  - MediaPipe UniversalEmbedder configured with `BaseOptions.Delegate.GPU` (OpenCL/Vulkan hardware acceleration) and graceful CPU fallback.
  - Dedicated Android Foreground Service (`IndexingForegroundService`) with live notification, progress bar, pause/stop controls, and haptic vibration + high-priority notification alert upon completion.
  - Warm, delightful micro-copy ("✨ Weaving visual intelligence...", "✨ Connecting memories...").
- **Crash-Proof Scoped Storage Deletion & Trashing**:
  - Replaced deprecated `startIntentSenderForResult` calls with Jetpack Compose-native `rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult())`.
  - Deletion or trashing never closes, restarts, or crashes the application.
- **Recycle Bin (Trash) in Cleanup Tab**:
  - Directly accessible from the Cleanup tab category reel.
  - Supports 30, 60, and 90 days retention badges with days-remaining countdowns.
  - Single-tap "♻️ Restore Selected" (`createTrashRequest(..., false)`) and "Empty Permanently" (`createDeleteRequest(...)`).
- **Biometric Private Vault**:
  - Fingerprint, Face Unlock, and device PIN authentication via Android `BiometricPrompt`.
  - Securely hides private photos from public gallery grids, search, cleanup, and smart albums until authenticated.
- **Date & Event Timeline Headers**:
  - Gallery view is chronologically organized into stylish headers ("Today", "Yesterday", "October 2026", "September 2026") showing date and photo count per event.
- **Side-by-Side Duplicate Comparison**:
  - Split inspection dialog comparing original and duplicate photos with resolution, file size, and sharpness scores highlighted in emerald green. Single-tap "Keep This" or "Keep Both".
- **Full Landscape Mode Support**:
  - Smooth orientation transitions without activity recreation (`android:configChanges`).
  - Dynamic adaptive grid switching (3 columns in Portrait, 6 columns in Landscape).

---

## Core Architecture & Features

- **Semantic Natural Language Search**: Search photos locally using Google EmbeddingGemma 2 (Text + Vision 440M LiteRT-LM) and MediaPipe Universal Embedder.
- **Smart Event & Album Clustering Engine**: Organizes photos into event albums using temporal proximity sliding windows and 256d compact vector cosine similarity without any cloud processing.
- **Video Intelligence & Multi-Format Timeline**: MediaStore video scanning, video playback indicators, duration badges, and large video (>50MB) triage.
- **Zero Cloud AI Dependency**: The 440M model is bundled directly inside the APK assets. Zero network downloads required.

## Target Platform & Requirements
- **JDK**: 21
- **Android SDK**: Compile SDK 36, Min SDK 31 (Android 12+)
- **ABIs**: 64-bit `arm64-v8a` and `x86_64`
