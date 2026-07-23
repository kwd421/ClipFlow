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

1. Analyze one or more URLs (newline-separated) with the bundled yt-dlp Android runtime.
2. Playlist URLs expand into a parent group plus child items with batch download.
3. Site routes: Chzzk native API when possible; SOOP/CIME via yt-dlp; generic pages fall back to WebView media capture.
4. Select candidates, download in a foreground WorkManager task, optional segment/audio extract from the source URL.
5. Save to `Downloads/ClipFlow` through MediaStore or to a user-selected document tree.
6. Session list/tasks restore after process death. Dark theme, sort, and cookies.txt import are supported.

## Release signing

Optional release keystore via environment or `gradle.properties`:

```
CLIPFLOW_STORE_FILE=/path/to/keystore.jks
CLIPFLOW_STORE_PASSWORD=...
CLIPFLOW_KEY_ALIAS=...
CLIPFLOW_KEY_PASSWORD=...
```

Update feed: `docs/appcast-android.json` (versionCode/apkUrl). Empty `apkUrl` disables the in-app update prompt.

DRM, CAPTCHA, and access-control bypass are intentionally unsupported.
