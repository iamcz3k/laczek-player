package com.v3x.laczekplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutionException

private data class Track(
    val id: Long, val title: String, val artist: String, val album: String,
    val path: String, val duration: Long, val uri: Uri,
    val albumId: Long, val composer: String, val year: Int, val sizeBytes: Long, val mimeType: String
)

class MainActivity : Activity() {
    private val tracks = mutableListOf<Track>()
    private val favorites = mutableSetOf<String>()
    private val playlistMap = linkedMapOf<String, MutableSet<String>>()
    private val ratings = mutableMapOf<String, Int>()
    private val bookmarks = mutableMapOf<String, Long>()
    private val customTitles = mutableMapOf<String, String>()
    private val customArtists = mutableMapOf<String, String>()
    private val customAlbums = mutableMapOf<String, String>()
    private var section = "Songs"
    private var query = ""
    private var sortMode = "Title"
    private var dark = true
    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var equalizer: Equalizer? = null
    private var sleepHandler: Handler? = null
    private var sleepRunnable: Runnable? = null
    private var lyricText = ""
    private var lyricHandler: Handler? = null
    private var lyricRunnable: Runnable? = null
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var songList: LinearLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var artworkView: ImageView
    private lateinit var playButton: Button
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private val orange = Color.rgb(255, 138, 61)
    private val bg get() = if (dark) Color.rgb(15, 16, 20) else Color.rgb(247, 247, 250)
    private val panel get() = if (dark) Color.rgb(30, 32, 39) else Color.rgb(231, 232, 238)
    private val fg get() = if (dark) Color.rgb(245, 245, 248) else Color.rgb(24, 25, 30)
    private val muted get() = if (dark) Color.rgb(165, 169, 181) else Color.rgb(95, 97, 108)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        loadLocalSettings()
        buildUi()
        if (hasAudioPermission()) scanLibrary() else requestAudioPermission()
        connectPlayback()
    }

    private fun loadLocalSettings() {
        val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        dark = prefs.getBoolean("dark", true)
        sortMode = prefs.getString("sort", "Title") ?: "Title"
        readStringMap(prefs.getString("custom_titles", "{}"), customTitles)
        readStringMap(prefs.getString("custom_artists", "{}"), customArtists)
        readStringMap(prefs.getString("custom_albums", "{}"), customAlbums)
        readLongMap(prefs.getString("bookmarks", "{}"), bookmarks)
        readIntMap(prefs.getString("ratings", "{}"), ratings)
        try {
            val json = JSONArray(prefs.getString("playlists", "[]"))
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                val set = mutableSetOf<String>()
                val ids = item.optJSONArray("ids") ?: JSONArray()
                for (j in 0 until ids.length()) set.add(ids.optString(j))
                playlistMap[item.optString("name", "Playlist")] = set
            }
        } catch (_: Exception) {}
    }

    private fun saveLocalSettings() {
        val json = JSONArray()
        playlistMap.forEach { (name, ids) ->
            val item = JSONObject().put("name", name)
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            item.put("ids", arr)
            json.put(item)
        }
        getSharedPreferences("laczek_player", MODE_PRIVATE).edit()
            .putStringSet("favorites", favorites.toSet())
            .putString("playlists", json.toString())
            .putBoolean("dark", dark)
            .putString("sort", sortMode)
            .putString("custom_titles", JSONObject(customTitles as Map<*, *>).toString())
            .putString("custom_artists", JSONObject(customArtists as Map<*, *>).toString())
            .putString("custom_albums", JSONObject(customAlbums as Map<*, *>).toString())
            .putString("bookmarks", JSONObject(bookmarks as Map<*, *>).toString())
            .putString("ratings", JSONObject(ratings as Map<*, *>).toString())
            .apply()
    }

    private fun readStringMap(json: String?, target: MutableMap<String, String>) {
        try {
            val obj = JSONObject(json ?: "{}")
            val keys = obj.keys()
            while (keys.hasNext()) { val key = keys.next(); target[key] = obj.optString(key) }
        } catch (_: Exception) {}
    }
    private fun readLongMap(json: String?, target: MutableMap<String, Long>) {
        try {
            val obj = JSONObject(json ?: "{}")
            val keys = obj.keys()
            while (keys.hasNext()) { val key = keys.next(); target[key] = obj.optLong(key) }
        } catch (_: Exception) {}
    }
    private fun readIntMap(json: String?, target: MutableMap<String, Int>) {
        try {
            val obj = JSONObject(json ?: "{}")
            val keys = obj.keys()
            while (keys.hasNext()) { val key = keys.next(); target[key] = obj.optInt(key) }
        } catch (_: Exception) {}
    }

    private fun titleOf(t: Track) = customTitles[t.id.toString()] ?: t.title
    private fun artistOf(t: Track) = customArtists[t.id.toString()] ?: t.artist
    private fun albumOf(t: Track) = customAlbums[t.id.toString()] ?: t.album

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
            setBackgroundColor(bg)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "LaCzek Player"
            textSize = 27f
            setTextColor(orange)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(actionButton("⋮") { showMoreMenu() })
        root.addView(top)
        root.addView(TextView(this).apply {
            text = "YOUR MUSIC. YOUR PHONE. OFFLINE."
            textSize = 10.5f
            letterSpacing = .08f
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(12))
        })
        search = EditText(this).apply {
            hint = "Search songs, artists, albums…"
            textSize = 14f
            isSingleLine = true
            setTextColor(fg)
            setHintTextColor(muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(panel, dp(16))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(SimpleTextWatcher { query = it.trim(); renderLibrary() })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        val navScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        listOf("Songs", "Albums", "Artists", "Folders", "Recent", "Most played", "Duplicates", "Favorites", "Playlists").forEach { name ->
            nav.addView(actionButton(name) { section = name; currentPlaylist = null; renderLibrary() }.apply {
                textSize = 12f
                setPadding(dp(10), 0, dp(10), 0)
            })
        }
        navScroll.addView(nav)
        root.addView(navScroll)
        status = TextView(this).apply { textSize = 12f; setTextColor(muted); setPadding(0, 0, 0, dp(8)) }
        root.addView(status)
        val scroll = ScrollView(this)
        songList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(songList)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val playerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
            background = roundedBackground(panel, dp(20))
        }
        nowPlaying = TextView(this).apply {
            text = "Choose a song to begin"
            textSize = 14f
            setTextColor(fg)
            maxLines = 2
        }
        val nowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        artworkView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = roundedBackground(bg, dp(12)); contentDescription = "Album artwork" }
        nowRow.addView(artworkView, LinearLayout.LayoutParams(dp(58), dp(58)))
        nowRow.addView(nowPlaying, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        playerPanel.addView(nowRow)
        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(orange)
            thumbTintList = android.content.res.ColorStateList.valueOf(orange)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) controller?.let { if (it.duration > 0) it.seekTo(it.duration * value / 1000L) }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        playerPanel.addView(seek)
        timeText = TextView(this).apply { textSize = 10f; setTextColor(muted) }
        playerPanel.addView(timeText)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        controls.addView(actionButton("⏮") { skip(-1) })
        controls.addView(actionButton("−15") { controller?.let { it.seekTo((it.currentPosition - 15000).coerceAtLeast(0)) } })
        playButton = actionButton("▶") { togglePlayback() }
        controls.addView(playButton)
        controls.addView(actionButton("+15") { controller?.let { it.seekTo((it.currentPosition + 15000).coerceAtMost(it.duration.coerceAtLeast(0))) } })
        controls.addView(actionButton("⏭") { skip(1) })
        playerPanel.addView(controls)
        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        tools.addView(actionButton("Shuffle") { toggleShuffle() })
        tools.addView(actionButton("Repeat") { cycleRepeat() })
        tools.addView(actionButton("Lyrics") { showLyrics() })
        tools.addView(actionButton("Speed") { setPlaybackSpeed() })
        tools.addView(actionButton("Queue") { showQueue() })
        tools.addView(actionButton("EQ") { showEqualizer() })
        playerPanel.addView(tools)
        root.addView(playerPanel)
        setContentView(root)
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                updatePlaybackUi()
                Handler(Looper.getMainLooper()).postDelayed(this, 700)
            }
        })
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(fg)
        backgroundTintList = android.content.res.ColorStateList.valueOf(panel)
        setOnClickListener { action() }
    }

    private fun roundedBackground(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun hasAudioPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }
    private fun requestAudioPermission() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        requestPermissions(arrayOf(p), 100)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanLibrary()
        else status.text = "Allow audio access to scan music stored on this phone."
    }

    private fun scanLibrary() {
        tracks.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, pathColumn, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.COMPOSER, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE)
        try {
            contentResolver.query(collection, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val path = c.getColumnIndex(pathColumn)
                val albumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val composer = c.getColumnIndex(MediaStore.Audio.Media.COMPOSER)
                val year = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val size = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val mime = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                while (c.moveToNext()) {
                    val trackId = c.getLong(id)
                    tracks.add(Track(trackId, c.getString(title) ?: "Unknown title", c.getString(artist) ?: "Unknown artist",
                        c.getString(album) ?: "Unknown album", if (path >= 0) c.getString(path) ?: "Unknown folder" else "Unknown folder",
                        c.getLong(duration), ContentUris.withAppendedId(collection, trackId),
                        if (albumId >= 0) c.getLong(albumId) else -1L, if (composer >= 0) c.getString(composer) ?: "" else "",
                        if (year >= 0) c.getInt(year) else 0, if (size >= 0) c.getLong(size) else 0L,
                        if (mime >= 0) c.getString(mime) ?: "" else ""))
                }
            }
        } catch (_: Exception) {
            status.text = "Could not scan storage. Check audio permission."
        }
        renderLibrary()
    }

    private fun filteredTracks(): List<Track> {
        var result = tracks.filter { t ->
            val matches = query.isBlank() || listOf(titleOf(t), artistOf(t), albumOf(t), t.path, t.composer, t.year.toString(), t.mimeType).any { it.contains(query, true) }
            matches && when (section) {
                "Favorites" -> favorites.contains(t.id.toString())
                "Recent" -> recentTrackIds().contains(t.id.toString())
                "Most played" -> getSharedPreferences("laczek_player", MODE_PRIVATE).getInt("plays_" + t.id, 0) > 0
                "Duplicates" -> duplicateTrackIds().contains(t.id.toString())
                "Albums", "Artists", "Folders", "Playlists" -> true
                else -> true
            }
        }
        if (section == "Recent") {
            val order = recentTrackIds()
            return result.sortedBy { order.indexOf(it.id.toString()).let { pos -> if (pos < 0) Int.MAX_VALUE else pos } }
        }
        if (section == "Most played") {
            val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
            return result.sortedByDescending { prefs.getInt("plays_" + it.id, 0) }
        }
        if (section == "Playlists" && playlistMap.isNotEmpty()) {
            val names = playlistMap.keys.toList()
            val choices = listOf("All songs") + names
            val selected = currentPlaylist
            if (selected != null) result = result.filter { it.id.toString() in (playlistMap[selected] ?: emptySet()) }
        }
        return when (sortMode) {
            "Artist" -> result.sortedBy { it.artist.lowercase(Locale.ROOT) }
            "Album" -> result.sortedBy { it.album.lowercase(Locale.ROOT) }
            "Duration" -> result.sortedByDescending { it.duration }
            "Newest" -> result.sortedByDescending { it.id }
            else -> result.sortedBy { it.title.lowercase(Locale.ROOT) }
        }
    }
    private var currentPlaylist: String? = null
    private var pendingExportPlaylist: String? = null
    private var pendingCopyTrack: Track? = null

    private fun renderLibrary() {
        if (!::songList.isInitialized) return
        songList.removeAllViews()
        val visible = filteredTracks()
        status.text = when {
            tracks.isEmpty() -> "No local music found. Add audio files and tap ⋮ → Rescan library."
            section == "Albums" -> "${visible.map { it.album }.distinct().size} albums · ${tracks.size} tracks"
            section == "Artists" -> "${visible.map { it.artist }.distinct().size} artists · ${tracks.size} tracks"
            section == "Folders" -> "${visible.map { it.path }.distinct().size} folders"
            section == "Favorites" -> "${visible.size} favourite tracks"
            section == "Playlists" -> currentPlaylist ?: "${playlistMap.size} playlists · tap ⋮ to manage"
            else -> "${visible.size} songs on this device · sorted by $sortMode"
        }
        if (section == "Albums" || section == "Artists" || section == "Folders") {
            val groups = when (section) {
                "Albums" -> visible.groupBy { albumOf(it) }
                "Artists" -> visible.groupBy { artistOf(it) }
                else -> visible.groupBy { it.path }
            }
            groups.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, group) ->
                songList.addView(listRow(name, "${group.size} tracks · ${group.firstOrNull()?.artist ?: ""}") { showTrackGroup(name, group) })
            }
        } else if (section == "Playlists" && currentPlaylist == null) {
            playlistMap.forEach { (name, ids) ->
                val row = listRow(name, "${ids.size} tracks · long-press for options") {
                    currentPlaylist = name
                    renderLibrary()
                }
                row.setOnLongClickListener { managePlaylist(name); true }
                songList.addView(row)
            }
            songList.addView(listRow("+ Create playlist", "Save selected tracks in a local playlist") { createPlaylist() })
        } else {
            visible.forEach { track ->
                val row = listRow(titleOf(track), "${artistOf(track)}  ·  ${albumOf(track)}  ·  ${formatTime(track.duration)}") { playTrack(track) }
                row.setOnLongClickListener { showTrackMenu(track); true }
                songList.addView(row)
            }
        }
    }

    private fun listRow(title: String, subtitle: String, click: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(10), dp(11))
            background = roundedBackground(bg, dp(12))
            setOnClickListener { click() }
        }
        row.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(fg); maxLines = 1 })
        row.addView(TextView(this).apply { text = subtitle; textSize = 11f; setTextColor(muted); maxLines = 1; setPadding(0, dp(3), 0, 0) })
        songList.addView(View(this).apply { setBackgroundColor(panel) }, LinearLayout.LayoutParams(-1, dp(1)))
        return row
    }

    private fun showTrackGroup(name: String, group: List<Track>) {
        AlertDialog.Builder(this).setTitle(name).setItems(group.map { it.title }.toTypedArray()) { _, which -> playTrack(group[which]) }
            .setNeutralButton("Play all") { _, _ -> playTracks(group) }.show()
    }

    private fun showTrackMenu(track: Track) {
        val options = arrayOf(
            if (favorites.contains(track.id.toString())) "Remove favourite" else "Add to favourites",
            "Play next", "Add to queue", "Add to playlist", "Edit local tags", "Rate track",
            "Save playback bookmark", "Jump to bookmark", "Share audio", "Copy audio file", "Delete audio file", "Track details"
        )
        AlertDialog.Builder(this).setTitle(titleOf(track)).setItems(options) { _, which ->
            when (which) {
                0 -> { if (!favorites.add(track.id.toString())) favorites.remove(track.id.toString()); saveLocalSettings(); renderLibrary() }
                1 -> controller?.let { it.addMediaItem(0, mediaItem(track)); Toast.makeText(this, "Queued next", Toast.LENGTH_SHORT).show() }
                2 -> { controller?.addMediaItem(mediaItem(track)); Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show() }
                3 -> addTrackToPlaylist(track)
                4 -> editLocalTags(track)
                5 -> rateTrack(track)
                6 -> { val p = controller; bookmarks[track.id.toString()] = if (p?.currentMediaItem?.mediaId == track.id.toString()) p.currentPosition else 0L; saveLocalSettings(); Toast.makeText(this, "Bookmark saved locally", Toast.LENGTH_SHORT).show() }
                7 -> { val position = bookmarks[track.id.toString()]; if (position != null) { playTrack(track); controller?.seekTo(position) } else Toast.makeText(this, "No bookmark saved for this track", Toast.LENGTH_SHORT).show() }
                8 -> shareTrack(track)
                9 -> { pendingCopyTrack = track; val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; putExtra(Intent.EXTRA_TITLE, fileNameOf(track)) }; startActivityForResult(intent, 208) }
                10 -> deleteTrack(track)
                11 -> AlertDialog.Builder(this).setMessage("Title: ${titleOf(track)}\nArtist: ${artistOf(track)}\nAlbum: ${albumOf(track)}\nFolder: ${track.path}\nComposer: ${track.composer.ifBlank { "Unknown" }}\nYear: ${if (track.year > 0) track.year else "Unknown"}\nFormat: ${track.mimeType.ifBlank { "Unknown" }}\nFile size: ${formatBytes(track.sizeBytes)}\nDuration: ${formatTime(track.duration)}\nRating: ${ratings[track.id.toString()] ?: 0}/5\nBookmark: ${bookmarks[track.id.toString()]?.let { formatTime(it) } ?: "Not set"}\nURI: ${track.uri}").setPositiveButton("Close", null).show()
            }
        }.show()
    }

    private fun editLocalTags(track: Track) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        val title = EditText(this).apply { hint = "Song title"; setText(titleOf(track)); setTextColor(fg) }
        val artist = EditText(this).apply { hint = "Artist"; setText(artistOf(track)); setTextColor(fg) }
        val album = EditText(this).apply { hint = "Album"; setText(albumOf(track)); setTextColor(fg) }
        box.addView(title); box.addView(artist); box.addView(album)
        AlertDialog.Builder(this).setTitle("Edit tags in LaCzek Player").setMessage("These overrides are stored locally in the app; the original audio file is not modified.")
            .setView(box).setPositiveButton("Save") { _, _ ->
                val id = track.id.toString()
                customTitles[id] = title.text.toString().trim().ifEmpty { track.title }
                customArtists[id] = artist.text.toString().trim().ifEmpty { track.artist }
                customAlbums[id] = album.text.toString().trim().ifEmpty { track.album }
                saveLocalSettings(); renderLibrary()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun rateTrack(track: Track) {
        val choices = arrayOf("Unrated", "★", "★★", "★★★", "★★★★", "★★★★★")
        val id = track.id.toString()
        AlertDialog.Builder(this).setTitle("Rate " + titleOf(track))
            .setSingleChoiceItems(choices, (ratings[id] ?: 0).coerceIn(0, 5)) { dialog, which ->
                if (which == 0) ratings.remove(id) else ratings[id] = which
                saveLocalSettings(); dialog.dismiss(); Toast.makeText(this, "Rating saved on this phone", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun artworkUri(t: Track): Uri? = if (t.albumId > 0) Uri.parse("content://media/external/audio/albumart/" + t.albumId) else null

    private fun mediaItem(t: Track) = MediaItem.Builder().setUri(t.uri).setMediaId(t.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(titleOf(t)).setArtist(artistOf(t)).setAlbumTitle(albumOf(t)).setArtworkUri(artworkUri(t)).build()).build()

    private fun connectPlayback() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controller?.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updatePlaybackUi()
                    override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlaybackUi()
                    override fun onPlaybackStateChanged(playbackState: Int) = updatePlaybackUi()
                })
                updatePlaybackUi()
            } catch (_: ExecutionException) {
                Toast.makeText(this, "Playback service could not start", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }, mainExecutor)
    }

    private fun playTrack(track: Track) {
        val p = controller ?: run { Toast.makeText(this, "Player is still starting. Try again.", Toast.LENGTH_SHORT).show(); return }
        val ordered = filteredTracks()
        val list = (listOf(track) + ordered.filter { it.id != track.id }).distinctBy { it.id }
        p.setMediaItems(list.map { mediaItem(it) }, 0, 0L)
        p.prepare()
        p.play()
        val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
        val recent = recentTrackIds().toMutableList()
        recent.remove(track.id.toString())
        recent.add(0, track.id.toString())
        prefs.edit().putLong("last_track", track.id)
            .putInt("plays_" + track.id, prefs.getInt("plays_" + track.id, 0) + 1)
            .putString("recent_order", JSONArray(recent.take(200)).toString()).apply()
        updatePlaybackUi()
    }
    private fun playTracks(items: List<Track>) {
        controller?.setMediaItems(items.map { mediaItem(it) }, 0, 0L)
        controller?.prepare()
        controller?.play()
    }
    private fun togglePlayback() {
        val p = controller ?: return
        if (p.isPlaying) p.pause() else if (p.mediaItemCount > 0) p.play() else tracks.firstOrNull()?.let { playTrack(it) }
        updatePlaybackUi()
    }
    private fun skip(direction: Int) {
        val p = controller ?: return
        if (direction < 0) {
            if (p.currentPosition > 3000) p.seekTo(0) else p.seekToPreviousMediaItem()
        } else p.seekToNextMediaItem()
        updatePlaybackUi()
    }
    private fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; Toast.makeText(this, if (it.shuffleModeEnabled) "Shuffle on" else "Shuffle off", Toast.LENGTH_SHORT).show() }
    }
    private fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
            Toast.makeText(this, when (it.repeatMode) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat one"; else -> "Repeat off" }, Toast.LENGTH_SHORT).show()
        }
    }
    private fun updatePlaybackUi() {
        if (!::nowPlaying.isInitialized) return
        val p = controller
        val item = p?.currentMediaItem
        nowPlaying.text = if (item == null) "Choose a song to begin" else
            "${item.mediaMetadata.title ?: "Unknown title"}\n${item.mediaMetadata.artist ?: "Unknown artist"}"
        playButton.text = if (p?.isPlaying == true) "Ⅱ" else "▶"
        val currentTrack = tracks.firstOrNull { it.id.toString() == item?.mediaId }
        try { val art = currentTrack?.let { artworkUri(it) }; if (art != null) artworkView.setImageURI(art) else artworkView.setImageDrawable(null) } catch (_: Exception) { artworkView.setImageDrawable(null) }
        val duration = p?.duration?.takeIf { it > 0 } ?: 0L
        val position = p?.currentPosition?.coerceAtLeast(0) ?: 0L
        seek.progress = if (duration > 0) (position * 1000 / duration).toInt() else 0
        timeText.text = "${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun duplicateTrackIds(): Set<String> = tracks.groupBy {
        titleOf(it).trim().lowercase(Locale.ROOT) + "|" + artistOf(it).trim().lowercase(Locale.ROOT)
    }.filterValues { it.size > 1 }.values.flatten().map { it.id.toString() }.toSet()

    private fun recentTrackIds(): List<String> {
        return try {
            val arr = JSONArray(getSharedPreferences("laczek_player", MODE_PRIVATE).getString("recent_order", "[]"))
            (0 until arr.length()).map { arr.optString(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun setPlaybackSpeed() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val labels = speeds.map { it.toString() + "×" }.toTypedArray()
        val current = controller?.playbackParameters?.speed ?: 1f
        val selected = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - current) } ?: 2
        AlertDialog.Builder(this).setTitle("Playback speed").setSingleChoiceItems(labels, selected) { dialog, which ->
            controller?.setPlaybackSpeed(speeds[which])
            dialog.dismiss()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showQueue() {
        val p = controller
        if (p == null || p.mediaItemCount == 0) {
            AlertDialog.Builder(this).setTitle("Playback queue").setMessage("The queue is empty. Play a song first.").setPositiveButton("Close", null).show()
            return
        }
        val labels = (0 until p.mediaItemCount).map { i ->
            val item = p.getMediaItemAt(i)
            (if (i == p.currentMediaItemIndex) "▶ " else "") + (item.mediaMetadata.title ?: item.mediaId)
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Queue · " + p.mediaItemCount + " tracks").setItems(labels) { _, which -> p.seekToDefaultPosition(which); p.play() }
            .setNeutralButton("Clear queue") { _, _ -> p.clearMediaItems() }.show()
    }

    private fun managePlaylist(name: String) {
        val options = arrayOf("Play playlist", "Rename playlist", "Export M3U", "Delete playlist")
        AlertDialog.Builder(this).setTitle(name).setItems(options) { _, which ->
            when (which) {
                0 -> {
                    val ids = playlistMap[name] ?: emptySet()
                    val selected = tracks.filter { it.id.toString() in ids }
                    if (selected.isEmpty()) Toast.makeText(this, "This playlist has no matching local tracks", Toast.LENGTH_SHORT).show() else playTracks(selected)
                }
                1 -> {
                    val input = EditText(this).apply { setText(name); setTextColor(fg) }
                    AlertDialog.Builder(this).setTitle("Rename playlist").setView(input).setPositiveButton("Save") { _, _ ->
                        val newName = input.text.toString().trim()
                        if (newName.isNotEmpty() && newName != name) { playlistMap[newName] = playlistMap.remove(name) ?: mutableSetOf(); if (currentPlaylist == name) currentPlaylist = newName; saveLocalSettings(); renderLibrary() }
                    }.setNegativeButton("Cancel", null).show()
                }
                2 -> { pendingExportPlaylist = name; val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/x-mpegurl"; putExtra(Intent.EXTRA_TITLE, name.replace(Regex("[^A-Za-z0-9._ -]"), "_") + ".m3u") }; startActivityForResult(intent, 205) }
                3 -> AlertDialog.Builder(this).setMessage("Delete playlist $name? The music files will not be deleted.").setPositiveButton("Delete") { _, _ -> playlistMap.remove(name); if (currentPlaylist == name) currentPlaylist = null; saveLocalSettings(); renderLibrary() }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun importM3u() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(intent, 206)
    }

    private fun createPlaylist() {
        val input = EditText(this).apply { hint = "Playlist name"; setTextColor(fg) }
        AlertDialog.Builder(this).setTitle("New offline playlist").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { playlistMap.putIfAbsent(name, mutableSetOf()); currentPlaylist = name; saveLocalSettings(); renderLibrary() }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun addTrackToPlaylist(track: Track) {
        if (playlistMap.isEmpty()) { createPlaylist(); Toast.makeText(this, "Create a playlist, then add this track", Toast.LENGTH_LONG).show(); return }
        val names = playlistMap.keys.toTypedArray()
        AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names) { _, which ->
            playlistMap[names[which]]?.add(track.id.toString())
            saveLocalSettings()
            Toast.makeText(this, "Added to ${names[which]}", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showMoreMenu() {
        val options = arrayOf("Rescan library", "Sort: $sortMode", "Create playlist", "Import local audio", "Import M3U playlist", "Sleep timer", "Theme: ${if (dark) "Dark" else "Light"}", "Export settings backup", "Restore settings backup", "Clear listening history", "Playlist overview")
        AlertDialog.Builder(this).setTitle("LaCzek tools").setItems(options) { _, which ->
            when (which) {
                0 -> scanLibrary()
                1 -> chooseSort()
                2 -> createPlaylist()
                3 -> importAudio()
                4 -> importM3u()
                5 -> setSleepTimer()
                6 -> { dark = !dark; saveLocalSettings(); rebuildUi() }
                7 -> exportBackup()
                8 -> importBackup()
                9 -> { getSharedPreferences("laczek_player", MODE_PRIVATE).edit().remove("last_track").remove("recent_order").apply(); Toast.makeText(this, "Playback history cleared", Toast.LENGTH_SHORT).show() }
                10 -> showPlaylistOverview()
            }
        }.show()
    }
    private fun chooseSort() {
        val modes = arrayOf("Title", "Artist", "Album", "Duration", "Newest")
        AlertDialog.Builder(this).setTitle("Sort library").setSingleChoiceItems(modes, modes.indexOf(sortMode).coerceAtLeast(0)) { d, which ->
            sortMode = modes[which]; saveLocalSettings(); renderLibrary(); d.dismiss()
        }.show()
    }
    private fun setSleepTimer() {
        val options = arrayOf("5 minutes", "10 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "Stop after current song", "Fade out over 10 seconds", "Cancel timer")
        AlertDialog.Builder(this).setTitle("Offline sleep timer").setItems(options) { _, which ->
            sleepRunnable?.let { sleepHandler?.removeCallbacks(it) }
            if (which == 8) { Toast.makeText(this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show(); return@setItems }
            if (which == 6) {
                controller?.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { controller?.pause(); controller?.removeListener(this) }
                })
                Toast.makeText(this, "Will stop after this track", Toast.LENGTH_SHORT).show()
            } else if (which == 7) {
                fadeOutAndPause()
            } else {
                val mins = intArrayOf(5,10,15,30,45,60)[which]
                sleepHandler = Handler(Looper.getMainLooper())
                sleepRunnable = Runnable { controller?.pause(); Toast.makeText(this, "Sleep timer stopped playback", Toast.LENGTH_SHORT).show() }
                sleepHandler?.postDelayed(sleepRunnable!!, mins * 60_000L)
                Toast.makeText(this, "Playback stops in $mins minutes", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun fadeOutAndPause() {
        val p = controller ?: return
        sleepHandler = Handler(Looper.getMainLooper())
        var step = 0
        sleepRunnable = object : Runnable {
            override fun run() {
                step += 1
                p.volume = (1f - step / 20f).coerceAtLeast(0f)
                if (step >= 20) { p.pause(); p.volume = 1f; sleepRunnable = null; Toast.makeText(this@MainActivity, "Faded out and paused", Toast.LENGTH_SHORT).show() }
                else sleepHandler?.postDelayed(this, 500L)
            }
        }
        sleepRunnable?.run()
    }

    private fun chooseLyrics() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(i, 201)
    }
    private data class TimedLyric(val timeMs: Long, val text: String)

    private fun parseLyrics(): List<TimedLyric> {
        val pattern = Regex("""\[([0-9]{1,2}):([0-9]{2})(?:[.:]([0-9]{1,3}))?]""")
        val parsed = mutableListOf<TimedLyric>()
        lyricText.lineSequence().forEach { line ->
            val matches = pattern.findAll(line).toList()
            val text = pattern.replace(line, "").trim()
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                parsed.add(TimedLyric(minutes * 60_000L + seconds * 1_000L + fraction, text))
            }
        }
        if (parsed.isEmpty()) return lyricText.lines().filter { it.isNotBlank() }.map { TimedLyric(0L, it) }
        return parsed.sortedBy { it.timeMs }
    }

    private fun showLyrics() {
        if (lyricText.isBlank()) { chooseLyrics(); return }
        val lines = parseLyrics()
        val scroll = ScrollView(this)
        val textView = TextView(this).apply {
            setTextColor(fg); textSize = 17f
            setPadding(dp(16), dp(12), dp(16), dp(18))
            setLineSpacing(dp(8).toFloat(), 1f)
        }
        scroll.addView(textView)
        val dialog = AlertDialog.Builder(this).setTitle("Synchronized local lyrics")
            .setView(scroll).setPositiveButton("Load another file") { _, _ -> chooseLyrics() }
            .setNegativeButton("Close", null).create()
        lyricHandler?.let { handler -> lyricRunnable?.let { handler.removeCallbacks(it) } }
        lyricHandler = Handler(Looper.getMainLooper())
        lyricRunnable = object : Runnable {
            override fun run() {
                val position = controller?.currentPosition ?: 0L
                val current = lines.indexOfLast { it.timeMs <= position }.coerceAtLeast(0)
                textView.text = lines.mapIndexed { index, line -> (if (index == current) "▶  " else "    ") + line.text }.joinToString("\n\n")
                scroll.post { scroll.smoothScrollTo(0, (current * textView.lineHeight * 2).coerceAtLeast(0)) }
                lyricHandler?.postDelayed(this, 300L)
            }
        }
        dialog.setOnDismissListener {
            lyricHandler?.let { handler -> lyricRunnable?.let { handler.removeCallbacks(it) } }
            lyricRunnable = null
        }
        dialog.show()
        lyricRunnable?.run()
    }
    private fun showEqualizer() {
        val sessionId = PlaybackService.audioSessionId
        if (sessionId <= 0) { Toast.makeText(this, "Start a song before opening the equalizer", Toast.LENGTH_SHORT).show(); return }
        try {
            equalizer?.release()
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
            val eq = equalizer!!
            val bands = (0 until eq.numberOfBands).map { it.toShort() }
            val names = bands.map { band -> "${eq.getCenterFreq(band) / 1000} Hz" }.toTypedArray()
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
            for (band in bands) {
                val label = TextView(this).apply { text = "${eq.getCenterFreq(band) / 1000} Hz"; setTextColor(fg) }
                val slider = SeekBar(this).apply {
                    val range = eq.bandLevelRange
                    max = range[1] - range[0]
                    progress = eq.getBandLevel(band) - range[0]
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) { if (user) try { eq.setBandLevel(band, (v + range[0]).toShort()) } catch (_: Exception) {} }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }
                layout.addView(label); layout.addView(slider)
            }
            AlertDialog.Builder(this).setTitle("Equalizer").setView(ScrollView(this).apply { addView(layout) })
                .setNeutralButton("Disable") { _, _ -> eq.enabled = false }
                .setPositiveButton("Done", null).show()
        } catch (_: Exception) { Toast.makeText(this, "Equalizer is not supported on this device", Toast.LENGTH_LONG).show() }
    }

    private fun importAudio() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        startActivityForResult(i, 202)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            201 -> {
                data.data?.let { uri ->
                    try {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { lyricText = it.readText().take(100_000) }
                        showLyrics()
                    } catch (_: Exception) { Toast.makeText(this, "Could not read this lyrics file", Toast.LENGTH_SHORT).show() }
                }
            }
            203 -> {
                data.data?.let { uri ->
                    try {
                        val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
                        val backup = JSONObject()
                            .put("favorites", JSONArray(favorites.toList()))
                            .put("dark", dark)
                            .put("sort", sortMode)
                            .put("playlists", JSONArray(prefs.getString("playlists", "[]")))
                            .put("custom_titles", JSONObject(customTitles as Map<*, *>))
                            .put("custom_artists", JSONObject(customArtists as Map<*, *>))
                            .put("custom_albums", JSONObject(customAlbums as Map<*, *>))
                            .put("bookmarks", JSONObject(bookmarks as Map<*, *>))
                            .put("ratings", JSONObject(ratings as Map<*, *>))
                        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(backup.toString(2)) }
                        Toast.makeText(this, "Local backup exported", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(this, "Backup export failed", Toast.LENGTH_SHORT).show() }
                }
            }
            204 -> {
                data.data?.let { uri ->
                    try {
                        val backup = contentResolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()) }
                            ?: throw IllegalStateException("Empty backup")
                        favorites.clear()
                        val favs = backup.optJSONArray("favorites") ?: JSONArray()
                        for (i in 0 until favs.length()) favorites.add(favs.optString(i))
                        dark = backup.optBoolean("dark", true)
                        sortMode = backup.optString("sort", "Title")
                        customTitles.clear(); readStringMap(backup.optString("custom_titles", "{}"), customTitles)
                        customArtists.clear(); readStringMap(backup.optString("custom_artists", "{}"), customArtists)
                        customAlbums.clear(); readStringMap(backup.optString("custom_albums", "{}"), customAlbums)
                        bookmarks.clear(); readLongMap(backup.optString("bookmarks", "{}"), bookmarks)
                        ratings.clear(); readIntMap(backup.optString("ratings", "{}"), ratings)
                        playlistMap.clear()
                        val lists = backup.optJSONArray("playlists") ?: JSONArray()
                        for (i in 0 until lists.length()) {
                            val item = lists.getJSONObject(i)
                            val ids = item.optJSONArray("ids") ?: JSONArray()
                            val set = mutableSetOf<String>()
                            for (j in 0 until ids.length()) set.add(ids.optString(j))
                            playlistMap[item.optString("name", "Playlist")] = set
                        }
                        saveLocalSettings()
                        rebuildUi()
                        Toast.makeText(this, "Local backup restored", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(this, "Could not restore this backup", Toast.LENGTH_SHORT).show() }
                }
            }
            207 -> {
                if (resultCode == RESULT_OK) { Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show(); scanLibrary() }
            }
            208 -> {
                val target = data.data
                val source = pendingCopyTrack
                if (target != null && source != null) {
                    try {
                        contentResolver.openInputStream(source.uri)?.use { input ->
                            contentResolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Could not read source audio")
                        Toast.makeText(this, "Audio file copied", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(this, "Could not copy this audio file", Toast.LENGTH_LONG).show() }
                }
                pendingCopyTrack = null
            }
            205 -> {
                val name = pendingExportPlaylist
                val ids = if (name != null) playlistMap[name] ?: emptySet() else emptySet()
                data.data?.let { uri ->
                    try {
                        val body = buildString {
                            append("#EXTM3U\n")
                            tracks.filter { it.id.toString() in ids }.forEach { t ->
                                append("#EXTINF:").append(t.duration / 1000).append(",").append(titleOf(t)).append("\n")
                                append(t.uri.toString()).append("\n")
                            }
                        }
                        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(body) }
                        Toast.makeText(this, "Playlist exported as M3U", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(this, "Could not export playlist", Toast.LENGTH_SHORT).show() }
                }
                pendingExportPlaylist = null
            }
            206 -> {
                data.data?.let { uri ->
                    try {
                        val body = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                        val imported = mutableSetOf<String>()
                        var lastTitle = ""
                        body.lineSequence().forEach { line ->
                            when {
                                line.startsWith("#EXTINF:", true) -> lastTitle = line.substringAfter(",").trim()
                                line.isNotBlank() && !line.startsWith("#") -> {
                                    val parsed = Uri.parse(line)
                                    val id = parsed.lastPathSegment
                                    val match = tracks.firstOrNull { it.id.toString() == id } ?: tracks.firstOrNull { titleOf(it).equals(lastTitle, true) || it.title.equals(lastTitle, true) }
                                    if (match != null) imported.add(match.id.toString())
                                    lastTitle = ""
                                }
                            }
                        }
                        val name = "Imported playlist " + (playlistMap.size + 1)
                        playlistMap[name] = imported
                        saveLocalSettings(); section = "Playlists"; currentPlaylist = null; renderLibrary()
                        Toast.makeText(this, "Imported ${imported.size} matching local tracks", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(this, "Could not import this M3U file", Toast.LENGTH_SHORT).show() }
                }
            }
            202 -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                val items = uris.mapIndexed { idx, uri -> MediaItem.Builder().setUri(uri).setMediaId("picked-$idx").build() }
                controller?.setMediaItems(items); controller?.prepare(); controller?.play()
            }
        }
    }

    private fun deleteTrack(track: Track) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(track.uri))
                startIntentSenderForResult(request.intentSender, 207, null, 0, 0, 0)
            } else {
                val deleted = contentResolver.delete(track.uri, null, null)
                if (deleted > 0) { Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show(); scanLibrary() }
                else Toast.makeText(this, "Android did not delete this file", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) { Toast.makeText(this, "Deletion was denied or unsupported by this device", Toast.LENGTH_LONG).show() }
    }

    private fun fileNameOf(track: Track): String {
        return try {
            contentResolver.query(track.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: titleOf(track) + ".audio"
        } catch (_: Exception) { titleOf(track) + ".audio" }
    }

    private fun shareTrack(track: Track) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "audio/*"; putExtra(Intent.EXTRA_STREAM, track.uri); putExtra(Intent.EXTRA_SUBJECT, track.title); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(Intent.createChooser(intent, "Share local audio"))
    }
    private fun showPlaylistOverview() {
        val msg = if (playlistMap.isEmpty()) "No playlists yet." else playlistMap.entries.joinToString("\n") { "${it.key}: ${it.value.size} tracks" }
        AlertDialog.Builder(this).setTitle("Offline playlists").setMessage(msg)
            .setPositiveButton("Create") { _, _ -> createPlaylist() }.setNegativeButton("Close", null).show()
    }
    private fun exportBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "laczek-player-backup.json") }
        startActivityForResult(i, 203)
    }
    private fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json" }
        startActivityForResult(i, 204)
    }
    private fun rebuildUi() {
        buildUi()
        if (::status.isInitialized) renderLibrary()
        updatePlaybackUi()
        window.statusBarColor = bg; window.navigationBarColor = bg
    }
    private fun formatBytes(bytes: Long): String = when { bytes <= 0 -> "Unknown"; bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0); else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0)) }
    private fun formatTime(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60000, (ms.coerceAtLeast(0) / 1000) % 60)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        equalizer?.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

private class SimpleTextWatcher(val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString() ?: "") }
    override fun afterTextChanged(s: android.text.Editable?) {}
}
