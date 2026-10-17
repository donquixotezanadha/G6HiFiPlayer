package id.g6hifiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the notification's Close player action without launching MainActivity.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("notification_action") ?: return
        if (action == "dismiss") MainActivity.notificationAction(action)
    }
}
