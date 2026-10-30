package id.g6hifiplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.io.File

/**
 * Build 2.14: the two home-screen widgets, written again from scratch (the old ones are gone).
 * Build 2.17: both follow the reference picture.
 *  - 4x2: rounded cover on the left; title, singer, album, thin progress bar, previous / play / next (outline icons). Background 80% black.
 *  - 2x2: big round cover with a small rounded-square play button overlapping its bottom-left; no card behind it.
 * Both are registered in the manifest (the old 2x2 never was). Dark look only, like the player.
 * Nothing runs in the background: the player pushes its state here (refresh / refreshProgress).
 */
object G6Widgets {
    const val EXTRA_ACTION = "g6_widget_action"

    private const val TAG = "G6Widgets"
    private const val PREFS = "g6_widgets"
    private const val ART_FILE = "g6_widgets_art.jpg"
    private const val OLD_PREFS = "g6_widget_state"
    private const val OLD_ART_FILE = "g6_widget_artwork.jpg"
    private const val EMPTY_TITLE = "NO MUSIC PLAYING"

    /** Saved cover: square, this many pixels. Small enough to travel inside RemoteViews on Android 9. */
    private const val THUMB_PX = 400
    private const val PLACEHOLDER_PX = 256
    /** Corner radius of the 4x2 cover as a fraction of its side (Build 2.17: a little rounder, like the picture). */
    private const val CORNER_FRACTION = 0.12f
    /** The 2x2 cover is a circle: radius = half the side. */
    private const val CIRCLE_FRACTION = 0.5f
    /** A 4x2 widget at least this tall (dp) gets the large layout (bigger cover and buttons). */
    private const val LARGE_MIN_HEIGHT_DP = 170
    private const val TOGGLE_DEBOUNCE_MS = 180L

    private const val K_HAS = "has_track"
    private const val K_TITLE = "title"
    private const val K_ARTIST = "artist"
    private const val K_ALBUM = "album"
    private const val K_PLAYING = "playing"
    private const val K_HIRES = "hires"
    private const val K_POS = "position"
    private const val K_DUR = "duration"
    private const val K_ART_ID = "art_identity"
    private const val K_OLD_CLEANED = "old_cleaned"

    private val lock = Any()
    private var lastToggleAtMs = Long.MIN_VALUE

    // ---- called by the player -------------------------------------------------------------

    /** Full state push from the player (track, cover, play/pause, position). Cheap when nothing but play/pause changed. */
    fun refresh(
        context: Context,
        title: String,
        artist: String,
        album: String,
        playing: Boolean,
        artwork: Bitmap?,
        position: Int,
        duration: Int,
        hires: Boolean = false
    ) {
        val app = context.applicationContext
        synchronized(lock) {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            cleanupOldWidgetData(app, prefs)
            val hasTrack = title.isNotBlank() && title != "G6HiFiPlayer"
            val sameTrack = prefs.getBoolean(K_HAS, false) == hasTrack &&
                prefs.getString(K_TITLE, null) == title &&
                prefs.getString(K_ARTIST, null) == artist &&
                prefs.getString(K_ALBUM, null) == album

            // Build 2.18: widgets carry no album art. A cover file saved by an older build is removed once.
            val oldArt = File(app.filesDir, ART_FILE)
            if (oldArt.exists()) oldArt.delete()
            val artChanged = false

            prefs.edit()
                .putBoolean(K_HAS, hasTrack)
                .putString(K_TITLE, title)
                .putString(K_ARTIST, artist)
                .putString(K_ALBUM, album)
                .putBoolean(K_PLAYING, playing)
                .putBoolean(K_HIRES, hasTrack && hires)
                .putInt(K_POS, position.coerceAtLeast(0))
                .putInt(K_DUR, duration.coerceAtLeast(0))
                .apply()

            if (sameTrack && !artChanged) refreshPlayback(app, playing, position, duration) else renderAll(app)
        }
    }

    /** About once a second while playing: only the 4x2 progress bar changes. */
    fun refreshProgress(context: Context, position: Int, duration: Int) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, G6Widget4x2Provider::class.java))
        if (ids.isEmpty()) return
        val total = duration.coerceAtLeast(1)
        val views = RemoteViews(app.packageName, R.layout.g6_widget_4x2_compact).apply {
            setProgressBar(R.id.w_progress, total, position.coerceIn(0, total), false)
        }
        try { manager.partiallyUpdateAppWidget(ids, views) } catch (e: Exception) { Log.w(TAG, "progress update failed", e) }
    }

    // ---- called by the providers ------------------------------------------------------------

    internal fun handleAction(action: String) {
        // Launchers can deliver a tap twice; accept the first toggle at once, drop near-simultaneous duplicates.
        if (action == "toggle") {
            val now = SystemClock.elapsedRealtime()
            val accepted = synchronized(lock) {
                val previous = lastToggleAtMs
                if (previous != Long.MIN_VALUE && now - previous < TOGGLE_DEBOUNCE_MS) false
                else { lastToggleAtMs = now; true }
            }
            if (!accepted) return
        }
        MainActivity.notificationAction(action)
    }

    internal fun onBoot(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            // After a restart nothing is playing, whatever was saved last.
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_PLAYING, false).apply()
            renderAll(app)
        }
    }

    /** Draws both widgets completely from the saved state (new widget, resize, new track, new cover, boot). */
    internal fun renderAll(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val manager = AppWidgetManager.getInstance(app)
            val ids4 = manager.getAppWidgetIds(ComponentName(app, G6Widget4x2Provider::class.java))
            val ids2 = manager.getAppWidgetIds(ComponentName(app, G6Widget2x2Provider::class.java))
            if (ids4.isEmpty() && ids2.isEmpty()) return

            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val hasTrack = prefs.getBoolean(K_HAS, false)
            val title = if (hasTrack) prefs.getString(K_TITLE, "").orEmpty().ifBlank { EMPTY_TITLE } else EMPTY_TITLE
            val artist = if (hasTrack) prefs.getString(K_ARTIST, "").orEmpty() else ""
            val album = if (hasTrack) prefs.getString(K_ALBUM, "").orEmpty() else ""
            val playing = hasTrack && prefs.getBoolean(K_PLAYING, false)
            val position = prefs.getInt(K_POS, 0)
            val duration = prefs.getInt(K_DUR, 0)

            if (ids4.isNotEmpty()) {
                val large = ids4.filter { isLarge(manager, it) }.toIntArray()
                val compact = ids4.filter { !isLarge(manager, it) }.toIntArray()
                if (compact.isNotEmpty()) {
                    val views = build4x2(app, R.layout.g6_widget_4x2_compact, title, artist, album, hasTrack, playing, position, duration)
                    try { manager.updateAppWidget(compact, views) } catch (e: Exception) { Log.w(TAG, "4x2 update failed", e) }
                }
                if (large.isNotEmpty()) {
                    val views = build4x2(app, R.layout.g6_widget_4x2_large, title, artist, album, hasTrack, playing, position, duration)
                    try { manager.updateAppWidget(large, views) } catch (e: Exception) { Log.w(TAG, "4x2 update failed", e) }
                }
            }
            if (ids2.isNotEmpty()) {
                val views = build2x2(app, playing)
                try { manager.updateAppWidget(ids2, views) } catch (e: Exception) { Log.w(TAG, "2x2 update failed", e) }
            }
        }
    }

    // ---- building the views -----------------------------------------------------------------

    private fun build4x2(
        context: Context, layout: Int, title: String, artist: String, album: String, hasTrack: Boolean,
        playing: Boolean, position: Int, duration: Int
    ): RemoteViews {
        val total = duration.coerceAtLeast(1)
        return RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.w_title, title)
            setTextViewText(R.id.w_artist, artist)
            setViewVisibility(R.id.w_artist, if (hasTrack && artist.isNotEmpty()) View.VISIBLE else View.GONE)
            setTextViewText(R.id.w_album, album)
            setViewVisibility(R.id.w_album, if (hasTrack && album.isNotEmpty()) View.VISIBLE else View.GONE)
            setImageViewResource(R.id.w_play, if (playing) R.drawable.g6_w_ic_pause else R.drawable.g6_w_ic_play)
            setProgressBar(R.id.w_progress, total, if (hasTrack) position.coerceIn(0, total) else 0, false)
            setOnClickPendingIntent(R.id.w_prev, actionIntent(context, G6Widget4x2Provider::class.java, 4201, "previous"))
            setOnClickPendingIntent(R.id.w_play, actionIntent(context, G6Widget4x2Provider::class.java, 4202, "toggle"))
            setOnClickPendingIntent(R.id.w_next, actionIntent(context, G6Widget4x2Provider::class.java, 4203, "next"))
            setOnClickPendingIntent(R.id.w_root, openAppIntent(context, 4204))
        }
    }

    private fun build2x2(context: Context, playing: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.g6_widget_2x2).apply {
            setImageViewResource(R.id.w_play, if (playing) R.drawable.g6_w_ic_pause else R.drawable.g6_w_ic_play)
            setOnClickPendingIntent(R.id.w_play, actionIntent(context, G6Widget2x2Provider::class.java, 2202, "toggle"))
            setOnClickPendingIntent(R.id.w_root, openAppIntent(context, 2204))
        }

    /** Play/pause only (the frequent case): partial update, no cover, no text. */
    private fun refreshPlayback(context: Context, playing: Boolean, position: Int, duration: Int) {
        val manager = AppWidgetManager.getInstance(context)
        val icon = if (playing) R.drawable.g6_w_ic_pause else R.drawable.g6_w_ic_play
        val ids4 = manager.getAppWidgetIds(ComponentName(context, G6Widget4x2Provider::class.java))
        if (ids4.isNotEmpty()) {
            val total = duration.coerceAtLeast(1)
            val views = RemoteViews(context.packageName, R.layout.g6_widget_4x2_compact).apply {
                setImageViewResource(R.id.w_play, icon)
                setProgressBar(R.id.w_progress, total, position.coerceIn(0, total), false)
            }
            try { manager.partiallyUpdateAppWidget(ids4, views) } catch (e: Exception) { Log.w(TAG, "4x2 playback update failed", e) }
        }
        val ids2 = manager.getAppWidgetIds(ComponentName(context, G6Widget2x2Provider::class.java))
        if (ids2.isNotEmpty()) {
            val views = RemoteViews(context.packageName, R.layout.g6_widget_2x2).apply { setImageViewResource(R.id.w_play, icon) }
            try { manager.partiallyUpdateAppWidget(ids2, views) } catch (e: Exception) { Log.w(TAG, "2x2 playback update failed", e) }
        }
    }

    private fun isLarge(manager: AppWidgetManager, id: Int): Boolean {
        val options: Bundle? = try { manager.getAppWidgetOptions(id) } catch (_: Exception) { null }
        return (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0) >= LARGE_MIN_HEIGHT_DP
    }

    // ---- intents ----------------------------------------------------------------------------

    private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun actionIntent(context: Context, provider: Class<*>, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, provider).putExtra(EXTRA_ACTION, action),
            pendingFlags()
        )

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingFlags()
        )

    /** The pre-2.14 widgets kept their state under other names; remove it once. */
    private fun cleanupOldWidgetData(context: Context, prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(K_OLD_CLEANED, false)) return
        try {
            context.deleteFile(OLD_ART_FILE)
            context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) { }
        prefs.edit().putBoolean(K_OLD_CLEANED, true).apply()
    }
}

/** Shared behaviour of both widget receivers; the two subclasses only exist so the launcher lists two widgets. */
open class G6WidgetProviderBase : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // A new or re-created widget is drawn from the last saved state, never from placeholder data.
        G6Widgets.renderAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, manager, id, newOptions)
        G6Widgets.renderAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            G6Widgets.onBoot(context)
            return
        }
        val action = intent.getStringExtra(G6Widgets.EXTRA_ACTION) ?: return
        G6Widgets.handleAction(action)
    }
}

class G6Widget4x2Provider : G6WidgetProviderBase()

class G6Widget2x2Provider : G6WidgetProviderBase()
