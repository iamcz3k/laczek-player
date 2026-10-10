# LaCzek Player

An offline-first Android music player. Playback, library management, playlists, ratings, bookmarks, and settings are stored and used locally. No streaming, accounts, cloud services, or online metadata/lyrics APIs are implemented.

## Features in the current test build
- Scan local audio; browse songs, albums, artists, folders, recent tracks, most-played tracks, and duplicate title/artist groups
- Search local title, artist, album, composer, year, folder, and file-format metadata
- Embedded album art and optional custom artwork chosen from local files
- Local playback, play/pause, next/previous, seeking, shuffle, repeat, playback speed, queue inspection, and queue save/restore
- Background playback through Android Media3 MediaSessionService and Android system media controls
- Resizable home-screen playback widget with play/pause, next, title, and artist
- Local favourites and named playlists; create, rename, delete, play, and import/export M3U playlists
- App-local title/artist/album overrides, star ratings, and per-track playback bookmarks
- Equalizer presets, custom band controls, bass boost, and virtualizer where supported
- Local LRC/TXT lyrics; timestamped LRC lines follow playback position
- Sleep timer, stop after current track, fade-out timer, and ±15-second seeking
- Share/copy local audio files and request Android consent before deleting supported media files
- File details including composer, year, format, size, and duration when indexed by Android
- Dark/light appearance, customizable accent colour, and folder exclusions
- Local JSON backup/restore for favourites, playlists, theme, sort mode, tags, artwork overrides, ratings, and bookmarks
- No network permission requested by the app

## Download the latest APK
**Direct APK:** https://github.com/iamcz3k/laczek-player/releases/download/test-build/app-debug.apk

If the release asset has not updated yet, open [GitHub Actions](https://github.com/iamcz3k/laczek-player/actions), choose the latest successful **Build LaCzek Player APK** run, and download the **LACZEKPlayer-1.3.0-debug** artifact ZIP.

This is a debug APK for testing, not a production-signed release. Android may ask you to allow installation from the browser or file manager.

## Build
Requires JDK 17, Android SDK platform 35, and Gradle 8.9. Run `gradle assembleDebug`.

- Package: `com.v3x.laczekplayer`
- Version: 1.3.0

## Important limits
This remains an evolving test build, not every item from the original 150+ feature wishlist is implemented. Hardware-dependent effects vary by device. Tag edits are app-local overrides, not embedded-file tag rewrites. M3U imports match local tracks by media ID or title; external playlist paths may not map on a different device.
