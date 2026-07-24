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

    fun ensureChannel(context: Context) {
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
