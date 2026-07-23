# ClipFlow Android

Native Android client for ClipFlow. The desktop Python application remains independent.

## Requirements

- JDK 17
- Android SDK 36
- Android build tools 35.0.0 or newer

## Build

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Current flow

1. Analyze a URL with the bundled yt-dlp Android runtime.
2. Select a visible quality candidate.
3. Download in a foreground WorkManager task.
4. Merge or transcode the result to one MP4 with the bundled FFmpeg runtime.
5. Save to `Downloads/ClipFlow` through MediaStore or to a user-selected document tree.

DRM, CAPTCHA, and access-control bypass are intentionally unsupported.
