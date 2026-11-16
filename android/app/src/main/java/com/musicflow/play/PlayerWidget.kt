package com.musicflow.play

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews

/** Home screen widget: the song playing, with previous, play/pause and next. Tapping it opens the app. */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, null)

    companion object {
        fun update(context: Context, cover: Bitmap?) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
                if (ids.isEmpty()) return
                val v = RemoteViews(context.packageName, R.layout.widget_player)
                val on = PlaybackService.running
                v.setTextViewText(R.id.w_title, if (on && PlaybackService.title.isNotEmpty()) PlaybackService.title else "MusicFlow Play")
                v.setTextViewText(R.id.w_artist, if (on) PlaybackService.artist else "Tap to open")
                if (on && cover != null) v.setImageViewBitmap(R.id.w_cover, cover) else if (!on) v.setImageViewResource(R.id.w_cover, R.mipmap.ic_launcher)
                v.setImageViewResource(R.id.w_play, if (on && PlaybackService.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                v.setOnClickPendingIntent(R.id.w_root, open)
                fun cmd(c: String, code: Int): PendingIntent {
                    if (!on) return open
                    val i = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_COMMAND).putExtra(PlaybackService.EXTRA_COMMAND, c)
                    val f = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, code, i, f) else PendingIntent.getService(context, code, i, f)
                }
                v.setOnClickPendingIntent(R.id.w_prev, cmd("prev", 11))
                v.setOnClickPendingIntent(R.id.w_play, cmd(if (PlaybackService.playing) "pause" else "play", 12))
                v.setOnClickPendingIntent(R.id.w_next, cmd("next", 13))
                manager.updateAppWidget(ids, v)
            } catch (e: Exception) { }
        }
    }
}
