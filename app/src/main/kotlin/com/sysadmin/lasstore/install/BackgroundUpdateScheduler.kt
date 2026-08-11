package com.sysadmin.lasstore.install

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.BackoffPolicy
import androidx.work.WorkManager
import com.sysadmin.lasstore.data.Logger
import com.sysadmin.lasstore.domain.AppInfo
import java.util.concurrent.TimeUnit

internal enum class BackgroundUpdateTransport {
    WorkManager,
    UserInitiatedJob,
}

internal fun backgroundUpdateTransportForApi(
    sdkInt: Int = Build.VERSION.SDK_INT,
): BackgroundUpdateTransport =
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        BackgroundUpdateTransport.UserInitiatedJob
    } else {
        BackgroundUpdateTransport.WorkManager
    }

class BackgroundUpdateScheduler(
    private val context: Context,
    private val logger: Logger,
) {
    fun enqueue(info: AppInfo): Boolean {
        val payload = QueuedUpdatePayload.from(info)
        val statusStore = com.sysadmin.lasstore.data.ServiceLocator.queuedUpdateStatus
        statusStore.get(payload)
            ?.packageInstallerSessionId
            ?.let(com.sysadmin.lasstore.data.ServiceLocator.installer::abandonSession)
        statusStore.markQueued(payload)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            backgroundUpdateTransportForApi() == BackgroundUpdateTransport.UserInitiatedJob
        ) {
            val scheduled = runCatching { scheduleUidt(payload) }
                .onFailure {
                    logger.warn(
                        "QueuedUpdate",
                        "UIDT schedule failed for ${payload.owner}/${payload.repo}: ${it.message}",
                    )
                }
                .getOrDefault(false)
            if (scheduled) return true
            logger.warn("QueuedUpdate", "UIDT schedule failed for ${payload.owner}/${payload.repo}; falling back to WorkManager")
        }
        enqueueWorker(payload)
        return true
    }

    fun cancel(info: AppInfo) {
        val statusStore = com.sysadmin.lasstore.data.ServiceLocator.queuedUpdateStatus
        val current = statusStore.get(info.sourceKey, info.owner, info.repo)
        val payload = QueuedUpdatePayload.from(
            info = info,
            generationId = current?.generationId ?: QueuedUpdatePayload.newGenerationId(),
        )
        statusStore
            .get(payload)
            ?.packageInstallerSessionId
            ?.let(com.sysadmin.lasstore.data.ServiceLocator.installer::abandonSession)
        if (backgroundUpdateTransportForApi() == BackgroundUpdateTransport.UserInitiatedJob) {
            context.getSystemService(JobScheduler::class.java).cancel(jobIdFor(payload))
        }
        WorkManager.getInstance(context).cancelUniqueWork(payload.workName)
        statusStore.markCancelled(payload)
        QueuedUpdateUserActionNotification.cancel(context, payload)
        logger.info("QueuedUpdate", "Cancelled queued update for ${payload.owner}/${payload.repo}")
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUidt(payload: QueuedUpdatePayload): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = buildUidtJobInfo(payload)
        val result = scheduler.schedule(job)
        logger.info("QueuedUpdate", "UIDT job schedule result=$result for ${payload.owner}/${payload.repo}")
        return result == JobScheduler.RESULT_SUCCESS
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    internal fun buildUidtJobInfo(payload: QueuedUpdatePayload): JobInfo {
        val component = ComponentName(context, QueuedUpdateJobService::class.java)
        val downloadBytes: Long = if (payload.assetSize > 0L) {
            payload.assetSize
        } else {
            JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
        }
        val network = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val jobBuilder = JobInfo.Builder(jobIdFor(payload), component)
            .setUserInitiated(true)
            .setRequiredNetwork(network)
            .setEstimatedNetworkBytes(downloadBytes, 0L)
            .setBackoffCriteria(
                TimeUnit.SECONDS.toMillis(30),
                JobInfo.BACKOFF_POLICY_EXPONENTIAL,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            jobBuilder.setTraceTag("las-update-${payload.owner}-${payload.repo}".take(32))
        }
        return jobBuilder
            .setExtras(payload.toPersistableBundle())
            .build()
    }

    private fun enqueueWorker(payload: QueuedUpdatePayload) {
        val request = OneTimeWorkRequestBuilder<QueuedUpdateWorker>()
            .setInputData(payload.toWorkData())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(payload.workName, ExistingWorkPolicy.REPLACE, request)
        logger.info("QueuedUpdate", "WorkManager update queued for ${payload.owner}/${payload.repo}")
    }

    private fun jobIdFor(payload: QueuedUpdatePayload): Int =
        JOB_ID_BASE + ((payload.workName.hashCode() and Int.MAX_VALUE) % JOB_ID_RANGE)

    private companion object {
        const val JOB_ID_BASE = 420_000
        const val JOB_ID_RANGE = 50_000
    }
}
