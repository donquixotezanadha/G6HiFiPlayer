package id.g6hifiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Deliver notification transport actions even if Android has reclaimed the activity.
 * Previously the receiver only called MainActivity.notificationAction(), which silently
 * dropped the action when activeInstance was null (for example after background recreation).
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("notification_action") ?: return
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("notification_action", action)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(activityIntent)
    }
}
