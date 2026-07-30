package com.sysadmin.lasstore.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sysadmin.lasstore.R
import com.sysadmin.lasstore.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class QueuedUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        ServiceLocator.init(applicationContext)
        val payload = QueuedUpdatePayload.from(params.extras)
        if (payload == null) {
            ServiceLocator.logger.warn("QueuedUpdate", "UIDT job missing payload")
            return false
        }
        val statusStore = ServiceLocator.queuedUpdateStatus
        val attempt = statusStore.beginAttempt(payload)
        if (attempt > QueuedUpdateStatusStore.MAX_ATTEMPTS) {
            statusStore.markFailed(
                payload,
                attempt,
                QueuedUpdateResult.Failed(
                    message = "Background update stopped after ${QueuedUpdateStatusStore.MAX_ATTEMPTS} attempts.",
                    kind = QueuedUpdateFailureKind.Timeout,
                ),
            )
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            createNotificationChannel()
            setNotification(
                params,
                NOTIFICATION_ID_BASE + (params.jobId % 10_000),
                buildNotification(payload),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }

        val job = scope.launch {
            val result = QueuedUpdateRunner.run(
                context = applicationContext,
                payload = payload,
                useInstallConstraints = true,
                onProgress = { downloaded, total ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        updateTransferredNetworkBytes(params, downloaded, 0L)
                        if (total > 0L) updateEstimatedNetworkBytes(params, total, 0L)
                    }
                },
            )
            running.remove(params.jobId)
            val retry = result is QueuedUpdateResult.Failed &&
                result.retryable &&
                attempt < QueuedUpdateStatusStore.MAX_ATTEMPTS
            when (result) {
                QueuedUpdateResult.Installed -> statusStore.markInstalled(payload)
                is QueuedUpdateResult.Queued ->
                    statusStore.markAwaitingInstall(payload, attempt, result.sessionId)
                is QueuedUpdateResult.Failed -> {
                    if (retry) {
                        statusStore.markRetrying(payload, attempt, result)
                    } else {
                        statusStore.markFailed(payload, attempt, result)
                    }
                }
            }
            jobFinished(params, retry)
        }
        running[params.jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        running.values.forEach { it.cancel() }
        running.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background updates",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(payload: QueuedUpdatePayload): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent()
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Updating ${payload.displayName}")
            .setContentText("Downloading ${payload.tagName}")
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "background_updates"
        const val NOTIFICATION_ID_BASE = 780_000
    }
}
