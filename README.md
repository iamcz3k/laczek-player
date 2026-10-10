# LaCzek Player

Offline-first Android music player. This initial test build scans music already stored on your phone and plays local audio. It has no streaming, accounts, cloud services, online lyrics, or online APIs.

## Included in the initial test build
- Scan local audio using Android MediaStore
- Song list with title, artist, and duration
- Local playback, play/pause, next/previous, and seek bar
- Dark interface with LaCzek orange accents
- Android audio permission handling

## Download the APK
1. Open the repository's Actions tab.
2. Open the latest successful "Build LaCzek Player APK" run.
3. Download the LACZEKPlayer-1.0.0-debug artifact ZIP.
4. Extract it and install app-debug.apk on your Android phone.

The artifact appears only after GitHub Actions finishes a successful build. This is a debug APK for testing, not a production-signed release.

## Build
Requires JDK 17, Android SDK platform 35, and Gradle 8.9. Run: gradle assembleDebug

Package: com.v3x.laczekplayer
Version: 1.0.0 (initial test build)
