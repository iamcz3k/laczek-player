package com.v3x.laczekplayer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var crossfadePlayer: ExoPlayer? = null
    private var crossfadeSourceIndex = -1
    private var crossfadeTargetIndex = -1
    private var crossfadeStartMs = 0L
    private var crossfadeDurationMs = 5000L
    private val crossfadeHandler = Handler(Looper.getMainLooper())
    private val crossfadeTicker = object : Runnable {
        override fun run() { updateCrossfade(); crossfadeHandler.postDelayed(this, 100L) }
    }
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
        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val active = player ?: return
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && crossfadeTargetIndex >= 0 &&
                    active.currentMediaItemIndex == crossfadeTargetIndex) {
                    val targetDuration = active.duration.takeIf { it > 0L } ?: crossfadeDurationMs
                    active.seekTo(crossfadeDurationMs.coerceAtMost((targetDuration - 1000L).coerceAtLeast(0L)))
                    active.volume = 1f
                    crossfadePlayer?.pause()
                    crossfadePlayer?.volume = 0f
                    crossfadePlayer?.clearMediaItems()
                    crossfadeSourceIndex = -1
                    crossfadeTargetIndex = -1
                } else if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) cancelCrossfade()
            }
        })
        statsHandler.postDelayed(statsTicker, 1000L)
        crossfadeHandler.postDelayed(crossfadeTicker, 100L)
    }


    private fun updateCrossfade() {
        val active = player ?: return
        val prefs = getSharedPreferences("laczek_player", MODE_PRIVATE)
        if (!prefs.getBoolean("crossfade_enabled", false) || !active.isPlaying ||
            active.currentMediaItemIndex < 0 || active.currentMediaItemIndex + 1 >= active.mediaItemCount) {
            if (!prefs.getBoolean("crossfade_enabled", false) || !active.isPlaying) cancelCrossfade()
            return
        }
        val duration = active.duration
        if (duration <= 0L) return
        val remaining = duration - active.currentPosition
        val configured = prefs.getInt("crossfade_seconds", 5).coerceIn(2, 10) * 1000L
        val index = active.currentMediaItemIndex
        if (crossfadeSourceIndex != index && remaining in 1..configured) {
            try {
                val nextItem = active.getMediaItemAt(index + 1)
                val secondary = crossfadePlayer ?: ExoPlayer.Builder(this).build().also { built ->
                    built.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                    built.setHandleAudioBecomingNoisy(false)
                    built.volume = 0f
                    crossfadePlayer = built
                }
                secondary.pause(); secondary.clearMediaItems(); secondary.setMediaItem(nextItem)
                secondary.volume = 0f; secondary.prepare(); secondary.play()
                crossfadeSourceIndex = index
                crossfadeTargetIndex = index + 1
                crossfadeDurationMs = remaining.coerceAtLeast(250L)
                crossfadeStartMs = SystemClock.elapsedRealtime()
            } catch (_: Exception) { cancelCrossfade() }
        }
        if (crossfadeSourceIndex == index && crossfadeTargetIndex == index + 1) {
            val elapsed = SystemClock.elapsedRealtime() - crossfadeStartMs
            val fraction = (elapsed.toFloat() / crossfadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            active.volume = 1f - fraction
            crossfadePlayer?.volume = fraction
        }
    }

    private fun cancelCrossfade() {
        player?.volume = 1f
        crossfadePlayer?.let { it.pause(); it.volume = 0f; it.clearMediaItems() }
        crossfadeSourceIndex = -1
        crossfadeTargetIndex = -1
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsTicker)
        crossfadeHandler.removeCallbacks(crossfadeTicker)
        session?.release()
        crossfadePlayer?.release()
        player?.release()
        session = null
        player = null
        audioSessionId = 0
        super.onDestroy()
    }
}
