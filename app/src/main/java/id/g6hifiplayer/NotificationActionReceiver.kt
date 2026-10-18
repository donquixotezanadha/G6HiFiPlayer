package id.g6hifiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Notification actions are broadcasts so transport controls do not launch the player Activity.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("notification_action")?.let(MainActivity::notificationAction)
    }
}
