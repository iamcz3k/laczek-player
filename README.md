# LaCzek Player

An offline-first Android music player. Playback and library management operate on local files. The app does not implement streaming, accounts, cloud services, online lyrics, or online metadata APIs.

## Current test build features
- Scan the device's local audio library
- Browse songs, albums, artists, and folders
- Search local songs and metadata
- Local playback with play/pause, next/previous, seeking, shuffle, repeat, and a playback queue
- Background playback through Android Media3 MediaSessionService, including system media controls
- Local favourites and named playlists
- Track details and sharing of local audio files
- Built-in Android equalizer where supported
- Load local LRC or text lyric files
- Sleep timer, playback speed control, and seek by 15 seconds
- Dark interface with LaCzek orange accents
- Local JSON settings backup and restore
- Local preferences for favourites, playlists, theme, and sort mode

## Download the latest APK
**Direct APK:** https://github.com/iamcz3k/laczek-player/releases/download/test-build/app-debug.apk

If a release asset is not available yet, open [GitHub Actions](https://github.com/iamcz3k/laczek-player/actions), select the latest successful **Build LaCzek Player APK** run, and download the **LACZEKPlayer-1.1.0-debug** artifact ZIP.

This is a debug build intended for testing, not a production-signed release. Android may require you to allow installation from the browser or file manager.

## Build
Requires JDK 17, Android SDK platform 35, and Gradle 8.9. Run `gradle assembleDebug`.

- Package: `com.v3x.laczekplayer`
- Version: 1.1.0

## Offline and capability notes
The app does not need an internet connection for local playback. Equalizer support varies by device. Lyrics are read from files selected on the device; synchronized display requires timed lyric data and is still being developed. Some features from the long-term feature wishlist are not implemented yet.
