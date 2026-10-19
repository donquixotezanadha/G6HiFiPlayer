package id.g6hifiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes transport commands straight to the playback service; no Activity lookup is involved. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("notification_action") ?: return
        PlaybackKeepAliveService.dispatchAction(context.applicationContext, action)
    }
}
