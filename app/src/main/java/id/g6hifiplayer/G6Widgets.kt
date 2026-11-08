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
 *  - 4x1: compact rounded cover, title/artist and previous / play / next controls on a rounded dark card.
 *  - 2x2: big round cover with a small rounded-square play button overlapping its bottom-left; no card behind it.
 * Both are registered in the manifest (the old 2x2 never was). Dark look only, like the player.
 * Nothing runs in the background: the player pushes its state here (refresh / refreshProgress).
 */
object G6Widgets {
    const val EXTRA_ACTION = "g6_widget_action"

    private const val TAG = "G6Widgets"
    // Hifi 1.60: fixed sizes of the two text-based widgets (no settings): text scale against the layout bases and gaps in dp.
    private const val TEXT_WIDGET_SCALE = 1.25f   // 4x2 text widget: 26 / 15 / 12 sp x 1.25 = 32.5 / 19 / 15 sp
    private const val TEXT_WIDGET_GAP_DP = 22     // 4x2: gap between title, progress and play (Hifi 1.62: 16 -> 22; 1.61: 6 -> 16)
    private const val CARD_SCALE = 1.10f          // 4x1: 13 / 10 sp x 1.10
    private const val CARD_EDGE_DP = 6            // 4x1: gap between the card and the top / bottom of the widget area
    private const val CARD_SIDE_DP = 20           // 4x1: gap between the card and the left / right of the widget area (Hifi 1.67: 12 -> 20, card edge in line with the icons and the text widget)

    /**
     * Hifi 1.53: the player stores its theme with Activity.getPreferences(), whose file is named after the Activity class. The class is
     * "id.g6hifiplayer.MainActivity" but the app id is now "id.hifi.player", so Android no longer strips the package and the file is
     * "id.g6hifiplayer.MainActivity". The widgets still read the old file name "MainActivity", which does not exist in Hifi, so they always
     * thought the theme was Dark. They now read the real file first (and the old name as a fallback).
     */
    private fun isLightTheme(context: Context): Boolean {
        for (name in arrayOf("id.g6hifiplayer.MainActivity", "MainActivity")) {
            val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (p.contains("light_theme")) return p.getBoolean("light_theme", false)
        }
        return false
    }

    private const val PREFS = "g6_widgets"
    private const val ART_FILE = "g6_widgets_art.jpg"
    private const val OLD_PREFS = "g6_widget_state"
    private const val OLD_ART_FILE = "g6_widget_artwork.jpg"
    private const val EMPTY_TITLE = "NO MUSIC PLAYING"

    /** Saved cover: square, this many pixels. Small enough to travel inside RemoteViews on Android 9. */
    private const val THUMB_PX = 400
    private const val PLACEHOLDER_PX = 256
    /** Corner radius of the 4x1 cover as a fraction of its side (Build 2.17: a little rounder, like the picture). */
    private const val CORNER_FRACTION = 0.12f
    /** The 2x2 cover is a circle: radius = half the side. */
    private const val CIRCLE_FRACTION = 0.5f
    private const val COVER_WIDGET_FRACTION = 0.16f   // simple 4x1 widget cover
    /** Legacy sizing threshold retained for compatibility with older widget instances. */
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
    private const val K_RENDER_VERSION = "widget_render_version"
    private const val WIDGET_RENDER_VERSION = 254
    private const val PROGRESS_CACHE_INTERVAL_MS = 5_000L

    private val lock = Any()
    private var lastToggleAtMs = Long.MIN_VALUE
    private var lastProgressPersistAtMs = Long.MIN_VALUE

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
            val validTitle = title.isNotBlank() &&
                title != "G6HiFiPlayer" &&
                title != EMPTY_TITLE &&
                title != "Nothing playing"

            // Empty metadata is common during stop/close transitions. Keep the last real
            // track snapshot; only change the play state instead of erasing the cache.
            if (!validTitle && prefs.getBoolean(K_HAS, false)) {
                prefs.edit().putBoolean(K_PLAYING, false).apply()
                refreshPlayback(
                    app,
                    false,
                    prefs.getInt(K_POS, position).coerceAtLeast(0),
                    prefs.getInt(K_DUR, duration).coerceAtLeast(0)
                )
                return
            }

            val hasTrack = validTitle
            val forceFullRender = prefs.getInt(K_RENDER_VERSION, 0) != WIDGET_RENDER_VERSION
            val sameTrack = prefs.getBoolean(K_HAS, false) == hasTrack &&
                prefs.getBoolean(K_HIRES, false) == (hasTrack && hires) &&
                prefs.getString(K_TITLE, null) == title &&
                prefs.getString(K_ARTIST, null) == artist &&
                prefs.getString(K_ALBUM, null) == album

            // Cache one small cover per track. Invalidate a previous cover before trying
            // to write a new identity, so a failed decode/write can never show the old album.
            var artChanged = false
            if (hasTrack) {
                val identity = listOf(title, artist, album).joinToString("\u001F")
                val file = File(app.filesDir, ART_FILE)
                val cachedIdentity = prefs.getString(K_ART_ID, null)
                if (artwork != null && !artwork.isRecycled) {
                    if (cachedIdentity != identity || !file.exists()) {
                        val newTrackIdentity = cachedIdentity != identity
                        if (newTrackIdentity) file.delete()
                        val saved = saveThumb(artwork, file)
                        if (saved) {
                            prefs.edit().putString(K_ART_ID, identity).apply()
                            artChanged = true
                        } else {
                            // Remember which track the placeholder belongs to. Do not repeatedly
                            // force full widget renders if this track's artwork cannot be written.
                            file.delete()
                            prefs.edit().putString(K_ART_ID, identity).apply()
                            artChanged = newTrackIdentity
                        }
                    }
                } else if (cachedIdentity != identity) {
                    // Invalidate the old cover once, then keep the new track identity while its art is pending.
                    file.delete()
                    prefs.edit().putString(K_ART_ID, identity).apply()
                    artChanged = true
                }
            }

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

            if (forceFullRender || !sameTrack || artChanged) {
                renderAll(app)
                // Persist the layout generation only after issuing a full RemoteViews update.
                prefs.edit().putInt(K_RENDER_VERSION, WIDGET_RENDER_VERSION).apply()
            } else {
                refreshPlayback(app, playing, position, duration)
            }
        }
    }

    /** Update only the progress image; no ProgressBar widget is inflated by the launcher. */
    fun refreshProgress(context: Context, position: Int, duration: Int) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val shouldPersist = prefs.getBoolean(K_HAS, false) &&
                (lastProgressPersistAtMs == Long.MIN_VALUE ||
                    now - lastProgressPersistAtMs >= PROGRESS_CACHE_INTERVAL_MS ||
                    (duration > 0 && position >= duration - 1_500))
            if (shouldPersist) {
                prefs.edit()
                    .putInt(K_POS, position.coerceAtLeast(0))
                    .putInt(K_DUR, duration.coerceAtLeast(0))
                    .apply()
                lastProgressPersistAtMs = now
            }
        }
        val manager = AppWidgetManager.getInstance(app)
        val ids4 = manager.getAppWidgetIds(ComponentName(app, G6Widget4x1Provider::class.java))
        val idsText = manager.getAppWidgetIds(ComponentName(app, G6WidgetText4x2Provider::class.java))
        val idsBlur = manager.getAppWidgetIds(ComponentName(app, G6WidgetBlur4x1Provider::class.java))
        if (ids4.isEmpty() && idsText.isEmpty() && idsBlur.isEmpty()) return
        val progress = if (duration > 0) {
            ((position.coerceAtLeast(0).toLong() * 100L) / duration.toLong()).toInt().coerceIn(0, 100)
        } else 0
        val light = isLightTheme(app)
        val bitmap = progressBitmap(progress, light)
        try {
            if (ids4.isNotEmpty()) {
                val views = RemoteViews(app.packageName, R.layout.g6_widget_4x1).apply {
                    setImageViewBitmap(R.id.w_progress, bitmap)
                }
                manager.partiallyUpdateAppWidget(ids4, views)
            }
            if (idsText.isNotEmpty()) {
                val views = RemoteViews(app.packageName, R.layout.g6_widget_text_4x2).apply {
                    setImageViewBitmap(R.id.w_progress, bitmap)
                    setTextViewText(R.id.w_elapsed, timeLabel(position))
                    setTextViewText(R.id.w_duration, timeLabel(duration))
                }
                manager.partiallyUpdateAppWidget(idsText, views)
            }
            if (idsBlur.isNotEmpty()) {
                val views = RemoteViews(app.packageName, R.layout.g6_widget_blur_4x1).apply {
                    setImageViewBitmap(R.id.w_progress, bitmap)
                }
                manager.partiallyUpdateAppWidget(idsBlur, views)
            }
        } catch (e: Exception) {
            Log.w(TAG, "widget progress update failed", e)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
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
            val ids4 = manager.getAppWidgetIds(ComponentName(app, G6Widget4x1Provider::class.java))
            val ids2 = manager.getAppWidgetIds(ComponentName(app, G6Widget2x2Provider::class.java))
            val idsText = manager.getAppWidgetIds(ComponentName(app, G6WidgetText4x2Provider::class.java))
            val idsSimple = manager.getAppWidgetIds(ComponentName(app, G6WidgetSimple4x1Provider::class.java))
            val idsBlur = manager.getAppWidgetIds(ComponentName(app, G6WidgetBlur4x1Provider::class.java))
            if (ids4.isEmpty() && ids2.isEmpty() && idsText.isEmpty() && idsSimple.isEmpty() && idsBlur.isEmpty()) return

            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val hasTrack = prefs.getBoolean(K_HAS, false)
            val title = if (hasTrack) prefs.getString(K_TITLE, "").orEmpty().ifBlank { EMPTY_TITLE } else EMPTY_TITLE
            val artist = if (hasTrack) prefs.getString(K_ARTIST, "").orEmpty() else ""
            val album = if (hasTrack) prefs.getString(K_ALBUM, "").orEmpty() else ""
            val playing = hasTrack && prefs.getBoolean(K_PLAYING, false)
            val position = prefs.getInt(K_POS, 0)
            val duration = prefs.getInt(K_DUR, 0)
            val hires = hasTrack && prefs.getBoolean(K_HIRES, false)
            val light = isLightTheme(app)

            if (ids4.isNotEmpty()) {
                // Rounded-square cover for the 4x1. updateAppWidget has already copied the bitmap into the outgoing parcel.
                val cover = coverBitmap(app, hasTrack, CORNER_FRACTION, hires)
                try {
                    val views = build4x1(app, R.layout.g6_widget_4x1, title, artist, album, hasTrack, playing, position, duration, cover, light)
                    try { manager.updateAppWidget(ids4, views) } catch (e: Exception) { Log.w(TAG, "4x1 update failed", e) }
                } finally {
                    if (!cover.isRecycled) cover.recycle()
                }
            }
            if (ids2.isNotEmpty()) {
                // Round cover for the 2x2.
                val cover = coverBitmap(app, hasTrack, CIRCLE_FRACTION, hires)
                try {
                    val views = build2x2(app, playing, cover, light)
                    try { manager.updateAppWidget(ids2, views) } catch (e: Exception) { Log.w(TAG, "2x2 update failed", e) }
                } finally {
                    if (!cover.isRecycled) cover.recycle()
                }
            }
            for (id in idsBlur) {
                val bg = blurBackground(app, manager, id, hasTrack, light)
                try {
                    val views = buildBlur4x1(app, blurInnerHeightDp(manager, id), title, artist, hasTrack, playing, position, duration, bg, light)
                    try { manager.updateAppWidget(id, views) } catch (e: Exception) { Log.w(TAG, "blur 4x1 update failed", e) }
                } finally {
                    if (!bg.isRecycled) bg.recycle()
                }
            }
            if (idsSimple.isNotEmpty()) {
                val cover = coverBitmap(app, hasTrack, COVER_WIDGET_FRACTION, hires)
                try {
                    val views = buildSimple4x1(app, title, artist, album, hasTrack, cover, light)
                    try { manager.updateAppWidget(idsSimple, views) } catch (e: Exception) { Log.w(TAG, "simple 4x1 update failed", e) }
                } finally {
                    if (!cover.isRecycled) cover.recycle()
                }
            }
            if (idsText.isNotEmpty()) {
                val views = buildText4x2(app, title, artist, hasTrack, playing, position, duration, light)
                try { manager.updateAppWidget(idsText, views) } catch (e: Exception) { Log.w(TAG, "text 4x2 update failed", e) }
            }
        }
    }

    // ---- building the views -----------------------------------------------------------------

    private fun build4x1(
        context: Context, layout: Int, title: String, artist: String, album: String, hasTrack: Boolean,
        playing: Boolean, position: Int, duration: Int, cover: Bitmap, light: Boolean
    ): RemoteViews {
        return RemoteViews(context.packageName, layout).apply {
            val density = context.resources.displayMetrics.density
            val scale = CARD_SCALE
            val edge = (CARD_EDGE_DP * density).toInt()
            setViewPadding(R.id.w_root, (CARD_SIDE_DP * density).toInt(), edge, (CARD_SIDE_DP * density).toInt(), edge)   // Hifi 1.67
            setTextViewTextSize(R.id.w_title, android.util.TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            setTextViewTextSize(R.id.w_artist, android.util.TypedValue.COMPLEX_UNIT_SP, 10f * scale)
            setImageViewBitmap(R.id.w_art, cover)
            setTextViewText(R.id.w_title, title)
            setTextViewText(R.id.w_artist, artist)
            setTextColor(R.id.w_title, if (light) 0xFF171720.toInt() else 0xFFFFFFFF.toInt())
            setTextColor(R.id.w_artist, if (light) 0xFF484855.toInt() else 0xFFA4A4B2.toInt())
            setInt(R.id.w_card, "setBackgroundResource", if (light) R.drawable.g6_widget_bg_4x1_light else R.drawable.g6_widget_bg_4x1)
            val iconTint = if (light) 0xFF171720.toInt() else 0xFFFFFFFF.toInt()
            val buttonBg = if (light) R.drawable.g6_widget_btn_light else R.drawable.g6_widget_btn
            setInt(R.id.w_prev, "setBackgroundResource", buttonBg)
            setInt(R.id.w_play, "setBackgroundResource", buttonBg)
            setInt(R.id.w_next, "setBackgroundResource", buttonBg)
            setInt(R.id.w_prev, "setColorFilter", iconTint)
            setInt(R.id.w_play, "setColorFilter", iconTint)
            setInt(R.id.w_next, "setColorFilter", iconTint)
            setViewVisibility(R.id.w_artist, if (hasTrack && artist.isNotEmpty()) View.VISIBLE else View.GONE)
            setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_pause_s else R.drawable.ic_play_s)
            setInt(R.id.w_play, "setColorFilter", iconTint)
            setImageViewBitmap(R.id.w_progress, progressBitmap(if (duration > 0) ((position.coerceAtLeast(0).toLong() * 100L) / duration.toLong()).toInt().coerceIn(0, 100) else 0, light))
            setOnClickPendingIntent(R.id.w_prev, actionIntent(context, G6Widget4x1Provider::class.java, 4201, "previous"))
            setOnClickPendingIntent(R.id.w_play, actionIntent(context, G6Widget4x1Provider::class.java, 4202, "toggle"))
            setOnClickPendingIntent(R.id.w_next, actionIntent(context, G6Widget4x1Provider::class.java, 4203, "next"))
            setOnClickPendingIntent(R.id.w_root, openAppIntent(context, 4204))
        }
    }

    private fun build2x2(context: Context, playing: Boolean, cover: Bitmap, light: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.g6_widget_2x2).apply {
            setImageViewBitmap(R.id.w_art, cover)
            setImageViewResource(R.id.w_play, if (playing) R.drawable.g6_w_ic_pause_2x2 else R.drawable.g6_w_ic_play_2x2)
            setInt(R.id.w_play, "setBackgroundResource", if (light) R.drawable.g6_widget_btn_square_2x2_light else R.drawable.g6_widget_btn_square_2x2)
            setInt(R.id.w_play, "setColorFilter", if (light) 0xFF171720.toInt() else 0xFFFFFFFF.toInt())
            setOnClickPendingIntent(R.id.w_play, actionIntent(context, G6Widget2x2Provider::class.java, 2202, "toggle"))
            setOnClickPendingIntent(R.id.w_root, openAppIntent(context, 2204))
        }

    /** Build 3.08: transparent typography-only 4x2 widget; only its upper-right icon opens Now Playing. */
    private fun buildText4x2(
        context: Context, title: String, artist: String, hasTrack: Boolean,
        playing: Boolean, position: Int, duration: Int, light: Boolean
    ): RemoteViews = RemoteViews(context.packageName, R.layout.g6_widget_text_4x2).apply {
        val density = context.resources.displayMetrics.density
        val scale = TEXT_WIDGET_SCALE
        val gap = (TEXT_WIDGET_GAP_DP * density).toInt()
        setTextViewTextSize(R.id.w_title, android.util.TypedValue.COMPLEX_UNIT_SP, 26f * scale)   // Hifi 1.60
        setTextViewTextSize(R.id.w_artist, android.util.TypedValue.COMPLEX_UNIT_SP, 15f * scale)
        setTextViewTextSize(R.id.w_elapsed, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * scale)
        setTextViewTextSize(R.id.w_duration, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * scale)
        setViewPadding(R.id.w_gap1, 0, gap, 0, 0)
        setViewPadding(R.id.w_gap2, 0, gap, 0, 0)
        val ink = if (light) 0xFF171720.toInt() else 0xFFFFFFFF.toInt()
        val muted = if (light) 0xFF484855.toInt() else 0xFFA4A4B2.toInt()
        setTextViewText(R.id.w_title, title)
        setTextViewText(R.id.w_artist, artist)
        setViewVisibility(R.id.w_artist, if (hasTrack && artist.isNotEmpty()) View.VISIBLE else View.GONE)
        setTextColor(R.id.w_title, ink)
        setTextColor(R.id.w_artist, muted)
        setTextColor(R.id.w_elapsed, muted)
        setTextColor(R.id.w_duration, muted)
        setImageViewResource(R.id.w_open_player, R.drawable.ic_stat_music)
        setInt(R.id.w_open_player, "setColorFilter", ink)
        setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_pause_s else R.drawable.ic_play_s)
        setInt(R.id.w_play, "setColorFilter", ink)
        setTextViewText(R.id.w_elapsed, timeLabel(position))
        setTextViewText(R.id.w_duration, timeLabel(duration))
        setImageViewBitmap(R.id.w_progress, progressBitmap(
            if (duration > 0) ((position.coerceAtLeast(0).toLong() * 100L) / duration.toLong()).toInt().coerceIn(0, 100) else 0,
            light
        ))
        setOnClickPendingIntent(R.id.w_open_player, openPlayerIntent(context, 4301))
        setOnClickPendingIntent(R.id.w_play, actionIntent(context, G6WidgetText4x2Provider::class.java, 4302, "toggle"))
    }

    /** Hifi 1.72: simple 4x1 widget (no background): cover and song / artist / album text only; only a tap on the cover opens Now Playing. */
    private fun buildSimple4x1(
        context: Context, title: String, artist: String, album: String, hasTrack: Boolean, cover: Bitmap, light: Boolean
    ): RemoteViews = RemoteViews(context.packageName, R.layout.g6_widget_simple_4x1).apply {
        val ink = if (light) 0xFF171720.toInt() else 0xFFFFFFFF.toInt()
        val muted = if (light) 0xFF484855.toInt() else 0xFFA4A4B2.toInt()
        setImageViewBitmap(R.id.w_art, cover)
        setTextViewText(R.id.w_title, title)
        setTextViewText(R.id.w_artist, artist)
        setTextViewText(R.id.w_album, album)
        setViewVisibility(R.id.w_artist, if (hasTrack && artist.isNotEmpty()) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.w_album, if (hasTrack && album.isNotEmpty()) View.VISIBLE else View.GONE)
        setTextColor(R.id.w_title, ink)
        setTextColor(R.id.w_artist, muted)
        setTextColor(R.id.w_album, muted)
        setOnClickPendingIntent(R.id.w_art, openPlayerIntent(context, 4501))
    }

    /**
     * Hifi 1.75: background of the blur 4x1 widget, drawn to the widget's own size (from its launcher options): theme surface colour, the
     * blurred cover (same blur as the app, [BackdropBlur]) centre-cropped, the same flat 70% veil as the app, rounded 16dp.
     */
    private fun blurBackground(context: Context, manager: AppWidgetManager, id: Int, hasTrack: Boolean, light: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val options: Bundle? = try { manager.getAppWidgetOptions(id) } catch (_: Exception) { null }
        val widthDp = (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0).let { if (it > 0) it else 250 }
        val heightDp = (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0).let { if (it > 0) it else 70 }
        val cardW = (widthDp - 2 * CARD_SIDE_DP).coerceAtLeast(120f.toInt())
        val cardH = (heightDp - 2 * CARD_EDGE_DP).coerceAtLeast(40)
        val scale = (density * 0.6f).coerceAtMost(900f / cardW)
        val w = (cardW * scale).toInt().coerceAtLeast(64)
        val h = (cardH * scale).toInt().coerceAtLeast(32)
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(if (light) 0xFFF8F8FC.toInt() else 0xFF000000.toInt())
        if (hasTrack) {
            val file = File(context.filesDir, ART_FILE)
            val raw = if (file.exists()) try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null } else null
            val soft = raw?.let { BackdropBlur.soft(it) }
            raw?.recycle()
            if (soft != null) {
                val k = maxOf(w / soft.width.toFloat(), h / soft.height.toFloat())
                val sw = (w / k).toInt().coerceIn(1, soft.width)
                val sh = (h / k).toInt().coerceIn(1, soft.height)
                val sx = (soft.width - sw) / 2
                val sy = (soft.height - sh) / 2
                canvas.drawBitmap(soft, android.graphics.Rect(sx, sy, sx + sw, sy + sh), android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                soft.recycle()
            }
        }
        canvas.drawColor(if (light) 0xB3FFFFFF.toInt() else 0xB3000000.toInt())
        val rounded = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val radius = 16f * scale
        Canvas(rounded).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        flat.recycle()
        return rounded
    }

    /** Height (dp) of the content area inside the blur card: widget height - top/bottom gaps - card padding. */
    private fun blurInnerHeightDp(manager: AppWidgetManager, id: Int): Float {
        val options: Bundle? = try { manager.getAppWidgetOptions(id) } catch (_: Exception) { null }
        val heightDp = (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0).let { if (it > 0) it else 70 }
        return (heightDp - 2 * CARD_EDGE_DP - 18).coerceAtLeast(30).toFloat()
    }

    /** Rounded-square play background in the 2x2 button colours (theme), corners like that button (16dp on ~56dp = 0.28). */
    private fun playSquare(light: Boolean): Bitmap {
        val size = 160
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (light) 0xFFDADADD.toInt() else 0xFF1E1E1E.toInt()
        Canvas(bmp).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.28f, size * 0.28f, paint)
        return bmp
    }

    private fun buildBlur4x1(
        context: Context, innerHeightDp: Float, title: String, artist: String, hasTrack: Boolean,
        playing: Boolean, position: Int, duration: Int, background: Bitmap, light: Boolean
    ): RemoteViews = RemoteViews(context.packageName, R.layout.g6_widget_blur_4x1).apply {
        val ink = if (light) 0xFF171720.toInt() else 0xFFFFFFFF.toInt()
        val muted = if (light) 0xFF484855.toInt() else 0xFFA4A4B2.toInt()
        setImageViewBitmap(R.id.w_bg, background)
        setTextViewText(R.id.w_title, title)
        setTextViewText(R.id.w_artist, artist)
        setViewVisibility(R.id.w_artist, if (hasTrack && artist.isNotEmpty()) View.VISIBLE else View.GONE)
        setTextColor(R.id.w_title, ink)
        setTextColor(R.id.w_artist, muted)
        // Play button = 60% of the inner height: 20% of it left free above and below, icon padding 22% of the square.
        val density = context.resources.displayMetrics.density
        val pad = (innerHeightDp * 0.20f * density).toInt()
        val iconPad = (innerHeightDp * 0.60f * 0.22f * density).toInt()
        setViewPadding(R.id.w_play_box, 0, pad, 0, pad)
        setViewPadding(R.id.w_play, iconPad, iconPad, iconPad, iconPad)
        setImageViewBitmap(R.id.w_play_bg, playSquare(light))
        setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_pause_s else R.drawable.ic_play_s)
        setInt(R.id.w_play, "setColorFilter", ink)
        setImageViewBitmap(R.id.w_progress, progressBitmap(
            if (duration > 0) ((position.coerceAtLeast(0).toLong() * 100L) / duration.toLong()).toInt().coerceIn(0, 100) else 0,
            light
        ))
        setOnClickPendingIntent(R.id.w_play, actionIntent(context, G6WidgetBlur4x1Provider::class.java, 4601, "toggle"))
        setOnClickPendingIntent(R.id.w_play_bg, actionIntent(context, G6WidgetBlur4x1Provider::class.java, 4601, "toggle"))
        setOnClickPendingIntent(R.id.w_root, openAppIntent(context, 4602))
    }

    private fun timeLabel(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) {
            String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        } else {
            String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }
    }

    /** Play/pause only (the frequent case): partial update, no cover, no text. */
    private fun refreshPlayback(context: Context, playing: Boolean, position: Int, duration: Int) {
        val manager = AppWidgetManager.getInstance(context)
        val light = isLightTheme(context)
        val icon = if (playing) R.drawable.ic_pause_s else R.drawable.ic_play_s
        val widget2x2Icon = if (playing) R.drawable.g6_w_ic_pause_2x2 else R.drawable.g6_w_ic_play_2x2
        val iconTint = if (light) 0xFF171720.toInt() else 0xFFFFFFFF.toInt()
        val ids4 = manager.getAppWidgetIds(ComponentName(context, G6Widget4x1Provider::class.java))
        if (ids4.isNotEmpty()) {
            val views = RemoteViews(context.packageName, R.layout.g6_widget_4x1).apply {
                setImageViewResource(R.id.w_play, icon)
                val buttonBg = if (light) R.drawable.g6_widget_btn_light else R.drawable.g6_widget_btn
                setInt(R.id.w_prev, "setBackgroundResource", buttonBg)
                setInt(R.id.w_play, "setBackgroundResource", buttonBg)
                setInt(R.id.w_next, "setBackgroundResource", buttonBg)
                setInt(R.id.w_prev, "setColorFilter", iconTint)
                setInt(R.id.w_play, "setColorFilter", iconTint)
                setInt(R.id.w_next, "setColorFilter", iconTint)
            }
            try { manager.partiallyUpdateAppWidget(ids4, views) } catch (e: Exception) { Log.w(TAG, "4x1 playback update failed", e) }
        }
        val ids2 = manager.getAppWidgetIds(ComponentName(context, G6Widget2x2Provider::class.java))
        if (ids2.isNotEmpty()) {
            val views = RemoteViews(context.packageName, R.layout.g6_widget_2x2).apply {
                setImageViewResource(R.id.w_play, widget2x2Icon)
                setInt(R.id.w_play, "setBackgroundResource", if (light) R.drawable.g6_widget_btn_square_2x2_light else R.drawable.g6_widget_btn_square_2x2)
                setInt(R.id.w_play, "setColorFilter", iconTint)
            }
            try { manager.partiallyUpdateAppWidget(ids2, views) } catch (e: Exception) { Log.w(TAG, "2x2 playback update failed", e) }
        }
        val idsBlur = manager.getAppWidgetIds(ComponentName(context, G6WidgetBlur4x1Provider::class.java))
        if (idsBlur.isNotEmpty()) {
            val views = RemoteViews(context.packageName, R.layout.g6_widget_blur_4x1).apply {
                setImageViewResource(R.id.w_play, icon)
                setInt(R.id.w_play, "setColorFilter", if (light) 0xFF171720.toInt() else 0xFFFFFFFF.toInt())
            }
            try { manager.partiallyUpdateAppWidget(idsBlur, views) } catch (e: Exception) { Log.w(TAG, "blur 4x1 playback update failed", e) }
        }
        val idsText = manager.getAppWidgetIds(ComponentName(context, G6WidgetText4x2Provider::class.java))
        if (idsText.isNotEmpty()) {
            val views = RemoteViews(context.packageName, R.layout.g6_widget_text_4x2).apply {
                setImageViewResource(R.id.w_play, icon)
                setInt(R.id.w_play, "setColorFilter", iconTint)
            }
            try { manager.partiallyUpdateAppWidget(idsText, views) } catch (e: Exception) { Log.w(TAG, "text 4x2 playback update failed", e) }
        }
    }

    private fun isLarge(manager: AppWidgetManager, id: Int): Boolean {
        val options: Bundle? = try { manager.getAppWidgetOptions(id) } catch (_: Exception) { null }
        return (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0) >= LARGE_MIN_HEIGHT_DP
    }

    /** Draw a tiny accent progress line into a bitmap so the launcher only inflates an ordinary ImageView. */
    private fun progressBitmap(progress: Int, light: Boolean): Bitmap {
        val width = 400
        val height = 10
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = height / 2f
        paint.color = if (light) 0xB3D6D6DC.toInt() else 0xB35E5E66.toInt()   // Hifi 1.54: unplayed track = the player's (70% opacity)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
        val filled = (width * progress.coerceIn(0, 100) / 100f).coerceIn(0f, width.toFloat())
        if (filled > 0f) {
            paint.color = 0xFF6662C6.toInt()
            canvas.drawRoundRect(RectF(0f, 0f, filled, height.toFloat()), radius, radius, paint)
        }
        return bitmap
    }

    // ---- cover ------------------------------------------------------------------------------

    /** Cover of the playing track with corner radius = [fraction] of its side (0.5 = circle), or a dark placeholder when there is none. */
    private fun coverBitmap(context: Context, hasTrack: Boolean, fraction: Float, hires: Boolean): Bitmap {
        if (hasTrack) {
            val file = File(context.filesDir, ART_FILE)
            if (file.exists()) {
                val raw = try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
                if (raw != null) {
                    val rounded = roundCorners(raw, fraction)
                    if (rounded !== raw) raw.recycle()
                    if (hires) {
                        // Build 2.17: Hi-Res badge on the finished crop, so a round cover never cuts it off.
                        val badge = HiResBadgeDrawable(context, circle = fraction >= 0.5f)
                        badge.setBounds(0, 0, rounded.width, rounded.height)
                        badge.draw(Canvas(rounded))
                    }
                    return rounded
                }
            }
        }
        return placeholder(context, fraction)
    }

    private fun roundCorners(src: Bitmap, fraction: Float): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val radius = minOf(w, h) * fraction
        Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        return out
    }

    private fun placeholder(context: Context, fraction: Float): Bitmap {
        val size = PLACEHOLDER_PX
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val light = isLightTheme(context)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) 0xFFE9E9F0.toInt() else 0xFF2A2A33.toInt() }
        val radius = size * fraction
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        context.getDrawable(R.drawable.ic_stat_music)?.mutate()?.let { icon ->
            icon.setTint(if (light) 0xFF484855.toInt() else 0xFF8E8E98.toInt())
            val pad = (size * 0.28f).toInt()
            icon.setBounds(pad, pad, size - pad, size - pad)
            icon.draw(canvas)
        }
        return out
    }

    /** Centre-crops the cover to a square of THUMB_PX and stores it as JPEG. Returns false if anything went wrong. */
    private fun saveThumb(src: Bitmap, file: File): Boolean {
        return try {
            val side = minOf(src.width, src.height)
            if (side <= 0) return false
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val px = minOf(THUMB_PX, side)
            val scaled = if (side != px) Bitmap.createScaledBitmap(square, px, px, true) else square
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (scaled !== square) scaled.recycle()
            if (square !== src) square.recycle()
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not save widget cover", e)
            false
        }
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

    private fun openPlayerIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java)
                .putExtra("g6_open_player", true)
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
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // A package update must re-issue complete RemoteViews so old launcher layouts are replaced.
            G6Widgets.renderAll(context)
            return
        }
        val action = intent.getStringExtra(G6Widgets.EXTRA_ACTION) ?: return
        G6Widgets.handleAction(action)
    }
}

class G6Widget4x1Provider : G6WidgetProviderBase()

class G6Widget2x2Provider : G6WidgetProviderBase()

class G6WidgetText4x2Provider : G6WidgetProviderBase()

class G6WidgetSimple4x1Provider : G6WidgetProviderBase()

class G6WidgetBlur4x1Provider : G6WidgetProviderBase()
