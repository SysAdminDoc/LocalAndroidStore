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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.BackoffPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sysadmin.lasstore.data.Logger
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.domain.AppInfo
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

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
    internal val scheduleUidtOverride: ((QueuedUpdatePayload) -> Boolean)? = null,
    internal val enqueueWorkerOverride: ((QueuedUpdatePayload) -> Unit)? = null,
    internal val cancelWorkOverride: ((String) -> Unit)? = null,
) {
    private val jobIdPrefs = context.getSharedPreferences(JOB_ID_PREFS, Context.MODE_PRIVATE)
    private val jobIdLock = Any()

    suspend fun enqueue(info: AppInfo): Boolean {
        return enqueueInternal(info, useUserInitiatedTransport = true)
    }

    /** Enqueue a previously persisted payload without changing its generation identity. */
    suspend fun enqueue(payload: QueuedUpdatePayload): Boolean {
        return enqueueInternal(payload, useUserInitiatedTransport = true)
    }

    /**
     * Queue a candidate discovered by the periodic checker.
     *
     * A periodic worker is not a user-initiated transfer, so it must stay on WorkManager even on
     * API 34+. The worker still uses the same generation, digest, APK inspection, signer, and
     * PackageInstaller reconciliation path as a manually queued update.
     */
    suspend fun enqueuePeriodic(info: AppInfo): Boolean {
        return enqueueInternal(info, useUserInitiatedTransport = false)
    }

    suspend fun enqueuePeriodic(payload: QueuedUpdatePayload): Boolean {
        return enqueueInternal(payload, useUserInitiatedTransport = false)
    }

    private suspend fun enqueueInternal(
        info: AppInfo,
        useUserInitiatedTransport: Boolean,
    ): Boolean = enqueueInternal(
        payload = QueuedUpdatePayload.from(info),
        useUserInitiatedTransport = useUserInitiatedTransport,
    )

    private suspend fun enqueueInternal(
        payload: QueuedUpdatePayload,
        useUserInitiatedTransport: Boolean,
    ): Boolean {
        val statusStore = com.sysadmin.lasstore.data.ServiceLocator.queuedUpdateStatus
        statusStore.awaitLoaded()
        try {
            statusStore.get(payload)
                ?.packageInstallerSessionId
                ?.let(com.sysadmin.lasstore.data.ServiceLocator.installer::abandonSession)
            statusStore.markQueued(payload)
            if (
                useUserInitiatedTransport &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                backgroundUpdateTransportForApi() == BackgroundUpdateTransport.UserInitiatedJob
            ) {
                val scheduled = runCatching { scheduleUidt(payload) }
                    .onFailure {
                        logger.warn(
                            "QueuedUpdate",
                            "UIDT schedule failed for ${payload.owner}/${payload.repo} " +
                                "(${it::class.simpleName ?: "unknown"}); falling back to WorkManager",
                        )
                    }
                    .getOrDefault(false)
                if (scheduled) return true
                logger.warn(
                    "QueuedUpdate",
                    "UIDT schedule returned failure for ${payload.owner}/${payload.repo}; " +
                        "falling back to WorkManager",
                )
            }
            enqueueWorker(payload)
            return true
        } catch (throwable: Throwable) {
            logger.warn(
                "QueuedUpdate",
                "Could not schedule ${payload.owner}/${payload.repo} " +
                    "(${throwable::class.simpleName ?: "unknown"})",
            )
            cancelScheduledWork(payload)
            runCatching {
                statusStore.markSchedulingFailed(
                    payload,
                    "Background update could not be scheduled. Try again.",
                )
            }.onFailure {
                logger.warn(
                    "QueuedUpdate",
                    "Could not persist scheduling failure for ${payload.owner}/${payload.repo} " +
                        "(${it::class.simpleName ?: "unknown"})",
                )
            }
            return false
        }
    }

    /** Schedule the durable 24-hour catalog/update check. WorkManager persists this across reboot. */
    fun schedulePeriodicCheck() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildPeriodicCheckRequest(),
        )
        logger.info("PeriodicUpdate", "Scheduled the 24-hour constrained catalog check")
    }

    fun cancelPeriodicCheck() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        logger.info("PeriodicUpdate", "Cancelled the periodic catalog check")
    }

    internal fun buildPeriodicCheckRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<PeriodicUpdateCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.MINUTES,
            )
            .build()

    suspend fun cancel(info: AppInfo): Boolean {
        val statusStore = com.sysadmin.lasstore.data.ServiceLocator.queuedUpdateStatus
        statusStore.awaitLoaded()
        val current = statusStore.get(info.sourceKey, info.owner, info.repo)
        val payload = QueuedUpdatePayload.from(
            info = info,
            generationId = current?.generationId ?: QueuedUpdatePayload.newGenerationId(),
        )
        try {
            statusStore
                .get(payload)
                ?.packageInstallerSessionId
                ?.let(com.sysadmin.lasstore.data.ServiceLocator.installer::abandonSession)
            if (!cancelScheduledWork(payload)) {
                throw IllegalStateException("one or more background transports rejected cancellation")
            }
            statusStore.markCancelled(payload)
            QueuedUpdateUserActionNotification.cancel(context, payload)
            logger.info("QueuedUpdate", "Cancelled queued update for ${payload.owner}/${payload.repo}")
            return true
        } catch (throwable: Throwable) {
            logger.warn(
                "QueuedUpdate",
                "Could not cancel ${payload.owner}/${payload.repo} " +
                    "(${throwable::class.simpleName ?: "unknown"})",
            )
            runCatching {
                statusStore.markNeedsReschedule(
                    payload,
                    "Could not cancel the background update. Try again.",
                )
            }.onFailure {
                logger.warn(
                    "QueuedUpdate",
                    "Could not persist cancellation failure for ${payload.owner}/${payload.repo} " +
                        "(${it::class.simpleName ?: "unknown"})",
                )
            }
            return false
        }
    }

    /** Restore pending queue work after JobScheduler loses non-persisted UIDT jobs at reboot. */
    suspend fun reconcilePersistedWork() {
        val statusStore = ServiceLocator.queuedUpdateStatus
        statusStore.awaitLoaded()
        statusStore.statuses.value
            .filter { it.isPending }
            .forEach { status ->
                val payload = status.queuedPayload
                if (payload == null) {
                    logger.warn(
                        "QueuedUpdate",
                        "Queued ${status.owner}/${status.repo} needs rescheduling; payload is unavailable",
                    )
                    return@forEach
                }
                when (QueuedInstallReconciler.reconcile(context, payload)) {
                    QueuedInstallReconciliation.Installed,
                    is QueuedInstallReconciliation.AwaitingSession -> return@forEach
                    QueuedInstallReconciliation.NotApplicable -> Unit
                }
                if (!statusStore.isCurrent(payload)) return@forEach
                if (statusStore.get(payload)?.phase == QueuedUpdatePhase.AwaitingUserAction) {
                    return@forEach
                }
                if (hasScheduledWork(payload)) return@forEach
                if (!scheduleExistingPayload(payload)) {
                    statusStore.markNeedsReschedule(
                        payload,
                        "Background update needs rescheduling. Tap retry from the catalog.",
                    )
                }
            }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUidt(payload: QueuedUpdatePayload): Boolean {
        scheduleUidtOverride?.let { return it(payload) }
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
        enqueueWorkerOverride?.let {
            it(payload)
            return
        }
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

    private fun cancelScheduledWork(payload: QueuedUpdatePayload): Boolean {
        var succeeded = true
        runCatching {
            if (backgroundUpdateTransportForApi() == BackgroundUpdateTransport.UserInitiatedJob) {
                context.getSystemService(JobScheduler::class.java).cancel(jobIdFor(payload))
            }
        }.onFailure { succeeded = false }
        runCatching {
            cancelWorkOverride?.invoke(payload.workName)
                ?: WorkManager.getInstance(context).cancelUniqueWork(payload.workName)
        }.onFailure { succeeded = false }
        runCatching { releaseJobId(payload) }.onFailure { succeeded = false }
        return succeeded
    }

    private fun scheduleExistingPayload(payload: QueuedUpdatePayload): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            runCatching { scheduleUidt(payload) }
                .onFailure { logger.warn("QueuedUpdate", "Could not restore UIDT work: ${it.message}") }
                .getOrDefault(false)
        ) {
            return true
        }
        return runCatching {
            enqueueWorker(payload)
            true
        }.onFailure {
            logger.warn("QueuedUpdate", "Could not restore WorkManager work: ${it.message}")
        }.getOrDefault(false)
    }

    private fun hasScheduledWork(payload: QueuedUpdatePayload): Boolean {
        val uidtScheduled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            runCatching {
                context.getSystemService(JobScheduler::class.java)
                    .getPendingJob(jobIdFor(payload)) != null
            }.getOrDefault(false)
        if (uidtScheduled) return true
        return runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(payload.workName)
                .get(10, SECONDS)
                .any { work ->
                    work.state == androidx.work.WorkInfo.State.ENQUEUED ||
                        work.state == androidx.work.WorkInfo.State.BLOCKED ||
                        work.state == androidx.work.WorkInfo.State.RUNNING
                }
        }.getOrDefault(false)
    }

    internal fun jobIdFor(payload: QueuedUpdatePayload): Int =
        synchronized(jobIdLock) {
            val key = jobIdKey(payload.workName)
            jobIdPrefs.getInt(key, INVALID_JOB_ID)
                .takeIf { it in JOB_ID_BASE until (JOB_ID_BASE + JOB_ID_RANGE) }
                ?: allocateJobIdLocked(payload.workName, key)
        }

    private fun allocateJobIdLocked(workName: String, key: String): Int {
        val used = jobIdPrefs.all
            .filterKeys { it.startsWith(JOB_ID_PREFIX) }
            .values
            .mapNotNull { it as? Int }
            .toSet()
        val start = (workName.hashCode() and Int.MAX_VALUE) % JOB_ID_RANGE
        for (offset in 0 until JOB_ID_RANGE) {
            val candidate = JOB_ID_BASE + ((start + offset) % JOB_ID_RANGE)
            if (candidate !in used) {
                check(
                    jobIdPrefs.edit()
                        .putInt(key, candidate)
                        .commit(),
                ) { "Could not persist queued update JobScheduler ID" }
                return candidate
            }
        }
        error("No queued update JobScheduler IDs remain")
    }

    private fun releaseJobId(payload: QueuedUpdatePayload) {
        synchronized(jobIdLock) {
            jobIdPrefs.edit().remove(jobIdKey(payload.workName)).commit()
        }
    }

    private fun jobIdKey(workName: String): String = "$JOB_ID_PREFIX$workName"

    private companion object {
        const val JOB_ID_BASE = 420_000
        const val JOB_ID_RANGE = 50_000
        const val JOB_ID_PREFS = "queued_update_job_ids"
        const val JOB_ID_PREFIX = "job."
        const val INVALID_JOB_ID = -1
        const val PERIODIC_WORK_NAME = "periodic-catalog-update-check"
    }
}
