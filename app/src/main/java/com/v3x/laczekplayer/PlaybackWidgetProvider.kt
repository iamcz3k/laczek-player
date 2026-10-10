package com.v3x.laczekplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Executor

class PlaybackWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateWidgets(context, manager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_OPEN -> {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ACTION_PLAY_PAUSE, ACTION_NEXT -> {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val future = MediaController.Builder(context, token).buildAsync()
                val handler = Handler(Looper.getMainLooper())
                future.addListener({
                    try {
                        val controller = future.get()
                        if (intent.action == ACTION_NEXT) controller.seekToNextMediaItem()
                        else if (controller.isPlaying) controller.pause() else controller.play()
                        MediaController.releaseFuture(future)
                        refresh(context)
                    } catch (_: Exception) {
                        MediaController.releaseFuture(future)
                    }
                }, Executor { command -> handler.post(command) })
            }
        }
    }

    companion object {
        const val ACTION_OPEN = "com.v3x.laczekplayer.WIDGET_OPEN"
        const val ACTION_PLAY_PAUSE = "com.v3x.laczekplayer.WIDGET_PLAY_PAUSE"
        const val ACTION_NEXT = "com.v3x.laczekplayer.WIDGET_NEXT"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlaybackWidgetProvider::class.java))
            if (ids.isNotEmpty()) updateWidgets(context, manager, ids)
        }

        private fun updateWidgets(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = context.getSharedPreferences("laczek_player", Context.MODE_PRIVATE)
            val title = prefs.getString("widget_title", "LaCzek Player") ?: "LaCzek Player"
            val artist = prefs.getString("widget_artist", "Offline music") ?: "Offline music"
            val playing = prefs.getBoolean("widget_playing", false)
            val views = RemoteViews(context.packageName, R.layout.widget_laczek_player)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            views.setTextViewText(R.id.widget_play_pause, if (playing) "Ⅱ" else "▶")
            views.setOnClickPendingIntent(R.id.widget_open, pending(context, ACTION_OPEN, 4101))
            views.setOnClickPendingIntent(R.id.widget_play_pause, pending(context, ACTION_PLAY_PAUSE, 4102))
            views.setOnClickPendingIntent(R.id.widget_next, pending(context, ACTION_NEXT, 4103))
            manager.updateAppWidget(ids, views)
        }

        private fun pending(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, PlaybackWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
