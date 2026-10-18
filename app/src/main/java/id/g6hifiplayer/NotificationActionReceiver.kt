package id.g6hifiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification transport controls run without launching MainActivity. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("notification_action")?.let(MainActivity::notificationAction)
    }
}
