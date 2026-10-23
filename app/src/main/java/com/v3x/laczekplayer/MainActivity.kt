package com.v3x.laczekplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentValues
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.animation.ArgbEvaluator
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.DocumentsContract
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
    val albumId: Long, val composer: String, val year: Int, val sizeBytes: Long, val mimeType: String, val genre: String
)

class MainActivity : Activity() {
    private val tracks = mutableListOf<Track>()
    private val genreByTrackId = mutableMapOf<Long, String>()
    private val favorites = mutableSetOf<String>()
    private val hiddenFolders = mutableSetOf<String>()
    private val playlistMap = linkedMapOf<String, MutableSet<String>>()
    private val ratings = mutableMapOf<String, Int>()
    private val bookmarks = mutableMapOf<String, Long>()
    private val customTitles = mutableMapOf<String, String>()
    private val customArtists = mutableMapOf<String, String>()
    private val customAlbums = mutableMapOf<String, String>()
    private val customGenres = mutableMapOf<String, String>()
    private val customYears = mutableMapOf<String, String>()
    private val lyricsByTrack = mutableMapOf<String, String>()
    private val trackSpeeds = mutableMapOf<String, Float>()
    private val customArtworkUris = mutableMapOf<String, String>()
    private val playlistArtworkUris = mutableMapOf<String, String>()
    private val folderTreeUris = mutableMapOf<String, String>()
    private val folderDocumentCache = mutableMapOf<String, Uri?>()
    private var minFileSizeKb = 0L
    private var minDurationSeconds = 0L
    private var pendingFolderPath: String? = null
    private var playlistArtworkTarget: String? = null
    private var partyAnimator: ValueAnimator? = null
    private var section = "Songs"
    private var query = ""
    private var filterArtist = ""
    private var filterYear = ""
    private var filterFormat = ""
    private var filterMinSeconds = ""
    private var filterMaxSeconds = ""
    private var selectionMode = false
    private val selectedTrackIds = mutableSetOf<String>()
    private var lastAccentTrackId = ""
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiTicker: Runnable? = null
    private var sortMode = "Title"
    private var dark = true
    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
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
    private var miniPlayerPanel: View? = null
    private lateinit var miniArtwork: ImageView
    private lateinit var miniTrackLabel: TextView
    private lateinit var miniArtistLabel: TextView
    private lateinit var miniPlayButton: Button
    private var fullPlayerOpen = false
    private var accentColor = Color.rgb(157, 104, 255)
    private val orange get() = accentColor
    private val bg get() = if (dark) Color.rgb(7, 6, 12) else Color.rgb(247, 245, 252)
    private val panel get() = if (dark) Color.rgb(22, 19, 32) else Color.rgb(235, 230, 247)
    private val fg get() = if (dark) Color.rgb(250, 248, 255) else Color.rgb(25, 20, 36)
    private val muted get() = if (dark) Color.rgb(174, 164, 199) else Color.rgb(103, 91, 126)

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
        accentColor = prefs.getInt("accent", Color.rgb(157, 104, 255)).let { if (it == Color.rgb(255, 138, 61)) Color.rgb(157, 104, 255) else it }
        hiddenFolders.addAll(prefs.getStringSet("hidden_folders", emptySet()) ?: emptySet())
        sortMode = prefs.getString("sort", "Title") ?: "Title"
        readStringMap(prefs.getString("custom_titles", "{}"), customTitles)
        readStringMap(prefs.getString("custom_artists", "{}"), customArtists)
        readStringMap(prefs.getString("custom_albums", "{}"), customAlbums)
        readStringMap(prefs.getString("custom_genres", "{}"), customGenres)
        readStringMap(prefs.getString("custom_years", "{}"), customYears)
        readStringMap(prefs.getString("lyrics_by_track", "{}"), lyricsByTrack)
        try { val speeds = JSONObject(prefs.getString("track_speeds", "{}")); val keys = speeds.keys(); while (keys.hasNext()) { val key = keys.next(); trackSpeeds[key] = speeds.optDouble(key, 1.0).toFloat().coerceIn(0.25f, 3.0f) } } catch (_: Exception) {}
        readStringMap(prefs.getString("custom_artwork_uris", "{}"), customArtworkUris)
        readStringMap(prefs.getString("playlist_artwork_uris", "{}"), playlistArtworkUris)
        readStringMap(prefs.getString("folder_tree_uris", "{}"), folderTreeUris)
        minFileSizeKb = prefs.getLong("min_file_size_kb", 0L).coerceAtLeast(0L)
        minDurationSeconds = prefs.getLong("min_duration_seconds", 0L).coerceAtLeast(0L)
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
            .putInt("accent", accentColor)
            .putStringSet("hidden_folders", hiddenFolders.toSet())
            .putString("sort", sortMode)
            .putString("custom_titles", JSONObject(customTitles as Map<*, *>).toString())
            .putString("custom_artists", JSONObject(customArtists as Map<*, *>).toString())
            .putString("custom_albums", JSONObject(customAlbums as Map<*, *>).toString())
            .putString("custom_genres", JSONObject(customGenres as Map<*, *>).toString())
            .putString("custom_years", JSONObject(customYears as Map<*, *>).toString())
            .putString("lyrics_by_track", JSONObject(lyricsByTrack as Map<*, *>).toString())
            .putString("track_speeds", JSONObject(trackSpeeds.mapValues { it.value.toDouble() } as Map<*, *>).toString())
            .putString("custom_artwork_uris", JSONObject(customArtworkUris as Map<*, *>).toString())
            .putString("playlist_artwork_uris", JSONObject(playlistArtworkUris as Map<*, *>).toString())
            .putString("folder_tree_uris", JSONObject(folderTreeUris as Map<*, *>).toString())
            .putLong("min_file_size_kb", minFileSizeKb)
            .putLong("min_duration_seconds", minDurationSeconds)
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
    private fun genreOf(t: Track) = customGenres[t.id.toString()] ?: t.genre
    private fun yearOf(t: Track) = customYears[t.id.toString()]?.toIntOrNull() ?: t.year

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (fullPlayerOpen) {
            showLibraryScreen()
        } else {
            super.onBackPressed()
        }
    }

    private fun showLibraryScreen() {
        fullPlayerOpen = false
        buildUi()
        renderLibrary()
        updatePlaybackUi()
    }

    private fun showFullPlayer() {
        val p = controller
        val item = p?.currentMediaItem
        if (item == null) {
            Toast.makeText(this, "Choose a song first", Toast.LENGTH_SHORT).show()
            return
        }
        fullPlayerOpen = true
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = if (dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(14))
            setBackgroundColor(bg)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(18))
        }
        top.addView(actionButton("⌄") { showLibraryScreen() }.apply {
            textSize = 27f
            background = roundedBackground(panel, dp(17))
            minWidth = dp(48); minimumWidth = dp(48)
            minHeight = dp(48); minimumHeight = dp(48)
            contentDescription = "Back to library"
        })
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        heading.addView(TextView(this).apply {
            text = "NOW PLAYING"
            textSize = 10f
            letterSpacing = .16f
            setTextColor(orange)
            gravity = Gravity.CENTER
        })
        heading.addView(TextView(this).apply {
            text = "LaCzek Player"
            textSize = 14f
            setTextColor(fg)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(actionButton("⋮") {
            controller?.currentMediaItem?.mediaId?.let { id ->
                tracks.firstOrNull { it.id.toString() == id }?.let { showTrackMenu(it) }
            }
        }.apply {
            textSize = 24f
            background = roundedBackground(panel, dp(17))
            minWidth = dp(48); minimumWidth = dp(48)
            minHeight = dp(48); minimumHeight = dp(48)
            contentDescription = "Track menu"
        })
        screen.addView(top)

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(panel, dp(28))
            clipToOutline = true
            contentDescription = "Album artwork"
            setOnClickListener { showArtworkPreview() }
        }
        body.addView(artworkView, LinearLayout.LayoutParams(-1, 0).apply {
            height = dp(300)
            width = -1
            bottomMargin = dp(24)
        })
        nowPlaying = TextView(this).apply {
            text = ""
            textSize = 22f
            setTextColor(fg)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        body.addView(nowPlaying, LinearLayout.LayoutParams(-1, -2))
        body.addView(TextView(this).apply {
            text = item.mediaMetadata.artist?.toString() ?: "Unknown artist"
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(3))
        }, LinearLayout.LayoutParams(-1, -2))
        val albumText = TextView(this).apply {
            text = item.mediaMetadata.albumTitle?.toString() ?: "Local audio"
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        body.addView(albumText)

        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(orange)
            thumbTintList = android.content.res.ColorStateList.valueOf(orange)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) Color.rgb(65, 67, 76) else Color.rgb(205, 207, 215))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) controller?.let { if (it.duration > 0) it.seekTo(it.duration * value / 1000L) }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        body.addView(seek, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(12) })
        timeText = TextView(this).apply {
            textSize = 11f
            setTextColor(muted)
            gravity = Gravity.CENTER_VERTICAL
        }
        body.addView(timeText, LinearLayout.LayoutParams(-1, dp(20)))
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, dp(18))
        }
        controls.addView(actionButton("⏮") { skip(-1) }.apply {
            textSize = 23f; background = roundedBackground(panel, dp(20))
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) })
        controls.addView(actionButton("−15") { controller?.let { it.seekTo((it.currentPosition - 15000).coerceAtLeast(0)) } }.apply {
            textSize = 12f; background = roundedBackground(panel, dp(18))
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
        playButton = actionButton("▶") { togglePlayback() }.apply {
            textSize = 26f
            setTextColor(Color.BLACK)
            background = roundedBackground(orange, dp(25))
        }
        controls.addView(playButton, LinearLayout.LayoutParams(dp(70), dp(62)).apply { marginEnd = dp(10) })
        controls.addView(actionButton("+15") { controller?.let { it.seekTo((it.currentPosition + 15000).coerceAtMost(it.duration.coerceAtLeast(0))) } }.apply {
            textSize = 12f; background = roundedBackground(panel, dp(18))
        }, LinearLayout.LayoutParams(dp(48), dp(52)).apply { marginEnd = dp(10) })
        controls.addView(actionButton("⏭") { skip(1) }.apply {
            textSize = 23f; background = roundedBackground(panel, dp(20))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        body.addView(controls)
        val toolGrid = GridLayout(this).apply {
            columnCount = 3
            rowCount = 3
            useDefaultMargins = false
        }
        listOf(
            "Shuffle" to { toggleShuffle() },
            "Repeat" to { cycleRepeat() },
            "Queue" to { showQueue() },
            "Lyrics" to { showLyrics() },
            "Speed" to { setPlaybackSpeed() },
            "A-B repeat" to { toggleABRepeat() },
            "Equalizer" to { showEqualizer() },
            "Favorite" to {
                controller?.currentMediaItem?.mediaId?.let { id ->
                    if (!favorites.add(id)) favorites.remove(id)
                    saveLocalSettings()
                    renderLibrary()
                    Toast.makeText(this, if (favorites.contains(id)) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
                }
                Unit
            },
            "Sleep timer" to { setSleepTimer() },
            "Artwork" to { showArtworkPreview() },
            "Track info" to {
                controller?.currentMediaItem?.mediaId?.let { id ->
                    tracks.firstOrNull { it.id.toString() == id }?.let { showTrackMenu(it) }
                }
                Unit
            }
        ).forEach { (label, action) ->
            val button = actionButton(label, action).apply {
                textSize = 12f
                background = roundedBackground(panel, dp(15))
                setPadding(dp(6), 0, dp(6), 0)
                minHeight = dp(46); minimumHeight = dp(46)
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(46)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            toolGrid.addView(button, params)
        }
        body.addView(toolGrid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        scroll.addView(body)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(screen)
        updatePlaybackUi()
    }

    private fun buildUi() {
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = if (dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(14))
        }
        val brandMark = ImageView(this).apply {
            setImageResource(R.drawable.ic_laczek_airpod)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "LaCzek Player AirPod logo"
        }
        header.addView(brandMark, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) })
        val brandColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandColumn.addView(TextView(this).apply {
            text = "LaCzek"
            textSize = 27f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = -.035f
        })
        brandColumn.addView(TextView(this).apply {
            text = "PLAYER  •  MUSIC + VIDEO"
            textSize = 10f
            letterSpacing = .16f
            setTextColor(orange)
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(brandColumn, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton(if (selectionMode) "Done" else "Select") {
            selectionMode = !selectionMode
            if (selectionMode) { section = "Songs"; currentPlaylist = null } else selectedTrackIds.clear()
            renderLibrary(); buildUiSelectionRefresh()
        }.apply { textSize = 12f; setPadding(dp(7), 0, dp(7), 0); minWidth = dp(58); minimumWidth = dp(58); minHeight = dp(44); minimumHeight = dp(44); background = roundedBackground(panel, dp(14)) })
        header.addView(actionButton("▣") { startActivity(Intent(this, VideoActivity::class.java)) }.apply {
            textSize = 19f
            background = roundedBackground(panel, dp(16))
            minWidth = dp(46); minimumWidth = dp(46)
            minHeight = dp(46); minimumHeight = dp(46)
            contentDescription = "Open local videos"
        })
        header.addView(actionButton("⚙") { showMoreMenu() }.apply {
            textSize = 20f
            background = roundedBackground(panel, dp(16))
            minWidth = dp(48); minimumWidth = dp(48)
            minHeight = dp(48); minimumHeight = dp(48)
        })
        root.addView(header)

        search = EditText(this).apply {
            hint = "Search songs, artists, albums"
            textSize = 14f
            isSingleLine = true
            setTextColor(fg)
            setHintTextColor(muted)
            setPadding(dp(16), 0, dp(16), 0)
            background = roundedBackground(panel, dp(17))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(SimpleTextWatcher { query = it.trim(); renderLibrary() })
        }
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
        searchRow.addView(actionButton("Filters") { showAdvancedSearch() }.apply { textSize = 12f; background = roundedBackground(panel, dp(16)); minWidth = dp(78); minimumWidth = dp(78); minHeight = dp(48); minimumHeight = dp(48) })
        root.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val navScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        listOf("Songs", "Albums", "Artists", "Featured artists", "Folders", "Genres", "Composers", "Years",
            "Formats", "Recent", "Recently added", "Unplayed", "Most played", "Top rated",
            "Longest", "Shortest", "Large files", "Rated", "Duplicates", "Favorites", "Playlists").forEach { name ->
            val selected = section == name
            nav.addView(actionButton(name) {
                section = name
                currentPlaylist = null
                renderLibrary()
                buildNavSelection(nav)
            }.apply {
                textSize = 12f
                setPadding(dp(15), 0, dp(15), 0)
                minHeight = dp(38); minimumHeight = dp(38)
                background = roundedBackground(if (selected) orange else panel, dp(20))
                setTextColor(if (selected) Color.BLACK else fg)
                stateListAnimator = null
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(7) })
        }
        navScroll.addView(nav)
        root.addView(navScroll)

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), 0, 0, dp(8))
        }
        root.addView(status)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(12))
        }
        songList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        scroll.addView(songList)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val miniPlayer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = roundedBackground(panel, dp(20))
            elevation = dp(3).toFloat()
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { showFullPlayer() }
        }
        miniArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(bg, dp(13))
            clipToOutline = true
            contentDescription = "Now playing artwork"
        }
        miniPlayer.addView(miniArtwork, LinearLayout.LayoutParams(dp(48), dp(48)))
        val miniLabels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(4), 0)
        }
        miniTrackLabel = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(fg)
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniArtistLabel = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(muted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        miniLabels.addView(miniTrackLabel)
        miniLabels.addView(miniArtistLabel)
        miniPlayer.addView(miniLabels, LinearLayout.LayoutParams(0, -2, 1f))
        miniPlayButton = actionButton("▶") { togglePlayback() }.apply {
            textSize = 20f
            setTextColor(orange)
            background = roundedBackground(bg, dp(16))
            minWidth = dp(46); minimumWidth = dp(46)
            minHeight = dp(46); minimumHeight = dp(46)
        }
        miniPlayer.addView(miniPlayButton)
        miniPlayer.setOnLongClickListener {
            controller?.currentMediaItem?.mediaId?.let { id -> tracks.firstOrNull { it.id.toString() == id }?.let { showTrackMenu(it) } }
            true
        }
        miniPlayerPanel = miniPlayer
        applyMiniPartyMode()
        root.addView(miniPlayer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        setContentView(root)
        root.alpha = 0.94f; root.translationY = dp(8).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        uiTicker?.let { uiHandler.removeCallbacks(it) }
        abTicker?.let { uiHandler.removeCallbacks(it) }
        uiTicker = object : Runnable {
            override fun run() {
                updatePlaybackUi()
                persistPlaybackPosition()
                uiHandler.postDelayed(this, 700L)
            }
        }
        uiHandler.post(uiTicker!!)
    }

    private fun buildNavSelection(nav: LinearLayout) {
        for (i in 0 until nav.childCount) {
            val view = nav.getChildAt(i) as? Button ?: continue
            val selected = view.text.toString() == section
            view.background = roundedBackground(if (selected) orange else panel, dp(20))
            view.setTextColor(if (selected) Color.BLACK else fg)
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(fg)
        backgroundTintList = android.content.res.ColorStateList.valueOf(panel)
        stateListAnimator = null
        setOnClickListener {
            animate().cancel()
            animate().scaleX(0.96f).scaleY(0.96f).setDuration(65L).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(105L).start()
                action()
            }.start()
        }
    }

    private fun roundedBackground(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (dark && color != Color.TRANSPARENT && color != bg) setStroke(dp(1), Color.argb(52, 157, 104, 255))
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
        loadGenreMap()
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
                        if (mime >= 0) c.getString(mime) ?: "" else "", genreByTrackId[trackId] ?: "Unknown genre"))
                }
            }
        } catch (_: Exception) {
            status.text = "Could not scan storage. Check audio permission."
        }
        renderLibrary()
    }

    private fun loadGenreMap() {
        genreByTrackId.clear()
        try {
            contentResolver.query(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, MediaStore.Audio.Genres.NAME + " COLLATE NOCASE ASC")?.use { genres ->
                val idColumn = genres.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
                val nameColumn = genres.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
                while (genres.moveToNext()) {
                    val genreId = genres.getLong(idColumn)
                    val genreName = genres.getString(nameColumn) ?: "Unknown genre"
                    val membersUri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                    contentResolver.query(membersUri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { members ->
                        val memberId = members.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        while (members.moveToNext()) genreByTrackId[members.getLong(memberId)] = genreName
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun filteredTracks(): List<Track> {
        var result = tracks.filter { t ->
            val matches = query.isBlank() || listOf(titleOf(t), artistOf(t), albumOf(t), t.path, genreOf(t), t.composer, yearOf(t).toString(), t.mimeType, lyricsByTrack[t.id.toString()] ?: "").any { it.contains(query, true) }
            val advancedMatches = (filterArtist.isBlank() || artistOf(t).contains(filterArtist, true)) &&
                (filterYear.isBlank() || yearOf(t).toString().contains(filterYear, true)) &&
                (filterFormat.isBlank() || t.mimeType.substringAfter("/", t.mimeType).contains(filterFormat, true)) &&
                (filterMinSeconds.isBlank() || t.duration >= (filterMinSeconds.toLongOrNull() ?: 0L) * 1000L) &&
                (filterMaxSeconds.isBlank() || t.duration <= (filterMaxSeconds.toLongOrNull() ?: Long.MAX_VALUE) * 1000L)
            !hiddenFolders.contains(t.path) && t.sizeBytes >= minFileSizeKb * 1024L && t.duration >= minDurationSeconds * 1000L && matches && advancedMatches && when (section) {
                "Featured artists" -> featuredArtists(t).isNotEmpty()
                "Rated" -> (ratings[t.id.toString()] ?: 0) > 0
                "Top rated" -> (ratings[t.id.toString()] ?: 0) >= 4
                "Favorites" -> favorites.contains(t.id.toString())
                "Recent" -> recentTrackIds().contains(t.id.toString())
                "Most played" -> getSharedPreferences("laczek_player", MODE_PRIVATE).getInt("plays_" + t.id, 0) > 0
                "Unplayed" -> getSharedPreferences("laczek_player", MODE_PRIVATE).getInt("plays_" + t.id, 0) == 0
                "Duplicates" -> duplicateTrackIds().contains(t.id.toString())
                "Albums", "Artists", "Featured artists", "Folders", "Genres", "Composers", "Years", "Formats", "Playlists",
                "Recently added", "Longest", "Shortest", "Large files" -> true
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
        if (section == "Recently added") return result.sortedByDescending { it.id }
        if (section == "Unplayed") return result.sortedBy { titleOf(it).lowercase(Locale.ROOT) }
        if (section == "Top rated") return result.sortedWith(
            compareByDescending<Track> { ratings[it.id.toString()] ?: 0 }
                .thenBy { titleOf(it).lowercase(Locale.ROOT) }
        )
        if (section == "Longest") return result.sortedByDescending { it.duration }
        if (section == "Shortest") return result.sortedBy { it.duration }
        if (section == "Large files") return result.sortedByDescending { it.sizeBytes }
        if (section == "Playlists" && playlistMap.isNotEmpty()) {
            val names = playlistMap.keys.toList()
            val choices = listOf("All songs") + names
            val selected = currentPlaylist
            if (selected != null) result = result.filter { it.id.toString() in (playlistMap[selected] ?: emptySet()) }
        }
        if (section == "Playlists" && currentPlaylist != null) {
            val order = (playlistMap[currentPlaylist] ?: emptySet()).toList()
            return result.sortedBy { order.indexOf(it.id.toString()).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        }
        return when (sortMode) {
            "Artist" -> result.sortedBy { artistOf(it).lowercase(Locale.ROOT) }
            "Album" -> result.sortedWith(compareBy<Track> { albumOf(it).lowercase(Locale.ROOT) }.thenBy { titleOf(it).lowercase(Locale.ROOT) })
            "Duration" -> result.sortedByDescending { it.duration }
            "Shortest" -> result.sortedBy { it.duration }
            "Year newest" -> result.sortedByDescending { yearOf(it) }
            "Year oldest" -> result.sortedBy { yearOf(it).takeIf { year -> year > 0 } ?: Int.MAX_VALUE }
            "File size" -> result.sortedByDescending { it.sizeBytes }
            "Rating" -> result.sortedByDescending { ratings[it.id.toString()] ?: 0 }
            "Play count" -> result.sortedByDescending { getSharedPreferences("laczek_player", MODE_PRIVATE).getInt("plays_" + it.id, 0) }
            "Format" -> result.sortedBy { it.mimeType.lowercase(Locale.ROOT) }
            "Newest" -> result.sortedByDescending { it.id }
            else -> result.sortedBy { titleOf(it).lowercase(Locale.ROOT) }
        }
    }
    private var currentPlaylist: String? = null
    private var pendingExportPlaylist: String? = null
    private var pendingCopyTrack: Track? = null
    private var pendingArtworkTrack: Track? = null
    private var pendingRenameTrack: Track? = null
    private var pendingRenameName: String? = null
    private var abStartMs: Long? = null
    private var abEndMs: Long? = null
    private var abTicker: Runnable? = null
    private var lastPositionSavedAtMs = 0L

    private fun renderLibrary() {
        if (!::songList.isInitialized) return
        songList.removeAllViews()
        val visible = filteredTracks()
        status.text = when {
            tracks.isEmpty() -> "No local music found. Add audio files and tap ⋮ → Rescan library."
            section == "Albums" -> "${visible.map { albumOf(it) }.distinct().size} albums · ${tracks.size} tracks"
            section == "Artists" -> "${visible.flatMap { listOf(primaryArtist(it)) + featuredArtists(it) }.distinct().size} artists · ${tracks.size} tracks"
            section == "Folders" -> "${visible.map { it.path }.distinct().size} folders"
            section == "Genres" -> "${visible.map { it.genre }.distinct().size} genres"
            section == "Composers" -> "${visible.map { it.composer.ifBlank { "Unknown composer" } }.distinct().size} composers"
            section == "Years" -> "${visible.map { if (it.year > 0) it.year.toString() else "Unknown year" }.distinct().size} release-year groups"
            section == "Formats" -> "${visible.map { it.mimeType.substringAfter("/", it.mimeType).ifBlank { "Unknown format" } }.distinct().size} file formats"
            section == "Rated" -> "${visible.size} rated tracks"
            section == "Top rated" -> "${visible.size} tracks rated 4 or 5 stars"
            section == "Unplayed" -> "${visible.size} tracks never started"
            section == "Recently added" -> "${visible.size} tracks · newest library entries first"
            section == "Longest" -> "${visible.size} tracks · longest first"
            section == "Shortest" -> "${visible.size} tracks · shortest first"
            section == "Large files" -> "${visible.size} tracks · largest files first"
            section == "Favorites" -> "${visible.size} favourite tracks"
            section == "Playlists" -> currentPlaylist ?: "${playlistMap.size} playlists · tap ⋮ to manage"
            else -> "${visible.size} songs on this device · sorted by $sortMode"
        }
        if (selectionMode) {
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(10)) }
            listOf("Queue" to { queueSelectedTracks() }, "♥ Fav" to { favoriteSelectedTracks() }, "Playlist" to { playlistSelectedTracks() }, "Clear" to { selectedTrackIds.clear(); renderLibrary() }).forEach { (label, action) ->
                actions.addView(actionButton(label, action).apply { textSize = 11f; background = roundedBackground(panel, dp(14)); minHeight = dp(42); minimumHeight = dp(42) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
            }
            songList.addView(TextView(this).apply { text = "${selectedTrackIds.size} selected · tap songs to select"; setTextColor(orange); textSize = 12f; setPadding(dp(2), dp(4), 0, dp(7)) })
            songList.addView(actions)
        } else if (controller?.currentMediaItem == null) {
            val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
            val lastId = prefs.getLong("last_track", -1L)
            val lastTrack = tracks.firstOrNull { it.id == lastId }
            if (lastTrack != null) songList.addView(listRow("▶  Resume ${titleOf(lastTrack)}", "Continue from ${formatTime(prefs.getLong("last_position_ms", 0L))}") { resumeLastTrack() })
        }
        if (section in listOf("Albums", "Artists", "Featured artists", "Folders", "Genres", "Composers", "Years", "Formats")) {
            val groups: Map<String, List<Track>> = when (section) {
                "Albums" -> visible.groupBy { albumOf(it) }
                "Artists" -> visible.flatMap { track -> (listOf(primaryArtist(track)) + featuredArtists(track)).distinct().map { it to track } }.groupBy({ it.first }, { it.second })
                "Featured artists" -> visible.flatMap { track -> featuredArtists(track).map { it to track } }.groupBy({ it.first }, { it.second })
                "Genres" -> visible.groupBy { genreOf(it) }
                "Composers" -> visible.groupBy { it.composer.ifBlank { "Unknown composer" } }
                "Years" -> visible.groupBy { if (yearOf(it) > 0) yearOf(it).toString() else "Unknown year" }
                "Formats" -> visible.groupBy { it.mimeType.substringAfter("/", it.mimeType).ifBlank { "Unknown format" }.uppercase(Locale.ROOT) }
                else -> visible.groupBy { it.path }
            }
            groups.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, group) ->
                val row = listRow(name, "${group.size} tracks · ${group.firstOrNull()?.artist ?: ""}") {
                    if (selectionMode) {
                        val ids = group.map { it.id.toString() }
                        if (ids.all { it in selectedTrackIds }) selectedTrackIds.removeAll(ids.toSet()) else selectedTrackIds.addAll(ids)
                        renderLibrary()
                    } else if (section == "Folders") showFolderInfo(name, group) else showTrackGroup(name, group)
                }
                if (section == "Folders") row.setOnLongClickListener { chooseFolderAssets(name); true }
                songList.addView(row)
            }
        } else if (section == "Playlists" && currentPlaylist == null) {
            playlistMap.forEach { (name, ids) ->
                val row = listRow(name, "${ids.size} tracks · long-press for options") {
                    currentPlaylist = name
                    renderLibrary()
                }
                playlistArtworkUris[name]?.let { artUri ->
                    try {
                        val cover = row.getChildAt(0) as? FrameLayout
                        cover?.removeAllViews()
                        cover?.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageURI(Uri.parse(artUri)) }, FrameLayout.LayoutParams(-1, -1))
                    } catch (_: Exception) {}
                }
                row.setOnLongClickListener { managePlaylist(name); true }
                songList.addView(row)
            }
            songList.addView(listRow("+ Create playlist", "Save selected tracks in a local playlist") { createPlaylist() })
        } else {
            visible.forEach { track ->
                songList.addView(trackRow(track))
            }
        }
    }

    private fun trackRow(track: Track): LinearLayout {
        val isCurrent = controller?.currentMediaItem?.mediaId == track.id.toString()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(5), dp(9))
            background = roundedBackground(if (track.id.toString() in selectedTrackIds) Color.argb(if (dark) 65 else 45, Color.red(orange), Color.green(orange), Color.blue(orange)) else if (isCurrent) Color.argb(if (dark) 40 else 24, Color.red(orange), Color.green(orange), Color.blue(orange)) else panel, dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { if (selectionMode) toggleTrackSelection(track) else playTrack(track) }
            setOnLongClickListener { if (selectionMode) toggleTrackSelection(track) else showTrackMenu(track); true }
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(bg, dp(13))
            clipToOutline = true
            contentDescription = "Artwork for ${titleOf(track)}"
            try {
                val uri = artworkUri(track)
                if (uri != null) setImageURI(uri)
            } catch (_: Exception) { setImageDrawable(null) }
        }
        row.addView(art, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.addView(TextView(this).apply {
            text = titleOf(track)
            textSize = 14f
            setTextColor(if (isCurrent) orange else fg)
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        labels.addView(TextView(this).apply {
            text = "${artistOf(track)}  ·  ${albumOf(track)}"
            textSize = 11f
            setTextColor(muted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = formatTime(track.duration)
            textSize = 10f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
        })
        row.addView(TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(muted)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Options for ${titleOf(track)}"
            setOnClickListener { showTrackMenu(track) }
        }, LinearLayout.LayoutParams(dp(34), dp(48)))
        return row
    }

    private fun listRow(title: String, subtitle: String, click: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = roundedBackground(panel, dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        val cover = FrameLayout(this).apply {
            background = roundedBackground(bg, dp(13))
            clipToPadding = false
            clipChildren = false
        }
        cover.addView(TextView(this).apply {
            text = "♫"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(orange)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, FrameLayout.LayoutParams(-1, -1))
        row.addView(cover, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 14.5f
            setTextColor(fg)
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        labels.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(muted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 25f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(22), dp(42)))
        return row
    }

    private fun showTrackGroup(name: String, group: List<Track>) {
        AlertDialog.Builder(this).setTitle(name).setItems(group.map { it.title }.toTypedArray()) { _, which -> playTrack(group[which]) }
            .setNeutralButton("Play all") { _, _ -> playTracks(group) }.show()
    }

    private fun showActionSheet(
        title: String,
        subtitle: String,
        options: List<Pair<String, String>>,
        onSelect: (Int) -> Unit
    ) {
        val dialog = android.app.Dialog(this)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(18))
            background = roundedBackground(panel, dp(26))
        }
        val grabber = View(this).apply {
            background = roundedBackground(if (dark) Color.rgb(100, 102, 112) else Color.rgb(185, 187, 196), dp(3))
        }
        shell.addView(grabber, LinearLayout.LayoutParams(dp(42), dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(17)
        })
        shell.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(fg)
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (subtitle.isNotBlank()) shell.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(12))
        })
        val list = ScrollView(this).apply { isFillViewport = false }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { index, option ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(10), dp(11))
                background = roundedBackground(bg, dp(15))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(index)
                }
            }
            val marker = TextView(this).apply {
                text = "›"
                textSize = 22f
                setTextColor(orange)
                gravity = Gravity.CENTER
                background = roundedBackground(panel, dp(12))
            }
            row.addView(marker, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            labels.addView(TextView(this).apply {
                text = option.first
                textSize = 14f
                setTextColor(fg)
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (option.second.isNotBlank()) labels.addView(TextView(this).apply {
                text = option.second
                textSize = 11f
                setTextColor(muted)
                setPadding(0, dp(3), 0, 0)
                maxLines = 2
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        list.addView(rows)
        shell.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        dialog.setContentView(shell)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1, dp(520))
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes = dialog.window?.attributes?.apply { dimAmount = .58f }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * .78f).toInt())
        dialog.window?.setGravity(Gravity.BOTTOM)
    }

    private fun showTrackMenu(track: Track) {
        val options = arrayOf(
            if (favorites.contains(track.id.toString())) "Remove favourite" else "Add to favourites",
            "Play next", "Add to queue", "Add to playlist", "Edit local tags", "Rate track",
            "Save playback bookmark", "Jump to bookmark", "Share audio", "Copy audio file", "Delete audio file", "Rename file", "Choose local artwork", "Reset local artwork", "Track details"
        )
        showActionSheet(titleOf(track), "Track options · local library", options.map { it to "" }) { which ->
            when (which) {
                0 -> { if (!favorites.add(track.id.toString())) favorites.remove(track.id.toString()); saveLocalSettings(); renderLibrary() }
                1 -> controller?.let { val insertAt = (it.currentMediaItemIndex + 1).coerceIn(0, it.mediaItemCount); it.addMediaItem(insertAt, mediaItem(track)); Toast.makeText(this, "Queued next", Toast.LENGTH_SHORT).show() }
                2 -> { controller?.addMediaItem(mediaItem(track)); Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show() }
                3 -> addTrackToPlaylist(track)
                4 -> editLocalTags(track)
                5 -> rateTrack(track)
                6 -> { val p = controller; bookmarks[track.id.toString()] = if (p?.currentMediaItem?.mediaId == track.id.toString()) p.currentPosition else 0L; saveLocalSettings(); Toast.makeText(this, "Bookmark saved locally", Toast.LENGTH_SHORT).show() }
                7 -> { val position = bookmarks[track.id.toString()]; if (position != null) { playTrack(track); controller?.seekTo(position) } else Toast.makeText(this, "No bookmark saved for this track", Toast.LENGTH_SHORT).show() }
                8 -> shareTrack(track)
                9 -> { pendingCopyTrack = track; val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; putExtra(Intent.EXTRA_TITLE, fileNameOf(track)) }; startActivityForResult(intent, 208) }
                10 -> deleteTrack(track)
                11 -> renameTrack(track)
                12 -> chooseArtwork(track)
                13 -> { customArtworkUris.remove(artworkKey(track)); saveLocalSettings(); updatePlaybackUi(); Toast.makeText(this, "Custom artwork removed", Toast.LENGTH_SHORT).show() }
                14 -> AlertDialog.Builder(this).setMessage("Title: ${titleOf(track)}\nArtist: ${artistOf(track)}\nAlbum: ${albumOf(track)}\nGenre: ${track.genre}\nFolder: ${track.path}\nComposer: ${track.composer.ifBlank { "Unknown" }}\nYear: ${if (track.year > 0) track.year else "Unknown"}\nFormat: ${track.mimeType.ifBlank { "Unknown" }}\nFile size: ${formatBytes(track.sizeBytes)}\nDuration: ${formatTime(track.duration)}\nRating: ${ratings[track.id.toString()] ?: 0}/5\nBookmark: ${bookmarks[track.id.toString()]?.let { formatTime(it) } ?: "Not set"}\nURI: ${track.uri}").setPositiveButton("Close", null).show()
            }
        }
    }

    private fun editLocalTags(track: Track) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        val title = EditText(this).apply { hint = "Song title"; setText(titleOf(track)); setTextColor(fg) }
        val artist = EditText(this).apply { hint = "Artist"; setText(artistOf(track)); setTextColor(fg) }
        val album = EditText(this).apply { hint = "Album"; setText(albumOf(track)); setTextColor(fg) }
        val genre = EditText(this).apply { hint = "Genre"; setText(genreOf(track)); setTextColor(fg) }
        val year = EditText(this).apply { hint = "Year"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(if (yearOf(track) > 0) yearOf(track).toString() else ""); setTextColor(fg) }
        box.addView(title); box.addView(artist); box.addView(album); box.addView(genre); box.addView(year)
        AlertDialog.Builder(this).setTitle("Edit tags in LaCzek Player").setMessage("These overrides are stored locally in the app; the original audio file is not modified.")
            .setView(box).setPositiveButton("Save") { _, _ ->
                val id = track.id.toString()
                customTitles[id] = title.text.toString().trim().ifEmpty { track.title }
                customArtists[id] = artist.text.toString().trim().ifEmpty { track.artist }
                customAlbums[id] = album.text.toString().trim().ifEmpty { track.album }
                customGenres[id] = genre.text.toString().trim().ifEmpty { track.genre }
                customYears[id] = year.text.toString().trim().takeIf { it.isNotBlank() && it.toIntOrNull() != null } ?: "${track.year}"
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

    private fun artworkKey(t: Track): String = if (t.albumId > 0) "album:" + t.albumId else "track:" + t.id
    private fun artworkUri(t: Track): Uri? = customArtworkUris[artworkKey(t)]?.let { Uri.parse(it) }
        ?: folderDocument(t.path, "cover.jpg")
        ?: folderDocument(t.path, "cover.png")
        ?: if (t.albumId > 0) Uri.parse("content://media/external/audio/albumart/" + t.albumId) else null

    private fun chooseFolderAssets(path: String) {
        pendingFolderPath = path
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 212)
    }

    private fun folderDocument(folderPath: String, fileName: String): Uri? {
        val cacheKey = folderPath + "|" + fileName.lowercase(Locale.ROOT)
        if (folderDocumentCache.containsKey(cacheKey)) return folderDocumentCache[cacheKey]
        val tree = folderTreeUris[folderPath]?.let { Uri.parse(it) } ?: return null
        val found = try {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            var match: Uri? = null
            contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameCol).equals(fileName, true)) {
                        match = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idCol))
                        break
                    }
                }
            }
            match
        } catch (_: Exception) { null }
        folderDocumentCache[cacheKey] = found
        return found
    }

    private fun showFolderInfo(path: String, group: List<Track>) {
        val infoUri = folderDocument(path, ".info.txt")
        val info = if (infoUri != null) try { contentResolver.openInputStream(infoUri)?.bufferedReader()?.use { it.readText().take(6000) } ?: "" } catch (_: Exception) { "" } else ""
        val cover = folderDocument(path, "cover.jpg") ?: folderDocument(path, "cover.png")
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        if (cover != null) body.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageURI(cover) }, LinearLayout.LayoutParams(dp(112), dp(112)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
        body.addView(TextView(this).apply { text = info.ifBlank { "No .info.txt found. Long-press this folder to grant access to its files." }; setTextColor(fg); textSize = 14f })
        AlertDialog.Builder(this).setTitle(path.trimEnd('/').substringAfterLast('/').ifBlank { path }).setView(body)
            .setPositiveButton("Play folder") { _, _ -> playTracks(group) }
            .setNeutralButton(if (folderTreeUris.containsKey(path)) "Change folder access" else "Use folder artwork/info") { _, _ -> chooseFolderAssets(path) }
            .setNegativeButton("Close", null).show()
    }

    private fun applyMiniPartyMode() {
        val view = miniPlayerPanel ?: return
        partyAnimator?.cancel(); partyAnimator = null
        val enabled = getSharedPreferences("laczek_player", MODE_PRIVATE).getBoolean("mini_party_mode", false)
        if (!enabled) { view.background = roundedBackground(panel, dp(20)); return }
        val outline = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(20).toFloat(); setColor(panel); setStroke(dp(2), orange) }
        view.background = outline
        partyAnimator = ValueAnimator.ofObject(ArgbEvaluator(), orange, Color.TRANSPARENT).apply {
            duration = 1100L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            addUpdateListener { outline.setStroke(dp(2), it.animatedValue as Int) }; start()
        }
    }

    private fun chooseArtwork(track: Track) {
        pendingArtworkTrack = track
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
        startActivityForResult(intent, 209)
    }

    private fun showArtworkPreview() {
        val item = controller?.currentMediaItem
        val track = tracks.firstOrNull { it.id.toString() == item?.mediaId }
        val uri = track?.let { artworkUri(it) }
        if (uri == null) {
            Toast.makeText(this, "No local artwork available for this track", Toast.LENGTH_SHORT).show()
            return
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Full-size album artwork"
            try { setImageURI(uri) } catch (_: Exception) {}
        }
        AlertDialog.Builder(this).setTitle(track?.let { titleOf(it) } ?: "Album artwork")
            .setView(preview)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun mediaItem(t: Track) = MediaItem.Builder().setUri(t.uri).setMediaId(t.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(titleOf(t)).setArtist(artistOf(t)).setAlbumTitle(albumOf(t)).setArtworkUri(artworkUri(t)).build()).build()

    private fun connectPlayback() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controller?.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        abStartMs = null; abEndMs = null; abTicker?.let { uiHandler.removeCallbacks(it) }; abTicker = null
                        val id = mediaItem?.mediaId?.toLongOrNull()
                        val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
                        if (id != null && prefs.getLong("last_track", -1L) != id) {
                            tracks.firstOrNull { it.id == id }?.let { recordTrackStarted(it) }
                        }
                        mediaItem?.mediaId?.let { id -> lyricText = lyricsByTrack[id] ?: "" }
                        applySavedSpeed(mediaItem)
                        mediaItem?.mediaId?.let { id -> tracks.firstOrNull { it.id.toString() == id }?.let { updateArtworkAccent(it) } }
                        updatePlaybackUi()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlaybackUi()
                    override fun onPlaybackStateChanged(playbackState: Int) = updatePlaybackUi()
                })
                updatePlaybackUi()
            } catch (_: ExecutionException) {
                Toast.makeText(this, "Playback service could not start", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }, mainExecutor)
    }

    private fun playTrack(track: Track, startPositionMs: Long = 0L) {
        val p = controller ?: run { Toast.makeText(this, "Player is still starting. Try again.", Toast.LENGTH_SHORT).show(); return }
        val ordered = filteredTracks()
        val list = (listOf(track) + ordered.filter { it.id != track.id }).distinctBy { it.id }
        p.setMediaItems(list.map { mediaItem(it) }, 0, startPositionMs.coerceAtLeast(0L))
        p.prepare()
        p.play()
        recordTrackStarted(track)
        updatePlaybackUi()
        showFullPlayer()
    }

    private fun recordTrackStarted(track: Track) {
        val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
        val recent = recentTrackIds().toMutableList()
        recent.remove(track.id.toString())
        recent.add(0, track.id.toString())
        prefs.edit()
            .putLong("last_track", track.id)
            .putLong("last_position_ms", 0L)
            .putInt("plays_" + track.id, prefs.getInt("plays_" + track.id, 0) + 1)
            .putString("recent_order", JSONArray(recent.take(200)).toString())
            .apply()
    }

    private fun playTracks(items: List<Track>) {
        if (items.isEmpty()) return
        controller?.setMediaItems(items.map { mediaItem(it) }, 0, 0L)
        controller?.prepare()
        controller?.play()
        recordTrackStarted(items.first())
        showFullPlayer()
    }
    private fun runFadedAction(fadeInAfter: Boolean = true, action: () -> Unit) {
        val p = controller ?: return
        val handler = Handler(Looper.getMainLooper())
        val originalVolume = p.volume.coerceIn(0f, 1f)
        var step = 0
        val fadeOut = object : Runnable {
            override fun run() {
                step++
                p.volume = originalVolume * (1f - step / 4f).coerceAtLeast(0f)
                if (step < 4) handler.postDelayed(this, 35L) else {
                    action()
                    if (!fadeInAfter) { p.volume = originalVolume; return }
                    var up = 0
                    val fadeIn = object : Runnable {
                        override fun run() {
                            up++
                            p.volume = originalVolume * (up / 4f).coerceAtMost(1f)
                            if (up < 4) handler.postDelayed(this, 35L)
                        }
                    }
                    fadeIn.run()
                }
            }
        }
        fadeOut.run()
    }

    private fun togglePlayback() {
        val p = controller ?: return
        if (p.isPlaying) runFadedAction(false) { p.pause() }
        else if (p.mediaItemCount > 0) runFadedAction(true) { p.play() }
        else tracks.firstOrNull()?.let { playTrack(it) }
        updatePlaybackUi()
    }
    private fun skip(direction: Int) {
        val p = controller ?: return
        runFadedAction(true) {
            if (direction < 0) {
                if (p.currentPosition > 3000) p.seekTo(0) else p.seekToPreviousMediaItem()
            } else p.seekToNextMediaItem()
        }
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
    private fun persistPlaybackPosition(force: Boolean = false) {
        val p = controller ?: return
        val item = p.currentMediaItem ?: return
        val id = item.mediaId.toLongOrNull() ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPositionSavedAtMs < 5_000L) return
        lastPositionSavedAtMs = now
        getSharedPreferences("laczek_player", MODE_PRIVATE).edit()
            .putLong("last_track", id)
            .putLong("last_position_ms", p.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    private fun updatePlaybackUi() {
        val p = controller
        val item = p?.currentMediaItem
        val hasTrack = item != null
        miniPlayerPanel?.visibility = if (hasTrack && !fullPlayerOpen) View.VISIBLE else View.GONE
        if (::miniTrackLabel.isInitialized) {
            miniTrackLabel.text = item?.mediaMetadata?.title?.toString() ?: ""
            miniArtistLabel.text = item?.mediaMetadata?.artist?.toString() ?: ""
            miniPlayButton.text = if (p?.isPlaying == true) "Ⅱ" else "▶"
            try {
                val track = tracks.firstOrNull { it.id.toString() == item?.mediaId }
                val art = track?.let { artworkUri(it) }
                if (art != null) miniArtwork.setImageURI(art) else miniArtwork.setImageDrawable(null)
            } catch (_: Exception) { miniArtwork.setImageDrawable(null) }
        }
        val currentId = item?.mediaId ?: ""
        if (currentId.isNotBlank() && currentId != lastAccentTrackId) tracks.firstOrNull { it.id.toString() == currentId }?.let { updateArtworkAccent(it) }
        val widgetTitle = item?.mediaMetadata?.title?.toString() ?: "LaCzek Player"
        val widgetArtist = item?.mediaMetadata?.artist?.toString() ?: "Offline music"
        val widgetPlaying = p?.isPlaying == true
        val widgetFavorite = currentId.isNotBlank() && favorites.contains(currentId)
        val widgetPrefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
        if (widgetPrefs.getString("widget_title", "") != widgetTitle || widgetPrefs.getString("widget_artist", "") != widgetArtist || widgetPrefs.getBoolean("widget_playing", false) != widgetPlaying || widgetPrefs.getString("widget_media_id", "") != currentId || widgetPrefs.getBoolean("widget_favorite", false) != widgetFavorite) {
            widgetPrefs.edit().putString("widget_title", widgetTitle).putString("widget_artist", widgetArtist).putBoolean("widget_playing", widgetPlaying).putString("widget_media_id", currentId).putBoolean("widget_favorite", widgetFavorite).apply()
            PlaybackWidgetProvider.refresh(this)
        }
        if (!fullPlayerOpen || !::nowPlaying.isInitialized || !::playButton.isInitialized ||
            !::artworkView.isInitialized || !::seek.isInitialized || !::timeText.isInitialized) return
        nowPlaying.text = item?.mediaMetadata?.title?.toString() ?: "Unknown title"
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

    private fun toggleABRepeat() {
        val p = controller
        if (p == null || p.mediaItemCount == 0) { Toast.makeText(this, "Play a track first", Toast.LENGTH_SHORT).show(); return }
        when {
            abStartMs == null -> { abStartMs = p.currentPosition; abEndMs = null; startABTicker(); Toast.makeText(this, "A point set. Tap A-B again at the end point.", Toast.LENGTH_SHORT).show() }
            abEndMs == null -> {
                val end = p.currentPosition
                if (end <= (abStartMs ?: 0L) + 500L) Toast.makeText(this, "End point must be after start point", Toast.LENGTH_SHORT).show()
                else { abEndMs = end; Toast.makeText(this, "A-B repeat active", Toast.LENGTH_SHORT).show() }
            }
            else -> { abStartMs = null; abEndMs = null; abTicker?.let { uiHandler.removeCallbacks(it) }; abTicker = null; Toast.makeText(this, "A-B repeat off", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun startABTicker() {
        if (abTicker != null) return
        abTicker = object : Runnable {
            override fun run() {
                val p = controller; val start = abStartMs; val end = abEndMs
                if (p != null && start != null && end != null && p.currentPosition >= end) p.seekTo(start)
                if (abStartMs != null) uiHandler.postDelayed(this, 100L) else abTicker = null
            }
        }
        uiHandler.post(abTicker!!)
    }

    private fun setPlaybackSpeed() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val labels = speeds.map { it.toString() + "×" }.toTypedArray()
        val id = controller?.currentMediaItem?.mediaId
        val current = id?.let { trackSpeeds[it] } ?: (controller?.playbackParameters?.speed ?: 1f)
        val selected = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - current) } ?: 2
        AlertDialog.Builder(this).setTitle("Playback speed · saved per track").setSingleChoiceItems(labels, selected) { dialog, which ->
            controller?.setPlaybackSpeed(speeds[which])
            if (id != null) { trackSpeeds[id] = speeds[which]; saveLocalSettings() }
            dialog.dismiss()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun applySavedSpeed(mediaItem: MediaItem?) {
        val id = mediaItem?.mediaId ?: return
        controller?.setPlaybackSpeed(trackSpeeds[id] ?: 1.0f)
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
        AlertDialog.Builder(this).setTitle("Queue · " + p.mediaItemCount + " tracks")
            .setItems(labels) { _, which -> manageQueueItem(which) }
            .setNeutralButton("Clear queue") { _, _ -> p.clearMediaItems() }
            .setPositiveButton("Done", null).show()
    }

    private fun manageQueueItem(index: Int) {
        val p = controller ?: return
        if (index !in 0 until p.mediaItemCount) return
        val item = p.getMediaItemAt(index)
        val title = item.mediaMetadata.title?.toString() ?: "Queued track"
        val actions = arrayOf(
            "Play now",
            "Move up",
            "Move down",
            "Remove from queue"
        )
        AlertDialog.Builder(this).setTitle(title).setItems(actions) { _, action ->
            val player = controller ?: return@setItems
            if (index !in 0 until player.mediaItemCount) return@setItems
            when (action) {
                0 -> { player.seekToDefaultPosition(index); player.play() }
                1 -> if (index > 0) player.moveMediaItem(index, index - 1)
                     else Toast.makeText(this, "This track is already first", Toast.LENGTH_SHORT).show()
                2 -> if (index < player.mediaItemCount - 1) player.moveMediaItem(index, index + 1)
                     else Toast.makeText(this, "This track is already last", Toast.LENGTH_SHORT).show()
                3 -> {
                    player.removeMediaItem(index)
                    Toast.makeText(this, "Removed from queue", Toast.LENGTH_SHORT).show()
                }
            }
            if (action in 1..3 && player.mediaItemCount > 0) showQueue()
        }.setNegativeButton("Back") { _, _ -> showQueue() }.show()
    }

    private fun managePlaylist(name: String) {
        val options = arrayOf("Play playlist", "Reorder tracks", "Set playlist artwork", "Rename playlist", "Export M3U", "Delete playlist")
        AlertDialog.Builder(this).setTitle(name).setItems(options) { _, which ->
            when (which) {
                0 -> {
                    val ids = playlistMap[name] ?: emptySet()
                    val selected = ids.mapNotNull { id -> tracks.firstOrNull { it.id.toString() == id } }
                    if (selected.isEmpty()) Toast.makeText(this, "This playlist has no matching local tracks", Toast.LENGTH_SHORT).show() else playTracks(selected)
                }
                1 -> reorderPlaylist(name)
                2 -> { playlistArtworkTarget = name; val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }; startActivityForResult(intent, 213) }
                3 -> {
                    val input = EditText(this).apply { setText(name); setTextColor(fg) }
                    AlertDialog.Builder(this).setTitle("Rename playlist").setView(input).setPositiveButton("Save") { _, _ ->
                        val newName = input.text.toString().trim()
                        if (newName.isNotEmpty() && newName != name) {
                            playlistMap[newName] = playlistMap.remove(name) ?: mutableSetOf()
                            playlistArtworkUris.remove(name)?.let { playlistArtworkUris[newName] = it }
                            if (currentPlaylist == name) currentPlaylist = newName
                            saveLocalSettings(); renderLibrary()
                        }
                    }.setNegativeButton("Cancel", null).show()
                }
                4 -> { pendingExportPlaylist = name; val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/x-mpegurl"; putExtra(Intent.EXTRA_TITLE, name.replace(Regex("[^A-Za-z0-9._ -]"), "_") + ".m3u") }; startActivityForResult(intent, 205) }
                5 -> AlertDialog.Builder(this).setMessage("Delete playlist $name? The music files will not be deleted.").setPositiveButton("Delete") { _, _ -> playlistMap.remove(name); playlistArtworkUris.remove(name); if (currentPlaylist == name) currentPlaylist = null; saveLocalSettings(); renderLibrary() }.setNegativeButton("Cancel", null).show()
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
        val crossfade = getSharedPreferences("laczek_player", MODE_PRIVATE).getBoolean("crossfade_enabled", false)
        val artworkColors = getSharedPreferences("laczek_player", MODE_PRIVATE).getBoolean("artwork_colors", true)
        val skipSilence = getSharedPreferences("laczek_player", MODE_PRIVATE).getBoolean("skip_silence_enabled", false)
        val partyMode = getSharedPreferences("laczek_player", MODE_PRIVATE).getBoolean("mini_party_mode", false)
        val options = arrayOf("Rescan library", "Sort: $sortMode", "Create playlist", "Import local audio", "Import M3U playlist", "Sleep timer", "Theme: ${if (dark) "Dark" else "Light"}", "Export settings backup", "Restore settings backup", "Clear listening history", "Playlist overview", "Accent colour", "Hidden folders", "Save queue for later", "Restore saved queue", "Listening statistics", "Resume last track", "Export library index (CSV)", "Advanced search filters", "Crossfade: ${if (crossfade) "On" else "Off"}", "Artwork-based colours: ${if (artworkColors) "On" else "Off"}", "Toggle multi-select", "Minimum file size/duration filters", "Skip silence preference", "Mini-player party mode")
        showActionSheet("LaCzek tools", "Offline music library and player settings", options.map { it to "" }) { which ->
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
                9 -> clearListeningHistory()
                10 -> showPlaylistOverview()
                11 -> chooseAccentColour()
                12 -> manageHiddenFolders()
                13 -> saveQueueForLater()
                14 -> restoreSavedQueue()
                15 -> showListeningStats()
                16 -> resumeLastTrack()
                17 -> exportLibraryIndex()
                18 -> showAdvancedSearch()
                19 -> { val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE); prefs.edit().putBoolean("crossfade_enabled", !crossfade).apply(); Toast.makeText(this, "Crossfade ${if (!crossfade) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show() }
                20 -> { val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE); prefs.edit().putBoolean("artwork_colors", !artworkColors).apply(); lastAccentTrackId = ""; if (artworkColors) accentColor = Color.rgb(255, 138, 61) else controller?.currentMediaItem?.mediaId?.let { id -> tracks.firstOrNull { it.id.toString() == id }?.let { updateArtworkAccent(it) } }; rebuildUi() }
                21 -> { selectionMode = !selectionMode; if (selectionMode) section = "Songs" else selectedTrackIds.clear(); rebuildUi() }
                22 -> chooseLibraryLimits()
                23 -> { getSharedPreferences("laczek_player", MODE_PRIVATE).edit().putBoolean("skip_silence_enabled", !skipSilence).apply(); Toast.makeText(this, "Skip silence "+(if (!skipSilence) "enabled" else "disabled"), Toast.LENGTH_SHORT).show() }
                24 -> { getSharedPreferences("laczek_player", MODE_PRIVATE).edit().putBoolean("mini_party_mode", !partyMode).apply(); applyMiniPartyMode() }
            }
        }
    }

    private fun clearListeningHistory() {
        AlertDialog.Builder(this)
            .setTitle("Clear listening history?")
            .setMessage("This clears recent tracks, play counts, and total listening time. It will not delete audio files, playlists, ratings, or bookmarks.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear history") { _, _ ->
                val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
                val editor = prefs.edit().remove("last_track").remove("last_position_ms").remove("recent_order").putLong("listening_ms", 0L)
                prefs.all.keys.filter { it.startsWith("plays_") }.forEach { editor.remove(it) }
                editor.apply()
                renderLibrary()
                Toast.makeText(this, "Listening history cleared", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun resumeLastTrack() {
        val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
        val lastId = prefs.getLong("last_track", -1L)
        val track = tracks.firstOrNull { it.id == lastId }
        if (track == null) {
            Toast.makeText(this, "No saved last track. Play a song first.", Toast.LENGTH_SHORT).show()
            return
        }
        val savedPosition = prefs.getLong("last_position_ms", 0L).coerceAtLeast(0L)
        val safePosition = if (track.duration > 1_000L) savedPosition.coerceAtMost(track.duration - 1_000L) else 0L
        playTrack(track, safePosition)
    }

    private fun exportLibraryIndex() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Scan the library first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "laczek-player-library.csv")
        }
        startActivityForResult(intent, 211)
    }

    private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun showListeningStats() {
        val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
        val totalPlays = prefs.all.filterKeys { it.startsWith("plays_") }.values.sumOf { (it as? Int ?: 0).toLong() }
        val totalListeningMs = prefs.getLong("listening_ms", 0L)
        val libraryDuration = tracks.sumOf { it.duration }
        val favouritesCount = favorites.size
        val playlistCount = playlistMap.size
        val ratedCount = ratings.size
        val bookmarkCount = bookmarks.size
        val message = "Listening time: ${formatLongDuration(totalListeningMs)}\nTracks scanned: ${tracks.size}\nLibrary duration: ${formatLongDuration(libraryDuration)}\nTracked starts: $totalPlays\nFavourites: $favouritesCount\nPlaylists: $playlistCount\nRated tracks: $ratedCount\nBookmarks: $bookmarkCount"
        AlertDialog.Builder(this).setTitle("Offline listening statistics").setMessage(message).setPositiveButton("Close", null).show()
    }

    private fun formatLongDuration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1000L
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        return "${hours}h ${minutes}m"
    }

    private fun chooseAccentColour() {
        val names = arrayOf("LaCzek orange", "Cyan", "Purple", "Green", "Pink", "Blue", "White")
        val colours = intArrayOf(Color.rgb(255, 138, 61), Color.rgb(0, 210, 220), Color.rgb(174, 106, 255), Color.rgb(68, 205, 120), Color.rgb(255, 90, 160), Color.rgb(75, 145, 255), Color.WHITE)
        val selected = colours.indexOf(accentColor).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Accent colour").setSingleChoiceItems(names, selected) { dialog, which ->
            accentColor = colours[which]; saveLocalSettings(); rebuildUi(); dialog.dismiss()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun manageHiddenFolders() {
        val folders = tracks.map { it.path }.distinct().sorted().toTypedArray()
        if (folders.isEmpty()) { Toast.makeText(this, "Scan music first", Toast.LENGTH_SHORT).show(); return }
        val checked = BooleanArray(folders.size) { hiddenFolders.contains(folders[it]) }
        AlertDialog.Builder(this).setTitle("Hide folders from the library")
            .setMultiChoiceItems(folders, checked) { _, which, isChecked -> if (isChecked) hiddenFolders.add(folders[which]) else hiddenFolders.remove(folders[which]) }
            .setPositiveButton("Save") { _, _ -> saveLocalSettings(); renderLibrary() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveQueueForLater() {
        val p = controller
        if (p == null || p.mediaItemCount == 0) { Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show(); return }
        val arr = JSONArray()
        for (i in 0 until p.mediaItemCount) arr.put(p.getMediaItemAt(i).mediaId)
        getSharedPreferences("laczek_player", MODE_PRIVATE).edit().putString("saved_queue", arr.toString())
            .putInt("saved_queue_index", p.currentMediaItemIndex).putLong("saved_queue_position", p.currentPosition).apply()
        Toast.makeText(this, "Queue saved locally", Toast.LENGTH_SHORT).show()
    }

    private fun restoreSavedQueue() {
        try {
            val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
            val arr = JSONArray(prefs.getString("saved_queue", "[]"))
            val saved = (0 until arr.length()).map { arr.optString(it) }
            val items = saved.mapNotNull { id -> tracks.firstOrNull { it.id.toString() == id } }
            if (items.isEmpty()) { Toast.makeText(this, "No saved local queue found", Toast.LENGTH_SHORT).show(); return }
            val start = prefs.getInt("saved_queue_index", 0).coerceIn(0, items.lastIndex)
            val position = prefs.getLong("saved_queue_position", 0L).coerceAtLeast(0L)
            controller?.setMediaItems(items.map { mediaItem(it) }, start, position)
            controller?.prepare(); controller?.play()
            Toast.makeText(this, "Saved queue restored", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { Toast.makeText(this, "Could not restore the saved queue", Toast.LENGTH_SHORT).show() }
    }

    private fun primaryArtist(track: Track): String {
        val artist = artistOf(track)
        val lower = artist.lowercase(Locale.ROOT)
        val markers = listOf(" feat. ", " feat ", " ft. ", " ft ", " featuring ")
        val index = markers.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return artist
        return artist.substring(0, index).trim().ifBlank { artist }
    }

    private fun featuredArtists(track: Track): List<String> {
        val artist = artistOf(track)
        val lower = artist.lowercase(Locale.ROOT)
        val marker = listOf(" feat. ", " feat ", " ft. ", " ft ", " featuring ").firstOrNull { lower.contains(it) } ?: return emptyList()
        val tail = artist.substring(lower.indexOf(marker) + marker.length).substringBefore(")").substringBefore("]")
        return tail.split(",", ";", "&", "+", " and ").map { it.trim().trimEnd('.', ',') }.filter { it.isNotBlank() }
    }

    private fun chooseLibraryLimits() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        val size = EditText(this).apply { hint = "Minimum file size in KB (0 = off)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(minFileSizeKb.toString()); setTextColor(fg) }
        val duration = EditText(this).apply { hint = "Minimum duration in seconds (0 = off)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(minDurationSeconds.toString()); setTextColor(fg) }
        box.addView(size); box.addView(duration)
        AlertDialog.Builder(this).setTitle("Library scan filters").setMessage("Tracks below either limit are hidden. Files are never deleted.")
            .setView(box).setPositiveButton("Apply") { _, _ ->
                minFileSizeKb = (size.text.toString().toLongOrNull() ?: 0L).coerceAtLeast(0L)
                minDurationSeconds = (duration.text.toString().toLongOrNull() ?: 0L).coerceAtLeast(0L)
                saveLocalSettings(); renderLibrary()
            }.setNeutralButton("Reset") { _, _ -> minFileSizeKb = 0L; minDurationSeconds = 0L; saveLocalSettings(); renderLibrary() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseSort() {
        val modes = arrayOf("Title", "Artist", "Album", "Duration", "Shortest", "Newest", "Year newest", "Year oldest", "File size", "Rating", "Play count", "Format")
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

    private fun showLyricsEditor() {
        val id = controller?.currentMediaItem?.mediaId
        if (id == null) { Toast.makeText(this, "Play a track before editing its lyrics", Toast.LENGTH_SHORT).show(); return }
        val editor = EditText(this).apply {
            setText(lyricsByTrack[id] ?: lyricText); setTextColor(fg); setHintTextColor(muted)
            hint = "[00:12.34] First line of lyrics"; minLines = 8; gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = roundedBackground(panel, dp(14))
        }
        AlertDialog.Builder(this).setTitle("Local synced lyrics (.LRC)")
            .setMessage("Use timestamps such as [00:12.34]. Saved only on this phone.").setView(editor)
            .setPositiveButton("Save lyrics") { _, _ ->
                val text = editor.text.toString().take(100_000); lyricsByTrack[id] = text; lyricText = text; saveLocalSettings()
                Toast.makeText(this, "Lyrics saved for this track", Toast.LENGTH_SHORT).show(); showLyrics()
            }
            .setNeutralButton("Import LRC file") { _, _ -> chooseLyrics() }
            .setNegativeButton("Cancel", null).show()
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
        val id = controller?.currentMediaItem?.mediaId
        if (id != null && lyricsByTrack.containsKey(id)) lyricText = lyricsByTrack[id] ?: ""
        if (lyricText.isBlank()) { showLyricsEditor(); return }
        val lines = parseLyrics()
        val scroll = ScrollView(this)
        val textView = TextView(this).apply {
            setTextColor(fg); textSize = 17f
            setPadding(dp(16), dp(12), dp(16), dp(18))
            setLineSpacing(dp(8).toFloat(), 1f)
        }
        scroll.addView(textView)
        val dialog = AlertDialog.Builder(this).setTitle("Synchronized local lyrics")
            .setView(scroll).setPositiveButton("Edit lyrics") { _, _ -> showLyricsEditor() }
            .setNeutralButton("Load LRC file") { _, _ -> chooseLyrics() }
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
            bassBoost?.release()
            virtualizer?.release()
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
            bassBoost = try { BassBoost(0, sessionId).apply { enabled = true } } catch (_: Exception) { null }
            virtualizer = try { Virtualizer(0, sessionId).apply { enabled = true } } catch (_: Exception) { null }
            val eq = equalizer!!
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
            val presetNames = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }.toTypedArray()
            if (presetNames.isNotEmpty()) {
                layout.addView(TextView(this).apply { text = "EQ preset"; setTextColor(fg) })
                val spinner = Spinner(this)
                spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presetNames)
                layout.addView(spinner)
                spinner.setSelection(eq.currentPreset.toInt().coerceIn(0, presetNames.lastIndex))
                spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        try { eq.usePreset(position.toShort()) } catch (_: Exception) {}
                    }
                }
            }
            val bands = (0 until eq.numberOfBands).map { it.toShort() }
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
            bassBoost?.let { effect ->
                layout.addView(TextView(this).apply { text = "Bass boost"; setTextColor(fg) })
                layout.addView(SeekBar(this).apply {
                    max = 1000
                    progress = effect.roundedStrength.toInt()
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) { if (user) try { effect.setStrength(v.toShort()) } catch (_: Exception) {} }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                })
            }
            virtualizer?.let { effect ->
                layout.addView(TextView(this).apply { text = "Virtualizer"; setTextColor(fg) })
                layout.addView(SeekBar(this).apply {
                    max = 1000
                    progress = effect.roundedStrength.toInt()
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) { if (user) try { effect.setStrength(v.toShort()) } catch (_: Exception) {} }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                })
            }
            AlertDialog.Builder(this).setTitle("Audio effects").setView(ScrollView(this).apply { addView(layout) })
                .setNeutralButton("Disable all") { _, _ -> eq.enabled = false; bassBoost?.enabled = false; virtualizer?.enabled = false }
                .setPositiveButton("Done", null).show()
        } catch (_: Exception) { Toast.makeText(this, "Audio effects are not supported on this device", Toast.LENGTH_LONG).show() }
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
                        controller?.currentMediaItem?.mediaId?.let { id -> lyricsByTrack[id] = lyricText; saveLocalSettings() }
                        showLyrics()
                    } catch (_: Exception) { Toast.makeText(this, "Could not read this lyrics file", Toast.LENGTH_SHORT).show() }
                }
            }
            203 -> {
                data.data?.let { uri ->
                    try {
                        val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
                        val playCounts = JSONObject()
                        prefs.all.filterKeys { it.startsWith("plays_") }.forEach { (key, value) ->
                            if (value is Int) playCounts.put(key, value)
                        }
                        val backup = JSONObject()
                            .put("format_version", 2)
                            .put("favorites", JSONArray(favorites.toList()))
                            .put("dark", dark)
                            .put("accent", accentColor)
                            .put("sort", sortMode)
                            .put("hidden_folders", JSONArray(hiddenFolders.toList()))
                            .put("playlists", JSONArray(prefs.getString("playlists", "[]")))
                            .put("custom_titles", JSONObject(customTitles as Map<*, *>))
                            .put("custom_artists", JSONObject(customArtists as Map<*, *>))
                            .put("custom_albums", JSONObject(customAlbums as Map<*, *>))
                            .put("custom_genres", JSONObject(customGenres as Map<*, *>))
                            .put("custom_years", JSONObject(customYears as Map<*, *>))
                            .put("lyrics_by_track", JSONObject(lyricsByTrack as Map<*, *>))
                            .put("track_speeds", JSONObject(trackSpeeds.mapValues { it.value.toDouble() } as Map<*, *>))
                            .put("custom_artwork_uris", JSONObject(customArtworkUris as Map<*, *>))
                            .put("playlist_artwork_uris", JSONObject(playlistArtworkUris as Map<*, *>))
                            .put("folder_tree_uris", JSONObject(folderTreeUris as Map<*, *>))
                            .put("min_file_size_kb", minFileSizeKb)
                            .put("min_duration_seconds", minDurationSeconds)
                            .put("bookmarks", JSONObject(bookmarks as Map<*, *>))
                            .put("ratings", JSONObject(ratings as Map<*, *>))
                            .put("recent_order", JSONArray(recentTrackIds()))
                            .put("play_counts", playCounts)
                            .put("listening_ms", prefs.getLong("listening_ms", 0L))
                            .put("saved_queue", JSONArray(prefs.getString("saved_queue", "[]")))
                            .put("saved_queue_index", prefs.getInt("saved_queue_index", 0))
                            .put("saved_queue_position", prefs.getLong("saved_queue_position", 0L))
                            .put("last_track", prefs.getLong("last_track", -1L))
                            .put("last_position_ms", prefs.getLong("last_position_ms", 0L))
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
                        accentColor = backup.optInt("accent", Color.rgb(255, 138, 61))
                        sortMode = backup.optString("sort", "Title")
                        hiddenFolders.clear()
                        val hidden = backup.optJSONArray("hidden_folders") ?: JSONArray()
                        for (i in 0 until hidden.length()) hiddenFolders.add(hidden.optString(i))
                        customTitles.clear(); readStringMap(backup.optString("custom_titles", "{}"), customTitles)
                        customArtists.clear(); readStringMap(backup.optString("custom_artists", "{}"), customArtists)
                        customAlbums.clear(); readStringMap(backup.optString("custom_albums", "{}"), customAlbums)
                        customGenres.clear(); readStringMap(backup.optString("custom_genres", "{}"), customGenres)
                        customYears.clear(); readStringMap(backup.optString("custom_years", "{}"), customYears)
                        lyricsByTrack.clear(); readStringMap(backup.optString("lyrics_by_track", "{}"), lyricsByTrack)
                        try { val speeds = JSONObject(backup.optString("track_speeds", "{}")); val keys = speeds.keys(); while (keys.hasNext()) { val key = keys.next(); trackSpeeds[key] = speeds.optDouble(key, 1.0).toFloat() } } catch (_: Exception) {}
                        customArtworkUris.clear(); readStringMap(backup.optString("custom_artwork_uris", "{}"), customArtworkUris)
                        playlistArtworkUris.clear(); readStringMap(backup.optString("playlist_artwork_uris", "{}"), playlistArtworkUris)
                        folderTreeUris.clear(); readStringMap(backup.optString("folder_tree_uris", "{}"), folderTreeUris)
                        minFileSizeKb = backup.optLong("min_file_size_kb", 0L).coerceAtLeast(0L)
                        minDurationSeconds = backup.optLong("min_duration_seconds", 0L).coerceAtLeast(0L)
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
                        val recent = backup.optJSONArray("recent_order") ?: JSONArray()
                        val playCounts = backup.optJSONObject("play_counts") ?: JSONObject()
                        val prefsEditor = getSharedPreferences("laczek_player", MODE_PRIVATE).edit()
                            .putString("recent_order", recent.toString())
                            .putLong("listening_ms", backup.optLong("listening_ms", 0L).coerceAtLeast(0L))
                            .putString("saved_queue", (backup.optJSONArray("saved_queue") ?: JSONArray()).toString())
                            .putInt("saved_queue_index", backup.optInt("saved_queue_index", 0).coerceAtLeast(0))
                            .putLong("saved_queue_position", backup.optLong("saved_queue_position", 0L).coerceAtLeast(0L))
                            .putLong("last_track", backup.optLong("last_track", -1L))
                            .putLong("last_position_ms", backup.optLong("last_position_ms", 0L).coerceAtLeast(0L))
                        val oldPlayKeys = getSharedPreferences("laczek_player", MODE_PRIVATE).all.keys.filter { it.startsWith("plays_") }
                        oldPlayKeys.forEach { prefsEditor.remove(it) }
                        val playKeys = playCounts.keys()
                        while (playKeys.hasNext()) {
                            val key = playKeys.next()
                            prefsEditor.putInt(key, playCounts.optInt(key, 0).coerceAtLeast(0))
                        }
                        prefsEditor.apply()
                        saveLocalSettings()
                        rebuildUi()
                        Toast.makeText(this, "Local backup restored", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(this, "Could not restore this backup", Toast.LENGTH_SHORT).show() }
                }
            }
            207 -> {
                if (resultCode == RESULT_OK) { Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show(); scanLibrary() }
            }
            210 -> {
                if (resultCode == RESULT_OK) performRename() else { pendingRenameTrack = null; pendingRenameName = null }
            }
            212 -> {
                val uri = data.data
                val path = pendingFolderPath
                if (uri != null && path != null) {
                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    folderTreeUris[path] = uri.toString(); folderDocumentCache.keys.removeAll { it.startsWith(path + "|") }; saveLocalSettings()
                    Toast.makeText(this, "Folder artwork/info access saved", Toast.LENGTH_SHORT).show()
                    showFolderInfo(path, tracks.filter { it.path == path })
                }
                pendingFolderPath = null
            }
            213 -> {
                val uri = data.data
                val target = playlistArtworkTarget
                if (uri != null && target != null) {
                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    playlistArtworkUris[target] = uri.toString(); saveLocalSettings(); renderLibrary()
                    Toast.makeText(this, "Playlist artwork saved locally", Toast.LENGTH_SHORT).show()
                }
                playlistArtworkTarget = null
            }
            213 -> {
                val uri = data.data
                val target = playlistArtworkTarget
                if (uri != null && target != null) {
                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    playlistArtworkUris[target] = uri.toString(); saveLocalSettings(); renderLibrary()
                    Toast.makeText(this, "Playlist artwork saved locally", Toast.LENGTH_SHORT).show()
                }
                playlistArtworkTarget = null
            }
            209 -> {
                val uri = data.data
                val track = pendingArtworkTrack
                if (uri != null && track != null) {
                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    customArtworkUris[artworkKey(track)] = uri.toString()
                    saveLocalSettings(); updatePlaybackUi()
                    Toast.makeText(this, "Artwork saved locally", Toast.LENGTH_SHORT).show()
                }
                pendingArtworkTrack = null
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
            211 -> {
                data.data?.let { uri ->
                    try {
                        val body = buildString {
                            append("Title,Artist,Album,Genre,Composer,Year,Duration_ms,Duration,Format,Size_bytes,Folder,Media_ID\n")
                            filteredTracks().forEach { track ->
                                append(listOf(
                                    titleOf(track), artistOf(track), albumOf(track), track.genre,
                                    track.composer, if (track.year > 0) track.year.toString() else "",
                                    track.duration.toString(), formatTime(track.duration), track.mimeType,
                                    track.sizeBytes.toString(), track.path, track.id.toString()
                                ).joinToString(",") { csvCell(it) }).append('\n')
                            }
                        }
                        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(body) }
                            ?: throw IllegalStateException("Could not open export destination")
                        Toast.makeText(this, "Exported " + filteredTracks().size + " tracks to CSV", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(this, "Library CSV export failed", Toast.LENGTH_LONG).show()
                    }
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

    private fun renameTrack(track: Track) {
        val input = EditText(this).apply { setText(fileNameOf(track)); setTextColor(fg) }
        AlertDialog.Builder(this).setTitle("Rename local audio file").setMessage("This changes the filename, not the embedded song tags.")
            .setView(input).setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank()) { Toast.makeText(this, "Filename cannot be empty", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                pendingRenameTrack = track; pendingRenameName = newName
                if (Build.VERSION.SDK_INT >= 30) {
                    try { val request = MediaStore.createWriteRequest(contentResolver, listOf(track.uri)); startIntentSenderForResult(request.intentSender, 210, null, 0, 0, 0) }
                    catch (_: Exception) { Toast.makeText(this, "Android could not request rename permission", Toast.LENGTH_LONG).show() }
                } else performRename()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun performRename() {
        val track = pendingRenameTrack ?: return
        val name = pendingRenameName ?: return
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
            val changed = contentResolver.update(track.uri, values, null, null)
            if (changed > 0) Toast.makeText(this, "File renamed", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "Android did not rename this file", Toast.LENGTH_LONG).show()
        } catch (_: Exception) { Toast.makeText(this, "Rename permission was denied or this file cannot be renamed", Toast.LENGTH_LONG).show() }
        pendingRenameTrack = null; pendingRenameName = null; scanLibrary()
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
    private fun reorderPlaylist(name: String) {
        val ids = (playlistMap[name] ?: emptySet()).toList()
        val ordered = ids.mapNotNull { id -> tracks.firstOrNull { it.id.toString() == id }?.let { id to titleOf(it) } }.toMutableList()
        if (ordered.isEmpty()) { Toast.makeText(this, "This playlist has no matching tracks", Toast.LENGTH_SHORT).show(); return }
        fun display() {
            AlertDialog.Builder(this).setTitle("Reorder playlist: " + name)
                .setItems(ordered.mapIndexed { index, item -> (index + 1).toString() + ". " + item.second }.toTypedArray()) { _, index ->
                    AlertDialog.Builder(this).setTitle(ordered[index].second).setItems(arrayOf("Move up", "Move down")) { _, direction ->
                        val target = if (direction == 0) index - 1 else index + 1
                        if (target in ordered.indices) { val item = ordered.removeAt(index); ordered.add(target, item); display() }
                    }.show()
                }.setPositiveButton("Save order") { _, _ -> playlistMap[name] = ordered.map { it.first }.toCollection(linkedSetOf()); saveLocalSettings(); renderLibrary() }
                .setNegativeButton("Cancel", null).show()
        }
        display()
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

    private fun buildUiSelectionRefresh() {
        if (!::root.isInitialized) return
        val header = root.getChildAt(0) as? LinearLayout ?: return
        if (header.childCount > 1) (header.getChildAt(1) as? Button)?.text = if (selectionMode) "Done" else "Select"
    }

    private fun toggleTrackSelection(track: Track) {
        val id = track.id.toString()
        if (!selectedTrackIds.add(id)) selectedTrackIds.remove(id)
        renderLibrary()
    }

    private fun selectedTracks(): List<Track> = tracks.filter { it.id.toString() in selectedTrackIds }

    private fun queueSelectedTracks() {
        val chosen = selectedTracks()
        if (chosen.isEmpty()) { Toast.makeText(this, "Select tracks first", Toast.LENGTH_SHORT).show(); return }
        val p = controller ?: return
        if (p.currentMediaItem == null) { p.setMediaItems(chosen.map { mediaItem(it) }); p.prepare(); p.play() }
        else p.addMediaItems(chosen.map { mediaItem(it) })
        Toast.makeText(this, "Queued ${chosen.size} tracks", Toast.LENGTH_SHORT).show()
    }

    private fun favoriteSelectedTracks() {
        val ids = selectedTrackIds.toList()
        if (ids.isEmpty()) { Toast.makeText(this, "Select tracks first", Toast.LENGTH_SHORT).show(); return }
        val remove = ids.all { it in favorites }
        if (remove) favorites.removeAll(ids.toSet()) else favorites.addAll(ids)
        saveLocalSettings(); renderLibrary()
        Toast.makeText(this, if (remove) "Removed ${ids.size} favourites" else "Added ${ids.size} favourites", Toast.LENGTH_SHORT).show()
    }

    private fun playlistSelectedTracks() {
        val ids = selectedTrackIds.toSet()
        if (ids.isEmpty()) { Toast.makeText(this, "Select tracks first", Toast.LENGTH_SHORT).show(); return }
        val input = EditText(this).apply { hint = "Playlist name"; setTextColor(fg) }
        AlertDialog.Builder(this).setTitle("Save selection as playlist").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) { playlistMap[name] = ids.toMutableSet(); saveLocalSettings(); section = "Playlists"; currentPlaylist = name; selectionMode = false; selectedTrackIds.clear(); rebuildUi() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showAdvancedSearch() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        fun field(label: String, value: String, numeric: Boolean = false): EditText = EditText(this).apply {
            hint = label; setText(value); setTextColor(fg); setHintTextColor(muted)
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val artist = field("Artist contains", filterArtist)
        val year = field("Year", filterYear, true)
        val format = field("Format (mp3, flac, m4a...)", filterFormat)
        val min = field("Minimum duration in seconds", filterMinSeconds, true)
        val max = field("Maximum duration in seconds", filterMaxSeconds, true)
        listOf(artist, year, format, min, max).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("Advanced offline search")
            .setMessage("Combine filters with the search box. Leave a field empty to ignore it.")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                filterArtist = artist.text.toString().trim(); filterYear = year.text.toString().trim()
                filterFormat = format.text.toString().trim(); filterMinSeconds = min.text.toString().trim(); filterMaxSeconds = max.text.toString().trim()
                renderLibrary()
            }
            .setNeutralButton("Clear filters") { _, _ -> filterArtist = ""; filterYear = ""; filterFormat = ""; filterMinSeconds = ""; filterMaxSeconds = ""; renderLibrary() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun updateArtworkAccent(track: Track) {
        val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
        if (!prefs.getBoolean("artwork_colors", true)) return
        val key = track.id.toString()
        if (key == lastAccentTrackId) return
        lastAccentTrackId = key
        val uri = artworkUri(track) ?: return
        try {
            val bitmap = contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 8 })
            } ?: return
            var red = 0L; var green = 0L; var blue = 0L; var count = 0L
            val stepX = (bitmap.width / 24).coerceAtLeast(1); val stepY = (bitmap.height / 24).coerceAtLeast(1)
            for (y in 0 until bitmap.height step stepY) for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y); red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++
            }
            bitmap.recycle()
            if (count > 0) {
                var r = (red / count).toInt(); var g = (green / count).toInt(); var b = (blue / count).toInt()
                if (maxOf(r, g, b) - minOf(r, g, b) < 22) { r = 255; g = 138; b = 61 }
                else if (maxOf(r, g, b) < 105) { r = (r + 65).coerceAtMost(255); g = (g + 65).coerceAtMost(255); b = (b + 65).coerceAtMost(255) }
                accentColor = Color.rgb(r, g, b)
                if (::seek.isInitialized) { seek.progressTintList = android.content.res.ColorStateList.valueOf(orange); seek.thumbTintList = android.content.res.ColorStateList.valueOf(orange) }
                if (::playButton.isInitialized) playButton.background = roundedBackground(orange, dp(25))
                if (::miniPlayButton.isInitialized) miniPlayButton.setTextColor(orange)
            }
        } catch (_: Exception) {}
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

    override fun onResume() {
        super.onResume()
        val savedFavorites = getSharedPreferences("laczek_player", MODE_PRIVATE).getStringSet("favorites", emptySet()) ?: emptySet()
        favorites.clear(); favorites.addAll(savedFavorites)
        if (::songList.isInitialized) renderLibrary()
        updatePlaybackUi()
    }

    override fun onPause() {
        persistPlaybackPosition(force = true)
        super.onPause()
    }

    override fun onDestroy() {
        persistPlaybackPosition(force = true)
        uiTicker?.let { uiHandler.removeCallbacks(it) }
        partyAnimator?.cancel()
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

private class SimpleTextWatcher(val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString() ?: "") }
    override fun afterTextChanged(s: android.text.Editable?) {}
}
