package com.sysadmin.lasstore.install

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sysadmin.lasstore.data.ServiceLocator

class QueuedUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val payload = QueuedUpdatePayload.from(inputData) ?: return Result.failure()
        ServiceLocator.init(applicationContext)
        val statusStore = ServiceLocator.queuedUpdateStatus
        if (statusStore.shouldDeferForRateLimit(payload)) return Result.retry()
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
            return Result.failure()
        }
        val result = QueuedUpdateRunner.run(
            context = applicationContext,
            payload = payload,
            useInstallConstraints = false,
            onProgress = { downloaded, total ->
                setProgressAsync(
                    workDataOf(
                        "downloaded" to downloaded,
                        "total" to total,
                    )
                )
            },
        )
        return when (result) {
            QueuedUpdateResult.Installed -> {
                statusStore.markInstalled(payload)
                Result.success()
            }
            is QueuedUpdateResult.Queued -> {
                statusStore.markAwaitingInstall(payload, attempt, result.sessionId)
                Result.success()
            }
            is QueuedUpdateResult.Failed -> {
                if (result.retryable && attempt < QueuedUpdateStatusStore.MAX_ATTEMPTS) {
                    statusStore.markRetrying(payload, attempt, result)
                    Result.retry()
                } else {
                    statusStore.markFailed(payload, attempt, result)
                    Result.failure()
                }
            }
        }
    }
}
