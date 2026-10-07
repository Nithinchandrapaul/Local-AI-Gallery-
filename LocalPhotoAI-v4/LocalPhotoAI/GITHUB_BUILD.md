# Build the APK from your phone

1. Create a GitHub repository named `LocalPhotoAI`.
2. Upload the contents of this folder to the repository root.
3. Open **Actions** → **Build Local Photo AI APK** → **Run workflow**.
4. Wait for the job to finish.
5. Open the completed workflow run.
6. Under **Artifacts**, download `LocalPhotoAI-debug-apk`.
7. Extract it and install the APK on Android.

The workflow installs JDK 21, Android SDK 36, Gradle 8.13, builds the debug APK, and publishes it as a GitHub Actions artifact.
