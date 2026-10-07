# Local Photo AI v0.3

An Android local-first AI photo search and cleanup application built around Google AI Edge's Universal Embedder + Semantic Retriever and EmbeddingGemma 2 Text+Vision.

## v0.3 features

- Natural-language semantic image search.
- Local EmbeddingGemma 2 indexing.
- Select-all / multi-select.
- Android system-confirmed bulk deletion.
- Exact duplicate detection using SHA-256.
- Similar-photo candidates using local average-hash / Hamming distance.
- Blurry-photo candidates using local Laplacian variance.
- Large-file cleanup candidates (>10 MB).
- Screenshot candidates based on MediaStore display names.
- Smart cleanup groups visually similar photos.
- Quality-based keeper selection using dimensions, size and recency.
- Reclaimable-space estimate before deletion.
- Review-only recommendations; no automatic deletion.
- Cleanup tab with one-tap analysis buttons.
- No cloud inference.

## Architecture

MediaStore -> local analyzer / EmbeddingGemma 2 -> SemanticRetriever + SQLite -> search/cleanup -> review -> MediaStore delete request.

## Build

Open the `LocalPhotoAI` folder in Android Studio. Use a current Android SDK and let Gradle resolve `com.google.mediapipe:tasks-retrieval`.

The model is downloaded on demand into the app's private files directory. Internet is only required for the model download; search and cleanup processing are local afterwards.

## Important behavior

- Exact duplicate candidates are generated with SHA-256, so identical bytes are grouped safely.
- Similar candidates use an average hash and are intentionally conservative. Review before deletion.
- Blurry detection is a heuristic, not a guarantee.
- The app uses Android's `MediaStore.createDeleteRequest`, so the OS/user confirmation remains in control of destructive deletion.


## V4 additions

- WhatsApp-origin image detection using MediaStore `RELATIVE_PATH` and filenames.
- Separate **WhatsApp forwards** heuristic and **All WhatsApp** view.
- Sent WhatsApp media is excluded from the likely-forward list.
- Heuristics consider WhatsApp folder, filename, compression/size, and poster/status-like aspect ratio.
- The UI clearly labels forwarding as heuristic: Android gallery APIs do not expose WhatsApp's internal forwarded-message flag.
- Review and bulk-delete remain explicit and protected by Android's system delete confirmation.

### WhatsApp limitation
A received WhatsApp image can be a personal photo, a forwarded image, a screenshot, a meme, or a document. The MediaStore API cannot reliably tell which one it was. V4 therefore produces **likely-forward candidates**, not a factual forwarded/unforwarded classification.
