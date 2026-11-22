package com.musicflow.play

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.RemoteViews

/** Home screen widgets. Every kind shows the song playing; the bigger ones add more (favorite, shuffle, repeat,
 *  the next songs, shortcuts). Buttons a layout does not have are simply skipped. */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = draw(context)

    companion object {
        @Volatile var lastCover: Bitmap? = null
        /** From the page: the song is a favorite, and the next songs ("Title · Artist"). */
        @Volatile var favorite = false
        @Volatile var upNext: List<String> = emptyList()

        const val EXTRA_ACTION = "widget_action"

        private val KINDS = listOf(
            PlayerWidget::class.java to R.layout.widget_player,
            WidgetMini::class.java to R.layout.widget_mini,
            WidgetCard::class.java to R.layout.widget_card,
            WidgetBig::class.java to R.layout.widget_big,
            WidgetShortcuts::class.java to R.layout.widget_shortcuts,
        )

        /** Called by the player service with the cover of the song (null while it loads or when nothing plays). */
        fun update(context: Context, cover: Bitmap?) {
            lastCover = cover
            draw(context)
        }

        fun draw(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            for ((cls, layout) in KINDS) {
                try {
                    val ids = manager.getAppWidgetIds(ComponentName(context, cls))
                    if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, layout))
                } catch (e: Exception) { }
            }
        }

        private fun open(context: Context, action: String?, code: Int): PendingIntent {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action != null) i.putExtra(EXTRA_ACTION, action)
            return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun views(context: Context, layout: Int): RemoteViews {
            val v = RemoteViews(context.packageName, layout)
            val on = PlaybackService.running
            val playing = on && PlaybackService.playing
            v.setTextViewText(R.id.w_title, if (on && PlaybackService.title.isNotEmpty()) PlaybackService.title else "MusicFlow Play")
            v.setTextViewText(R.id.w_artist, if (on) PlaybackService.artist else "Tap to open")
            val cover = lastCover
            if (on && cover != null) v.setImageViewBitmap(R.id.w_cover, cover) else v.setImageViewResource(R.id.w_cover, R.mipmap.ic_launcher)
            v.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            v.setImageViewResource(R.id.w_fav, if (on && favorite) R.drawable.ic_w_heart_f else R.drawable.ic_w_heart)
            v.setImageViewResource(R.id.w_shuffle, if (PlaybackService.shuffle) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle_off)
            v.setImageViewResource(R.id.w_repeat, when (PlaybackService.repeat) {
                "one" -> R.drawable.ic_repeat_one
                "all" -> R.drawable.ic_repeat_all
                else -> R.drawable.ic_repeat_off
            })
            val next = if (on) upNext else emptyList()
            listOf(R.id.w_up1, R.id.w_up2, R.id.w_up3).forEachIndexed { k, id -> v.setTextViewText(id, next.getOrNull(k) ?: if (k == 0) "Nothing after this song" else "") }
            v.setViewVisibility(R.id.w_upbox, if (on) View.VISIBLE else View.INVISIBLE)

            val openApp = open(context, null, 10)
            v.setOnClickPendingIntent(R.id.w_root, openApp)
            fun cmd(c: String, code: Int): PendingIntent {
                if (!on) return if (c == "play") open(context, "play", 19) else openApp
                val i = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_COMMAND).putExtra(PlaybackService.EXTRA_COMMAND, c)
                val f = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, code, i, f) else PendingIntent.getService(context, code, i, f)
            }
            v.setOnClickPendingIntent(R.id.w_prev, cmd("prev", 11))
            v.setOnClickPendingIntent(R.id.w_play, cmd(if (playing) "pause" else "play", 12))
            v.setOnClickPendingIntent(R.id.w_next, cmd("next", 13))
            v.setOnClickPendingIntent(R.id.w_fav, cmd("fav", 14))
            v.setOnClickPendingIntent(R.id.w_shuffle, cmd("shuffle", 15))
            v.setOnClickPendingIntent(R.id.w_repeat, cmd("repeat", 16))
            v.setOnClickPendingIntent(R.id.w_upbox, open(context, "queue", 17))
            v.setOnClickPendingIntent(R.id.w_sc_shuffle, open(context, "shuffleall", 20))
            v.setOnClickPendingIntent(R.id.w_sc_fav, open(context, "favorites", 21))
            v.setOnClickPendingIntent(R.id.w_sc_top, open(context, "mixtop", 22))
            v.setOnClickPendingIntent(R.id.w_sc_new, open(context, "mixnew", 23))
            return v
        }
    }
}

class WidgetMini : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = PlayerWidget.draw(context)
}

class WidgetCard : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = PlayerWidget.draw(context)
}

class WidgetBig : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = PlayerWidget.draw(context)
}

class WidgetShortcuts : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = PlayerWidget.draw(context)
}
