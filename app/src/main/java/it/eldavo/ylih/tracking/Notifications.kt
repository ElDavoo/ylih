package it.eldavo.ylih.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import it.eldavo.ylih.MainActivity
import it.eldavo.ylih.R

object Notifications {
    const val CHANNEL_TRACKING = "tracking"
    const val ID_TRACKING = 1

    /**
     * Creates the tracking channel at process start.
     *
     * This used to run in [TrackingService.onCreate], on the line above the `startForeground`
     * that needs it. With the notification permission denied, creating the channel there was
     * observed to silently do nothing, so `startForeground` threw a moment later; the service
     * caught that and stopped itself, and every retry ran the same two calls to the same result
     * — detailed tracking died the instant it was switched on.
     *
     * Creating it here instead fixes that: the service starts and runs whether or not the
     * notification is allowed to be drawn. It also moves the permission ask to the switch that
     * turns detailed tracking on rather than to first run — that ask is now about visibility,
     * not about whether tracking works.
     */
    fun ensureChannelAtStartup(context: Context) {
        ensureChannel(context)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_TRACKING) != null) return
        val channel = NotificationChannel(
            CHANNEL_TRACKING,
            context.getString(R.string.channel_tracking),
            // Minimum importance: silent, collapsed, and dismissible on Android 13+.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.channel_tracking_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun trackingNotification(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
