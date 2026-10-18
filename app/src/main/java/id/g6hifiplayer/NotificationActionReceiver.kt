package id.g6hifiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handle transport controls without bringing the Now Playing activity to the foreground.
 * The active player owns playback and dispatches the action on its main thread.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("notification_action") ?: return
        MainActivity.notificationAction(action)
    }
}
