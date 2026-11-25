package com.v3x.laczekplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.PictureInPictureParams
import android.util.Rational
import android.view.MotionEvent
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

private data class LocalVideo(val id: Long, val title: String, val uri: Uri, val duration: Long, val size: Long, val mime: String)

class VideoActivity : Activity() {
    private val videos = mutableListOf<LocalVideo>()
    private lateinit var root: LinearLayout
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var currentVideo: LocalVideo? = null
    private var searchText = ""
    private var playbackSpeed = 1f
    private var landscape = false
    private var controlsLocked = false
    private var aspectMode = 0
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureStartPosition = 0L
    private var gestureStartVolume = 0
    private var gestureStartBrightness = 0.5f
    private val preferences get() = getSharedPreferences("laczek_player", MODE_PRIVATE)
    private val purple get() = preferences.getInt("accent", Color.rgb(157, 104, 255))
    private val darkTheme get() = preferences.getBoolean("dark", true)
    private val backgroundColor get() = if (darkTheme) Color.BLACK else Color.rgb(247, 245, 252)
    private val panelColor get() = if (darkTheme) Color.rgb(29, 27, 35) else Color.rgb(235, 230, 240)
    private val white get() = if (darkTheme) Color.rgb(245, 239, 247) else Color.rgb(28, 27, 31)
    private val muted get() = if (darkTheme) Color.rgb(202, 196, 208) else Color.rgb(73, 69, 79)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        buildLibraryUi()
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) scanVideos()
        else requestPermissions(arrayOf(permission), 402)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 402 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanVideos()
        else {
            Toast.makeText(this, "Video access was not granted. You can still open one video using Browse.", Toast.LENGTH_LONG).show()
            addBrowseFallback()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun card(color: Int = panelColor, radius: Int = 20) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.argb(65, 157, 104, 255))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(white)
        background = card()
        backgroundTintList = null
        stateListAnimator = null
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { onClick() }
    }

    private fun buildLibraryUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("‹") { finish() }.apply { textSize = 28f }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "LaCzek"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = "PLAYER  /  VIDEO LIBRARY"
            textSize = 10f
            letterSpacing = .14f
            setTextColor(purple)
        })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("⌕") { openDocument() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val search = EditText(this).apply {
            hint = "Search videos on this device"
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            isSingleLine = true
            setPadding(dp(16), 0, dp(16), 0)
            background = card()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchText = s?.toString().orEmpty()
                    renderVideos()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(12) })
        val info = TextView(this).apply {
            text = "YOUR DEVICE • LOCAL VIDEO"
            textSize = 10f
            letterSpacing = .1f
            setTextColor(muted)
            setPadding(dp(2), dp(2), 0, dp(10))
            tag = "video_status"
        }
        root.addView(info)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "video_list" }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("Browse a video file") { openDocument() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        setContentView(root)
    }

    private fun scanVideos() {
        videos.clear()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE)
        try {
            contentResolver.query(collection, columns, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    videos.add(LocalVideo(id, cursor.getString(titleCol) ?: "Untitled video",
                        ContentUris.withAppendedId(collection, id), cursor.getLong(durationCol),
                        cursor.getLong(sizeCol), cursor.getString(mimeCol) ?: "video/*"))
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not scan local videos.", Toast.LENGTH_SHORT).show()
        }
        renderVideos()
    }

    private fun renderVideos() {
        val list = root.findViewWithTag<LinearLayout>("video_list") ?: return
        val status = root.findViewWithTag<TextView>("video_status")
        list.removeAllViews()
        val shown = videos.filter { it.title.contains(searchText, true) }
        status?.text = "${shown.size} VIDEOS  •  STORED LOCALLY"
        if (shown.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (videos.isEmpty()) "No videos found yet. Use Browse to open a video from your device." else "No videos match that search."
                textSize = 14f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(44), dp(24), dp(44))
            })
        }
        shown.forEach { video ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = card()
                setPadding(dp(12), dp(12), dp(10), dp(12))
                isClickable = true
                setOnClickListener { playVideo(video) }
            }
            val icon = TextView(this).apply {
                text = "▶"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(purple)
                background = card(Color.rgb(35, 26, 54), 16)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) })
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = video.title
                textSize = 14f
                setTextColor(white)
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            labels.addView(TextView(this).apply {
                text = "${formatTime(video.duration)}  •  ${formatSize(video.size)}  •  ${video.mime.substringAfter("/").uppercase(Locale.ROOT)}"
                textSize = 10f
                setTextColor(muted)
                setPadding(0, dp(5), 0, 0)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("⋮") { showVideoTools(video) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    private fun playVideo(video: LocalVideo) {
        currentVideo = video
        player?.release()
        val savedPosition = preferences.getLong("video_position_" + video.id, 0L).coerceAtLeast(0L)
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(video.uri))
            prepare()
            if (savedPosition > 0L) seekTo(savedPosition)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Toast.makeText(this@VideoActivity, "This video format could not be played on this device.", Toast.LENGTH_LONG).show()
                }
            })
        }
        buildPlaybackUi(video)
    }

    private fun buildPlaybackUi(video: LocalVideo) {
        root.removeAllViews()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("‹ Library") { player?.pause(); playerView?.player = null; playerView = null; buildLibraryUi(); renderVideos() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(button("⋮ Tools") { showVideoTools(video) }, LinearLayout.LayoutParams(dp(100), dp(48)))
        root.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        playerView = PlayerView(this).apply {
            player = this@VideoActivity.player
            useController = !controlsLocked
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            resizeMode = when (aspectMode) {
                1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        gestureDownX = event.x
                        gestureDownY = event.y
                        gestureStartPosition = this@VideoActivity.player?.currentPosition ?: 0L
                        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                        gestureStartVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        gestureStartBrightness = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                    }
                    MotionEvent.ACTION_UP -> handlePlayerGesture(event.x - gestureDownX, event.y - gestureDownY)
                }
                false
            }
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = video.title
            textSize = 17f
            setTextColor(white)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(2), dp(14), dp(2), dp(4))
        })
        root.addView(TextView(this).apply {
            text = "${video.mime}  •  ${formatSize(video.size)}"
            textSize = 11f
            setTextColor(muted)
            setPadding(dp(2), 0, dp(2), dp(12))
        })
        val tools = GridLayout(this).apply { columnCount = 3 }
        listOf(
            "Speed  ${playbackSpeed}×" to { choosePlaybackSpeed(video) },
            "Aspect ratio" to { cycleAspectRatio() },
            "Audio tracks" to { chooseTrack(C.TRACK_TYPE_AUDIO) },
            "Subtitles" to { chooseTrack(C.TRACK_TYPE_TEXT) },
            "Screen lock" to { controlsLocked = !controlsLocked; playerView?.useController = !controlsLocked; Toast.makeText(this, if (controlsLocked) "Player controls locked" else "Player controls unlocked", Toast.LENGTH_SHORT).show() },
            "Picture-in-picture" to { enterPlayerPip() },
            "Previous video" to { playAdjacentVideo(-1) },
            "Next video" to { playAdjacentVideo(1) },
            "Convert to MP3" to { convertToMp3(video) },
            "Audio extract" to { extractAudio(video) },
            "Video info" to { showInfo(video) },
            "Screen rotate" to { landscape = !landscape; requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        ).forEach { (label, action) ->
            val b = button(label, action)
            tools.addView(b, GridLayout.LayoutParams().apply { width = 0; height = dp(48); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        root.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }


    private fun playAdjacentVideo(direction: Int) {
        val current = currentVideo ?: return
        val index = videos.indexOfFirst { it.id == current.id && it.uri == current.uri }
        if (index < 0) {
            Toast.makeText(this, "This file is not in the scanned video library", Toast.LENGTH_SHORT).show()
            return
        }
        val next = (index + direction).coerceIn(0, videos.lastIndex)
        if (next == index) {
            Toast.makeText(this, if (direction < 0) "First video in library" else "Last video in library", Toast.LENGTH_SHORT).show()
            return
        }
        playVideo(videos[next])
    }

    private fun choosePlaybackSpeed(video: LocalVideo) {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val labels = speeds.map { it.toString() + "×" }.toTypedArray()
        val selected = speeds.indexOf(playbackSpeed).coerceAtLeast(2)
        AlertDialog.Builder(this).setTitle("Playback speed")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                playbackSpeed = speeds[which]
                player?.playbackParameters = PlaybackParameters(playbackSpeed)
                dialog.dismiss()
                buildPlaybackUi(video)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun cycleAspectRatio() {
        aspectMode = (aspectMode + 1) % 3
        playerView?.resizeMode = when (aspectMode) {
            1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val label = arrayOf("Fit", "Zoom", "Stretch")[aspectMode]
        Toast.makeText(this, "Aspect ratio: " + label, Toast.LENGTH_SHORT).show()
    }

    private fun chooseTrack(trackType: Int) {
        val currentPlayer = player ?: return
        val groups = currentPlayer.currentTracks.groups.filter { it.type == trackType }
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        options.add(if (trackType == C.TRACK_TYPE_AUDIO) "Default audio" else "Subtitles off")
        actions.add {
            currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(trackType, trackType == C.TRACK_TYPE_TEXT)
                .clearOverridesOfType(trackType)
                .build()
        }
        groups.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val label = format.label ?: format.language ?: ("Track " + (index + 1))
                options.add(listOfNotNull(label, format.codecs, format.sampleMimeType).distinct().joinToString(" · "))
                actions.add {
                    currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(trackType, false)
                        .clearOverridesOfType(trackType)
                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .build()
                }
            }
        }
        if (options.size == 1) {
            Toast.makeText(this, if (trackType == C.TRACK_TYPE_AUDIO) "No selectable audio tracks found" else "No selectable subtitles found", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (trackType == C.TRACK_TYPE_AUDIO) "Audio tracks" else "Subtitles")
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun enterPlayerPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (_: Exception) {
                Toast.makeText(this, "Picture-in-picture is unavailable on this device", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Picture-in-picture requires Android 8.0 or newer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePlayerGesture(dx: Float, dy: Float) {
        val activePlayer = player ?: return
        val view = playerView ?: return
        if (kotlin.math.abs(dx) > dp(48) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
            val delta = (dx / view.width.coerceAtLeast(1) * 120000L).toLong()
            val end = activePlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            val target = (gestureStartPosition + delta).coerceIn(0L, end)
            activePlayer.seekTo(target)
            Toast.makeText(this, (if (delta >= 0) "+" else "") + (delta / 1000) + "s · " + formatTime(target), Toast.LENGTH_SHORT).show()
        } else if (kotlin.math.abs(dy) > dp(48) && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
            if (gestureDownX < view.width / 2f) {
                val attributes = window.attributes
                attributes.screenBrightness = (gestureStartBrightness - dy / view.height.coerceAtLeast(1)).coerceIn(0.02f, 1f)
                window.attributes = attributes
                Toast.makeText(this, "Brightness " + (attributes.screenBrightness * 100).toInt() + "%", Toast.LENGTH_SHORT).show()
            } else {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val next = (gestureStartVolume - (dy / view.height.coerceAtLeast(1) * max).toInt()).coerceIn(0, max)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                Toast.makeText(this, "Volume " + (next * 100 / max) + "%", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveVideoPosition() {
        val video = currentVideo ?: return
        val position = player?.currentPosition ?: return
        preferences.edit().putLong("video_position_" + video.id, position.coerceAtLeast(0L)).apply()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) enterPlayerPip()
    }

    override fun onPause() {
        saveVideoPosition()
        super.onPause()
    }

    private fun showVideoTools(video: LocalVideo) {
        AlertDialog.Builder(this)
            .setTitle(video.title)
            .setItems(arrayOf("Play video", "Convert to MP3", "Extract audio to M4A", "Video details", "Share video")) { _, which ->
                when (which) {
                    0 -> playVideo(video)
                    1 -> convertToMp3(video)
                    2 -> extractAudio(video)
                    3 -> showInfo(video)
                    4 -> shareVideo(video)
                }
            }.show()
    }

    private fun showInfo(video: LocalVideo) {
        AlertDialog.Builder(this).setTitle("Video details")
            .setMessage("Name: ${video.title}\nFormat: ${video.mime}\nDuration: ${formatTime(video.duration)}\nSize: ${formatSize(video.size)}\n\nStored on this device. No upload required.")
            .setPositiveButton("Close", null).show()
    }

    private fun shareVideo(video: LocalVideo) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = video.mime
                putExtra(Intent.EXTRA_STREAM, video.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share video"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app available to share this video.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun convertToMp3(video: LocalVideo) {
        Toast.makeText(this, "Converting video audio to MP3 on this device…", Toast.LENGTH_LONG).show()
        Thread {
            var input: File? = null
            try {
                val inputFile = File(cacheDir, "laczek_video_${System.nanoTime()}.mp4")
                input = inputFile
                contentResolver.openInputStream(video.uri).use { source ->
                    if (source == null) throw IllegalStateException("Cannot read this video.")
                    inputFile.outputStream().use { target -> source.copyTo(target) }
                }
                val directory = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
                if (!directory.exists()) directory.mkdirs()
                val output = File(directory, "LaCzek-${video.id.takeIf { it >= 0 } ?: System.currentTimeMillis()}.mp3")
                val command = "-y -i ${inputFile.absolutePath} -vn -codec:a libmp3lame -q:a 2 ${output.absolutePath}"
                FFmpegKit.executeAsync(command) { session ->
                    val success = ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0L
                    runOnUiThread {
                        if (success) {
                            AlertDialog.Builder(this).setTitle("MP3 ready")
                                .setMessage("Converted locally. No upload required.\n\n${output.absolutePath}")
                                .setPositiveButton("Share MP3") { _, _ -> shareAudio(output) }
                                .setNegativeButton("Close", null).show()
                        } else {
                            Toast.makeText(this, "MP3 conversion failed. This file may contain an unsupported audio stream.", Toast.LENGTH_LONG).show()
                        }
                    }
                    inputFile.delete()
                }
            } catch (e: Exception) {
                input?.delete()
                runOnUiThread { Toast.makeText(this, "Could not convert to MP3: ${e.message ?: "file access error"}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun shareAudio(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "audio/mpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share MP3"))
        } catch (_: Exception) {
            Toast.makeText(this, "MP3 saved in LaCzek Player's Music folder.", Toast.LENGTH_LONG).show()
        }
    }

    private fun extractAudio(video: LocalVideo) {
        Toast.makeText(this, "Extracting audio…", Toast.LENGTH_SHORT).show()
        Thread {
            var extractor: MediaExtractor? = null
            var muxer: MediaMuxer? = null
            var started = false
            try {
                val extractorInstance = MediaExtractor()
                extractor = extractorInstance
                extractorInstance.setDataSource(this, video.uri, null)
                var audioTrack = -1
                for (i in 0 until extractorInstance.trackCount) {
                    val format = extractorInstance.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { audioTrack = i; break }
                }
                if (audioTrack < 0) throw IllegalStateException("This video has no audio track.")
                val format = extractorInstance.getTrackFormat(audioTrack)
                extractorInstance.selectTrack(audioTrack)
                val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
                if (!dir.exists()) dir.mkdirs()
                val outputFile = File(dir, video.title.replace(Regex("[^A-Za-z0-9._ -]"), "_") + "-audio.m4a")
                val muxerInstance = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = muxerInstance
                val outputTrack = muxerInstance.addTrack(format)
                muxerInstance.start()
                started = true
                val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
                val info = android.media.MediaCodec.BufferInfo()
                while (true) {
                    buffer.clear()
                    val size = extractorInstance.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractorInstance.sampleTime
                    info.flags = extractorInstance.sampleFlags
                    muxerInstance.writeSampleData(outputTrack, buffer, info)
                    extractorInstance.advance()
                }
                muxerInstance.stop()
                started = false
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Audio extracted")
                        .setMessage("Saved audio as M4A/AAC:\n${outputFile.absolutePath}\n\nThis keeps the original audio track without re-encoding.")
                        .setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Audio extraction failed: ${e.message ?: "unsupported audio format"}", Toast.LENGTH_LONG).show() }
            } finally {
                try { if (started) muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun openDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 403)
    }

    @Deprecated("Use Activity Result APIs when modernizing")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 403 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val name = queryName(uri) ?: "Selected video"
            val item = LocalVideo(-1, name, uri, 0L, 0L, contentResolver.getType(uri) ?: "video/*")
            playVideo(item)
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun addBrowseFallback() {
        root.addView(button("Browse a video file") { openDocument() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.ROOT, "%d:%02d", total / 60L, total % 60L)
    }
    private fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> "Size unknown"
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> "${bytes / 1024L} KB"
    }

    override fun onBackPressed() {
        if (player != null && root.childCount > 0 && playerView != null) {
            player?.pause()
            playerView?.player = null
            playerView = null
            buildLibraryUi()
            renderVideos()
        } else super.onBackPressed()
    }

    override fun onDestroy() {
        saveVideoPosition()
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
