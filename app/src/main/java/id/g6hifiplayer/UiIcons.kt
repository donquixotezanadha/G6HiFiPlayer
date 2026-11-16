package id.g6hifiplayer

import android.content.Context

/**
 * Hifi 2.68: the sharp icons (square-cornered, thinner lines) belong to the Classic UI only; Adaptive keeps the original rounded icons.
 * The flag is read from the same preference the Classic mode is stored in; every entry point (activity, widgets, notification) refreshes it first.
 */
internal object UiIcons {
    @Volatile var classic: Boolean = true   // Hifi 2.73 classic: pure Classic, always on

    fun refresh(context: Context) {
        for (name in arrayOf("id.g6hifiplayer.MainActivity", "MainActivity")) {
            val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (p.contains("ui_classic")) { classic = true; return }
        }
        classic = true
    }

    fun pick(id: Int): Int = if (!classic) id else when (id) {
        R.drawable.g6_w_ic_next -> R.drawable.g6_w_ic_next_sharp
        R.drawable.g6_w_ic_pause -> R.drawable.g6_w_ic_pause_sharp
        R.drawable.g6_w_ic_pause_2x2 -> R.drawable.g6_w_ic_pause_2x2_sharp
        R.drawable.g6_w_ic_play -> R.drawable.g6_w_ic_play_sharp
        R.drawable.g6_w_ic_play_2x2 -> R.drawable.g6_w_ic_play_2x2_sharp
        R.drawable.g6_w_ic_prev -> R.drawable.g6_w_ic_prev_sharp
        R.drawable.ic_close_24 -> R.drawable.ic_close_24_sharp
        R.drawable.ic_next_24 -> R.drawable.ic_next_24_sharp
        R.drawable.ic_next_s -> R.drawable.ic_next_s_sharp
        R.drawable.ic_pause_24 -> R.drawable.ic_pause_24_sharp
        R.drawable.ic_pause_s -> R.drawable.ic_pause_s_sharp
        R.drawable.ic_play_24 -> R.drawable.ic_play_24_sharp
        R.drawable.ic_play_s -> R.drawable.ic_play_s_sharp
        R.drawable.ic_previous_24 -> R.drawable.ic_previous_24_sharp
        R.drawable.ic_previous_s -> R.drawable.ic_previous_s_sharp
        R.drawable.ic_gear -> R.drawable.ic_gear_sharp
        R.drawable.ic_row_sun -> R.drawable.ic_row_sun_sharp
        R.drawable.ic_row_moon -> R.drawable.ic_row_moon_sharp
        R.drawable.ic_row_queue -> R.drawable.ic_row_queue_sharp
        R.drawable.ic_row_album -> R.drawable.ic_row_album_sharp
        else -> id
    }
}
