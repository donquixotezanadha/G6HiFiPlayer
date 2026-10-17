package id.g6hifiplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.RemoteViews

/** Home-screen controls share the existing playback actions used by the media notification. */
open class G6MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> update(context, manager, id) }
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        MainActivity.notificationAction(action)
    }
    companion object {
        const val EXTRA_ACTION = "widget_action"
        fun refresh(context: Context, title: String, artist: String, album: String, playing: Boolean, artwork: Bitmap?, light: Boolean, position: Int, duration: Int) {
            val manager = AppWidgetManager.getInstance(context)
            listOf(G6MusicWidgetProvider::class.java, G6CompactWidgetProvider::class.java).forEach { cls ->
                val ids = manager.getAppWidgetIds(ComponentName(context, cls))
                ids.forEach { id ->
                    val compact = cls == G6CompactWidgetProvider::class.java
                    val views = RemoteViews(context.packageName, if (compact) R.layout.widget_player_2x2 else R.layout.widget_player_4x2)
                    if (compact) {
                        if (artwork != null) views.setImageViewBitmap(R.id.widget_art, artwork)
                        views.setInt(R.id.widget_play, "setColorFilter", if (light) Color.WHITE else Color.BLACK)
                        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
                        views.setInt(R.id.widget_play_circle, "setBackgroundColor", if (light) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
                    } else {
                        views.setTextViewText(R.id.widget_title, title.ifBlank { "Not Playing" })
                        views.setTextViewText(R.id.widget_artist, listOf(artist, album).filter { it.isNotBlank() }.joinToString(" • "))
                        views.setTextViewText(R.id.widget_elapsed, time(position))
                        views.setTextViewText(R.id.widget_duration, time(duration))
                        views.setProgressBar(R.id.widget_progress, duration.coerceAtLeast(1), position.coerceIn(0, duration.coerceAtLeast(1)), false)
                        if (artwork != null) views.setImageViewBitmap(R.id.widget_art, artwork)
                        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
                        views.setInt(R.id.widget_card, "setBackgroundColor", if (light) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
                        views.setTextColor(R.id.widget_title, if (light) Color.BLACK else Color.WHITE)
                        views.setTextColor(R.id.widget_artist, if (light) 0xFF666666.toInt() else 0xFFAAAAAA.toInt())
                    }
                    bind(context, views, R.id.widget_play, "toggle")
                    if (!compact) {
                        bind(context, views, R.id.widget_previous, "previous")
                        bind(context, views, R.id.widget_next, "next")
                    }
                    val open = PendingIntent.getActivity(context, 20 + if (compact) 1 else 0, Intent(context, MainActivity::class.java), flags())
                    views.setOnClickPendingIntent(if (compact) R.id.widget_art else R.id.widget_card, open)
                    manager.updateAppWidget(id, views)
                }
            }
        }
        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            refresh(context, "G6HiFiPlayer", "Choose a song", "", false, null, false, 0, 1)
        }
        private fun bind(context: Context, views: RemoteViews, id: Int, action: String) {
            val intent = Intent(context, G6MusicWidgetProvider::class.java).putExtra(EXTRA_ACTION, action)
            views.setOnClickPendingIntent(id, PendingIntent.getBroadcast(context, action.hashCode(), intent, flags()))
        }
        private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or if (android.os.Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        private fun time(ms: Int): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, (ms.coerceAtLeast(0) / 1000) % 60)
    }
}
class G6CompactWidgetProvider : G6MusicWidgetProvider()
