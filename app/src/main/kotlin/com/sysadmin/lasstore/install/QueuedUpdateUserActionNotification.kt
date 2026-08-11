package com.sysadmin.lasstore.install

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sysadmin.lasstore.R

internal object QueuedUpdateUserActionNotification {
    private const val CHANNEL_ID = "queued_update_user_action"
    private const val NOTIFICATION_ID_BASE = 790_000

    fun show(context: Context, payload: QueuedUpdatePayload, confirmation: Intent?) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Background update actions",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val actionIntent = confirmation?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val pending = actionIntent?.let {
            PendingIntent.getActivity(
                appContext,
                notificationId(payload),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Confirm ${payload.displayName} update")
            .setContentText("Tap to finish the Android install confirmation.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(notificationId(payload), notification) }
    }

    fun cancel(context: Context, payload: QueuedUpdatePayload) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(notificationId(payload))
    }

    private fun notificationId(payload: QueuedUpdatePayload): Int =
        NOTIFICATION_ID_BASE + (payload.workName.hashCode() and 0x7fff)
}
