package com.v3x.laczekplayer

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsTicker = object : Runnable {
        override fun run() {
            val activePlayer = player
            if (activePlayer?.isPlaying == true) {
                val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
                prefs.edit().putLong("listening_ms", prefs.getLong("listening_ms", 0L) + 1000L).apply()
            }
            statsHandler.postDelayed(this, 1000L)
        }
    }

    companion object {
        @Volatile var audioSessionId: Int = 0
            private set
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
        }
        audioSessionId = player?.audioSessionId ?: 0
        session = MediaSession.Builder(this, player!!).build()
        statsHandler.postDelayed(statsTicker, 1000L)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsTicker)
        session?.release()
        player?.release()
        session = null
        player = null
        audioSessionId = 0
        super.onDestroy()
    }
}
