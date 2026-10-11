# LaCzek Player

An offline-first Android music player. Playback, library management, playlists, ratings, bookmarks, lyrics, artwork, and settings are stored and used locally. No streaming, accounts, cloud services, or online metadata/lyrics APIs are implemented.

## Features

- Scan local audio; browse songs, albums, artists, featured artists, genres, composers, release years, formats, folders, recent tracks, most-played tracks, and duplicate groups.
- Search track metadata and saved local LRC/TXT lyrics.
- Minimum file-size and minimum-duration filters, saved locally and applied to library views.
- Advanced offline sorting by title, artist, album, duration, year, file size, rating, play count, format, and recently added.
- Embedded album art, custom local artwork, folder cover images, and folder information files after the user grants folder access.
- Local playback, seeking, shuffle, repeat, speed control, queue inspection, queue save/restore, and Android system media controls.
- Background playback through Android Media3 MediaSessionService and a resizable home-screen playback widget.
- Local favourites, named playlists, manual playlist ordering, playlist artwork, and M3U import/export.
- Multi-selection across library sections, including selecting groups.
- Skip-silence playback, smooth fade effects around play/pause/skip, and optional mini-player party animation.
- App-local title/artist/album/genre/year overrides, star ratings, and per-track playback bookmarks.
- Equalizer presets, custom bands, bass boost, and virtualizer where supported.
- A-B repeat, sleep timer, stop after current track, fade-out timer, and ±15-second seeking.
- Rename, share, copy, and delete supported local audio files through Android's permission/consent flows.
- Offline listening statistics, play counts, recent history, and library CSV export.
- Dark/light appearance, customizable accent colour, folder exclusions, and local JSON backup/restore.
- No Android INTERNET permission; the app is designed for offline use.

## Download APK

**Direct APK:** https://github.com/iamcz3k/laczek-player/releases/latest/download/LACZEKPlayer-2.4.0.apk

Versioned release: https://github.com/iamcz3k/laczek-player/releases/tag/v2.4.0

The APK is a debug build for device testing, not a production-signed release. Android may ask you to allow installation from your browser or file manager. If the release asset is not available yet, open GitHub Actions and download the `LACZEKPlayer-2.4.0-debug` artifact ZIP from the latest successful build: https://github.com/iamcz3k/laczek-player/actions

## Build

Requires JDK 17, Android SDK platform 35, and Gradle 8.9. Run `gradle assembleDebug`.

- Package: `com.v3x.laczekplayer`
- Version: `2.4.0`

## Important limits

This is an evolving test build. Hardware-dependent audio effects vary by device. Tag edits are app-local overrides, not embedded-file tag rewrites. Folder artwork and information files require the user to grant access to the relevant local folder. Playlist imports match local tracks by media ID or title; external paths may not map on a different device. Keep a separate backup of important playlists and local settings.
