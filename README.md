# LaCzek Player

An offline-first Android music player. Playback, library management, playlists, ratings, bookmarks, and settings are stored and used locally. No streaming, accounts, cloud services, or online metadata/lyrics APIs are implemented.

## Features in the current test build
- Scan local audio; browse songs, albums, artists, genres, composers, release years, file formats, folders, recent tracks, most-played tracks, and duplicate title/artist groups
- Search local title, artist, album, composer, year, folder, and file-format metadata
- Embedded album art and optional custom artwork chosen from local files
- Local playback, play/pause, next/previous, seeking, shuffle, repeat, playback speed, queue inspection, and queue save/restore
- Background playback through Android Media3 MediaSessionService and Android system media controls
- Resizable home-screen playback widget with play/pause, next, title, and artist
- Local favourites and named playlists; create, rename, delete, play, and import/export M3U playlists
- App-local title/artist/album overrides, star ratings, and per-track playback bookmarks
- Equalizer presets, custom band controls, bass boost, and virtualizer where supported
- A-B repeat for looping a section of a track; rename files through Android's media permission flow
- Offline listening-time and library statistics; recent history and play counts update as tracks actually advance
- Local LRC/TXT lyrics; timestamped LRC lines follow playback position
- Sleep timer, stop after current track, fade-out timer, and ±15-second seeking
- Share/copy local audio files and request Android consent before deleting supported media files
- File details including composer, year, format, size, and duration when indexed by Android
- Dark/light appearance, customizable accent colour, and folder exclusions
- Local JSON backup/restore for favourites, playlists, theme, sort mode, tags, artwork overrides, ratings, and bookmarks
- Branded LaCzek Player launcher icon with an adaptive icon on Android 8.0+;
- Playback queue item controls: play now, move up/down, or remove without clearing the whole queue;
- Export the currently filtered library to CSV for offline spreadsheets; resume the last played local track from its last saved position; clear listening history and counters with confirmation
- No network permission requested by the app

## Download the latest APK
**Direct APK:** https://github.com/iamcz3k/laczek-player/releases/latest/download/LACZEKPlayer-1.9.0.apk

The APK is attached to the versioned [v1.9.0 release](https://github.com/iamcz3k/laczek-player/releases/tag/v1.9.0). If GitHub has not finished publishing it, open [GitHub Actions](https://github.com/iamcz3k/laczek-player/actions), choose the latest successful **Build LaCzek Player APK** run, and download the **LACZEKPlayer-1.9.0-debug** artifact ZIP.

This is a debug APK for testing, not a production-signed release. Android may ask you to allow installation from the browser or file manager.

## Build
Requires JDK 17, Android SDK platform 35, and Gradle 8.9. Run `gradle assembleDebug`.

- Package: `com.v3x.laczekplayer`
- Version: 1.9.0

## Important limits
This remains an evolving test build, not every item from the original feature wishlist is implemented. Version 2.4.0 adds the ten selected Namida-inspired offline features listed above. CI now checks APK archive integrity, debug signing, package ID, and absence of the Android INTERNET permission before publishing. Hardware-dependent effects vary by device. Tag edits are app-local overrides, not embedded-file tag rewrites. M3U imports match local tracks by media ID or title; external playlist paths may not map on a different device. JSON backup version 2 also preserves accent colour, hidden folders, recent order, play counts, listening time, saved queue state, and the last track position.
