# LaCzek Player

An offline-first Android music player. Playback, library management, playlists, ratings, bookmarks, and settings are stored and used locally. No streaming, accounts, cloud services, or online metadata/lyrics APIs are implemented.

## Features in the current test build
- Scan local audio and browse songs, albums, artists, and folders
- Search local title, artist, album, composer, year, folder, and format metadata
- Album artwork in the player when embedded/local artwork is available
- Local play/pause, next/previous, seeking, shuffle, repeat, queue inspection, and playback speed
- Background playback through Android Media3 MediaSessionService and Android system media controls
- Local favourites, recent/most-played views, duplicate title/artist detection, and named playlists
- Create, rename, delete, and play playlists; import/export M3U playlists with local-track matching
- Edit app-local title/artist/album overrides (does not rewrite the audio file's embedded tags)
- Local 0–5 star ratings and per-track playback bookmarks
- Built-in Android equalizer where supported
- Load local LRC/TXT lyrics; timestamped LRC lines follow playback position
- Sleep timer, stop after current track, fade-out timer, and ±15-second seeking
- Share/copy local audio files and request Android consent before deleting supported media files
- File details including composer/year/format/size when supplied by Android's media index
- Dark/light appearance with LaCzek orange accents
- Local JSON backup/restore for favourites, playlists, theme, sort mode, tags, ratings, and bookmarks

## Download the latest APK
**Direct APK:** https://github.com/iamcz3k/laczek-player/releases/download/test-build/app-debug.apk

If the release asset has not updated yet, open [GitHub Actions](https://github.com/iamcz3k/laczek-player/actions), choose the latest successful **Build LaCzek Player APK** run, and download the **LACZEKPlayer-1.2.0-debug** artifact ZIP.

This is a debug APK for testing, not a production-signed release. Android may ask you to allow installation from the browser or file manager.

## Build
Requires JDK 17, Android SDK platform 35, and Gradle 8.9. Run `gradle assembleDebug`.

- Package: `com.v3x.laczekplayer`
- Version: 1.2.0

## Important limits
This is still an evolving test build, not every item from the original 150+ feature wishlist is implemented. Hardware-dependent audio effects vary by device. Custom tag edits are app-local overrides, not embedded-file tag rewrites. No network permission is requested by the app.
