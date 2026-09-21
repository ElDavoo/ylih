package it.eldavo.ylih.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import it.eldavo.ylih.MainActivity
import it.eldavo.ylih.R
import it.eldavo.ylih.data.AutoBackupError

object Notifications {
    const val CHANNEL_TRACKING = "tracking"
    const val ID_TRACKING = 1
    const val CHANNEL_BACKUPS = "backups"
    const val ID_BACKUP_FAILED = 2

    /**
     * Creates the tracking channel at process start.
     *
     * This used to run in [TrackingService.onCreate], on the line above the `startForeground`
     * that needs it. With the notification permission denied, creating the channel there
     * silently did nothing, so `startForeground` threw a moment later; the service caught that
     * and stopped itself, and every retry repeated the same two calls with the same result —
     * detailed tracking died the instant it was switched on.
     *
     * Creating it here fixes that: the service starts and runs whether or not the notification
     * may be drawn. It also moves the permission ask to the switch that turns detailed tracking
     * on rather than to first run, so that ask is now about visibility, not about whether
     * tracking works.
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

    /**
     * Says automatic backups have stopped working. The whole feature exists for someone who
     * doesn't open the app for months, so a status line in settings alone would be read too late.
     *
     * Its own channel, created here rather than at startup: nothing about it has to exist before
     * the first failure, and a user who silences it keeps the tracking notification.
     */
    fun notifyBackupFailed(context: Context, error: AutoBackupError) {
        // Without the permission there's nothing to post; the settings screen still says it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_BACKUPS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_BACKUPS,
                    context.getString(R.string.channel_backups),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            // Not 0, the tracking notification's: extras don't tell PendingIntents apart, so the
            // same code would have FLAG_UPDATE_CURRENT rewrite that one to open settings too.
            ID_BACKUP_FAILED,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(
            when (error) {
                AutoBackupError.ACCESS_LOST -> R.string.settings_auto_backup_access_lost
                AutoBackupError.WRITE_FAILED -> R.string.settings_auto_backup_write_failed
            },
        )
        manager.notify(
            ID_BACKUP_FAILED,
            NotificationCompat.Builder(context, CHANNEL_BACKUPS)
                .setSmallIcon(R.drawable.ic_headphones)
                .setContentTitle(context.getString(R.string.notification_backup_failed))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build(),
        )
    }

    /** A backup went through, so whatever the last failure said no longer holds. */
    fun cancelBackupFailed(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_BACKUP_FAILED)
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
