package com.sysadmin.lasstore.install

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.domain.AppInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackgroundUpdateSchedulerTest {
    @Test
    fun workManagerEnqueueFailureDoesNotLeaveAQueuedRecord() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServiceLocator.init(context)
        clearStatuses(context)
        val info = appInfo("failure")
        val scheduler = BackgroundUpdateScheduler(
            context = context,
            logger = ServiceLocator.logger,
            enqueueWorkerOverride = { error("injected WorkManager failure") },
            cancelWorkOverride = {},
        )

        try {
            assertFalse(scheduler.enqueue(info))
            val status = ServiceLocator.queuedUpdateStatus.get(
                info.sourceKey,
                info.owner,
                info.repo,
            )
            assertEquals(QueuedUpdatePhase.Failed, status?.phase)
            assertEquals(QueuedUpdateFailureKind.Storage, status?.failureKind)
        } finally {
            clearStatuses(context)
        }
    }

    @Test
    fun successfulFallbackLeavesOneQueuedRecord() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServiceLocator.init(context)
        clearStatuses(context)
        val info = appInfo("success")
        val scheduler = BackgroundUpdateScheduler(
            context = context,
            logger = ServiceLocator.logger,
            enqueueWorkerOverride = {},
            cancelWorkOverride = {},
        )

        try {
            assertTrue(scheduler.enqueue(info))
            val status = ServiceLocator.queuedUpdateStatus.get(
                info.sourceKey,
                info.owner,
                info.repo,
            )
            assertEquals(QueuedUpdatePhase.Queued, status?.phase)
            assertEquals(1, ServiceLocator.queuedUpdateStatus.statuses.value.count {
                it.sourceKey == info.sourceKey && it.owner == info.owner && it.repo == info.repo
            })
        } finally {
            clearStatuses(context)
        }
    }

    @Test
    fun cancellationTransportFailureKeepsTheRecordRetryable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServiceLocator.init(context)
        clearStatuses(context)
        val info = appInfo("cancel")
        val payload = QueuedUpdatePayload.from(info)
        ServiceLocator.queuedUpdateStatus.markQueued(payload)
        val scheduler = BackgroundUpdateScheduler(
            context = context,
            logger = ServiceLocator.logger,
            cancelWorkOverride = { error("injected cancellation failure") },
        )

        try {
            assertFalse(scheduler.cancel(info))
            val status = ServiceLocator.queuedUpdateStatus.get(
                info.sourceKey,
                info.owner,
                info.repo,
            )
            assertEquals(QueuedUpdatePhase.Queued, status?.phase)
            assertTrue(status?.message.orEmpty().contains("Could not cancel"))
        } finally {
            clearStatuses(context)
        }
    }

    @Test
    fun periodicCheckUsesTheRequiredConstrainedTwentyFourHourRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServiceLocator.init(context)
        val request = BackgroundUpdateScheduler(context, ServiceLocator.logger)
            .buildPeriodicCheckRequest()

        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertTrue(request.workSpec.constraints.requiresStorageNotLow())
        assertEquals(
            java.util.concurrent.TimeUnit.HOURS.toMillis(24),
            request.workSpec.intervalDuration,
        )
        assertEquals(PeriodicUpdateCheckWorker::class.java.name, request.workSpec.workerClassName)
    }

    @Test
    fun periodicEnqueueNeverUsesUserInitiatedTransport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServiceLocator.init(context)
        clearStatuses(context)
        val info = appInfo("periodic")
        val scheduler = BackgroundUpdateScheduler(
            context = context,
            logger = ServiceLocator.logger,
            scheduleUidtOverride = { error("periodic checks must not schedule UIDT work") },
            enqueueWorkerOverride = {},
            cancelWorkOverride = {},
        )

        try {
            assertTrue(scheduler.enqueuePeriodic(info))
            assertEquals(
                QueuedUpdatePhase.Queued,
                ServiceLocator.queuedUpdateStatus.get(info.sourceKey, info.owner, info.repo)?.phase,
            )
        } finally {
            clearStatuses(context)
        }
    }

    private fun clearStatuses(context: Context) {
        context.getSharedPreferences("queued_update_status", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun appInfo(repo: String) = AppInfo(
        owner = "owner",
        repo = repo,
        sourceKey = "github",
        sourceLabel = "GitHub",
        displayName = repo,
        description = null,
        stars = 0,
        htmlUrl = "https://github.com/owner/$repo",
        tagName = "v1",
        versionName = "1",
        versionCode = null,
        applicationId = "com.example.$repo",
        asset = GhAsset(
            name = "$repo.apk",
            browserDownloadUrl = "https://example.invalid/$repo.apk",
            size = 100L,
        ),
        publishedAt = null,
        prerelease = false,
    )
}
