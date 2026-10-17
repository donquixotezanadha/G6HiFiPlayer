package id.g6hifiplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.widget.RemoteViews
import java.io.File

/** Home-screen widgets retain the last rendered track even after the app process exits. */
open class G6MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Do not replace persisted playback information with placeholder data when the launcher
        // creates/recreates a widget. Re-render the last known state instead.
        renderSavedState(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        MainActivity.notificationAction(action)
    }

    companion object {
        const val EXTRA_ACTION = "widget_action"
        private const val PREFS = "g6_widget_state"
        private const val ART_FILE = "g6_widget_artwork.jpg"
        private const val ACCENT = 0xFF6662C6.toInt()
        private const val THUMB_SIZE = 256

        fun refresh(
            context: Context,
            title: String,
            artist: String,
            album: String,
            playing: Boolean,
            artwork: Bitmap?,
            light: Boolean,
            position: Int,
            duration: Int
        ) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Persist only a small widget thumbnail. Sending the Now Playing bitmap (up to
            // 960x960) through RemoteViews can exceed Binder's transaction limit on Android 9.
            if (artwork != null && !artwork.isRecycled) {
                try {
                    val ratio = minOf(1f, THUMB_SIZE.toFloat() / maxOf(artwork.width, artwork.height).coerceAtLeast(1))
                    val thumb = if (ratio < 1f) Bitmap.createScaledBitmap(
                        artwork,
                        (artwork.width * ratio).toInt().coerceAtLeast(1),
                        (artwork.height * ratio).toInt().coerceAtLeast(1),
                        true
                    ) else artwork
                    context.openFileOutput(ART_FILE, Context.MODE_PRIVATE).use { out ->
                        thumb.compress(Bitmap.CompressFormat.JPEG, 84, out)
                    }
                    if (thumb !== artwork) thumb.recycle()
                } catch (_: Exception) {
                    // Keep the last valid artwork file if writing a new thumbnail fails.
                }
            }
            prefs.edit()
                .putBoolean("has_track", title.isNotBlank() && title != "G6HiFiPlayer")
                .putString("title", title)
                .putString("artist", artist)
                .putString("album", album)
                .putBoolean("playing", playing)
                .putBoolean("light", light)
                .putInt("position", position.coerceAtLeast(0))
                .putInt("duration", duration.coerceAtLeast(0))
                .apply()
            renderSavedState(context)
        }

        private fun renderSavedState(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val hasTrack = prefs.getBoolean("has_track", false)
            val title = if (hasTrack) prefs.getString("title", "NO MUSIC PLAYING").orEmpty() else "NO MUSIC PLAYING"
            val artist = if (hasTrack) prefs.getString("artist", "").orEmpty() else ""
            val album = if (hasTrack) prefs.getString("album", "").orEmpty() else ""
            val playing = hasTrack && prefs.getBoolean("playing", false)
            val light = prefs.getBoolean("light", false)
            val position = prefs.getInt("position", 0)
            val duration = prefs.getInt("duration", 0)
            val artwork = if (hasTrack) try {
                File(context.filesDir, ART_FILE).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            } catch (_: Exception) { null } else null

            val manager = AppWidgetManager.getInstance(context)
            listOf(G6MusicWidgetProvider::class.java, G6CompactWidgetProvider::class.java).forEach { cls ->
                val ids = manager.getAppWidgetIds(ComponentName(context, cls))
                if (ids.isEmpty()) return@forEach
                val compact = cls == G6CompactWidgetProvider::class.java
                ids.forEach { id ->
                    val views = RemoteViews(context.packageName, if (compact) R.layout.widget_player_2x2 else R.layout.widget_player_4x2)
                    if (compact) {
                        if (artwork != null) views.setImageViewBitmap(R.id.widget_art, artwork)
                        else views.setImageViewResource(R.id.widget_art, android.R.color.transparent)
                        views.setInt(R.id.widget_art, "setBackgroundColor", if (hasTrack) ACCENT else ACCENT)
                        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
                        views.setInt(R.id.widget_play_circle, "setBackgroundColor", if (light) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
                        views.setInt(R.id.widget_play, "setColorFilter", if (light) Color.BLACK else Color.WHITE)
                    } else {
                        views.setTextViewText(R.id.widget_title, title)
                        views.setTextViewText(R.id.widget_artist, listOf(artist, album).filter { it.isNotBlank() }.joinToString(" • "))
                        views.setTextViewText(R.id.widget_elapsed, time(position))
                        views.setTextViewText(R.id.widget_duration, time(duration))
                        views.setProgressBar(R.id.widget_progress, duration.coerceAtLeast(1), position.coerceIn(0, duration.coerceAtLeast(1)), false)
                        if (artwork != null) views.setImageViewBitmap(R.id.widget_art, artwork)
                        else views.setImageViewResource(R.id.widget_art, android.R.color.transparent)
                        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
                        views.setInt(R.id.widget_card, "setBackgroundColor", if (hasTrack) {
                            if (light) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()
                        } else ACCENT)
                        views.setTextColor(R.id.widget_title, if (!hasTrack) Color.WHITE else if (light) Color.BLACK else Color.WHITE)
                        views.setTextViewText(R.id.widget_title, if (hasTrack) title else "NO MUSIC PLAYING")
                        views.setTextColor(R.id.widget_artist, if (light) 0xFF666666.toInt() else 0xFFAAAAAA.toInt())
                        views.setViewVisibility(R.id.widget_artist, if (hasTrack) android.view.View.VISIBLE else android.view.View.GONE)
                        views.setViewVisibility(R.id.widget_progress, if (hasTrack) android.view.View.VISIBLE else android.view.View.GONE)
                        views.setViewVisibility(R.id.widget_elapsed, if (hasTrack) android.view.View.VISIBLE else android.view.View.GONE)
                        views.setViewVisibility(R.id.widget_duration, if (hasTrack) android.view.View.VISIBLE else android.view.View.GONE)
                    }
                    bind(context, views, R.id.widget_play, "toggle")
                    if (!compact) {
                        bind(context, views, R.id.widget_previous, "previous")
                        bind(context, views, R.id.widget_next, "next")
                    }
                    val open = PendingIntent.getActivity(
                        context, 20 + if (compact) 1 else 0,
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        flags()
                    )
                    views.setOnClickPendingIntent(if (compact) R.id.widget_art else R.id.widget_card, open)
                    manager.updateAppWidget(id, views)
                }
            }
            artwork?.recycle()
        }

        private fun bind(context: Context, views: RemoteViews, id: Int, action: String) {
            val intent = Intent(context, G6MusicWidgetProvider::class.java).putExtra(EXTRA_ACTION, action)
            views.setOnClickPendingIntent(id, PendingIntent.getBroadcast(context, action.hashCode(), intent, flags()))
        }

        private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or
            if (android.os.Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

        private fun time(ms: Int): String =
            "%d:%02d".format(ms.coerceAtLeast(0) / 60000, (ms.coerceAtLeast(0) / 1000) % 60)
    }
}

class G6CompactWidgetProvider : G6MusicWidgetProvider()
