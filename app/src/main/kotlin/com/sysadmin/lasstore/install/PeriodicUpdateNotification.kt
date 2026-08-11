package com.sysadmin.lasstore.install

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sysadmin.lasstore.R

internal object PeriodicUpdateNotification {
    private const val CHANNEL_ID = "periodic_update_results"
    private const val NOTIFICATION_ID = 795_001

    fun show(context: Context, queuedCount: Int, availableCount: Int = 0) {
        if (queuedCount < 1 && availableCount < 1) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.periodic_update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = launchIntent?.let {
            PendingIntent.getActivity(
                appContext,
                NOTIFICATION_ID,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val content = when {
            queuedCount > 0 && availableCount > 0 -> appContext.getString(
                R.string.periodic_updates_mixed_body,
                queuedCount,
                availableCount,
            )
            queuedCount > 0 -> appContext.resources.getQuantityString(
                R.plurals.periodic_updates_queued_body,
                queuedCount,
                queuedCount,
            )
            else -> appContext.resources.getQuantityString(
                R.plurals.periodic_updates_available_body,
                availableCount,
                availableCount,
            )
        }
        val title = if (queuedCount > 0) {
            appContext.getString(R.string.periodic_updates_queued_title)
        } else {
            appContext.getString(R.string.periodic_updates_available_title)
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}
