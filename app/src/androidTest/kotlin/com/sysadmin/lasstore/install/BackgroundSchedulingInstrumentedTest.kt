package com.sysadmin.lasstore.install

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import com.sysadmin.lasstore.data.Logger
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.domain.AppInfo
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundSchedulingInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun api35UidtJobCarriesNetworkEstimateAndUserInitiatedFlag() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val info = appInfo()
        val payload = QueuedUpdatePayload.from(info)
        val job = BackgroundUpdateScheduler(context, Logger(context))
            .buildUidtJobInfo(payload)

        assertTrue(job.isUserInitiated)
        assertNotNull(job.requiredNetwork)
        assertEquals(info.asset.size, job.estimatedNetworkDownloadBytes)
        assertEquals(
            QueuedUpdateJobService::class.java.name,
            job.service.className,
        )
        assertEquals(payload.generationId, job.extras.getString("generation_id"))
    }

    @Test
    fun api26FallbackEnqueuesConstrainedWorkManagerRequest() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        ServiceLocator.init(context)
        val info = appInfo()
        val payload = QueuedUpdatePayload.from(info)
        val scheduler = BackgroundUpdateScheduler(context, ServiceLocator.logger)

        try {
            assertTrue(scheduler.enqueue(info))
            val work = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(payload.workName)
                .get(10, TimeUnit.SECONDS)
            assertTrue(work.isNotEmpty())
            assertEquals(
                payload.generationId,
                ServiceLocator.queuedUpdateStatus.get(payload)?.generationId,
            )
            assertTrue(
                work.all {
                    it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED ||
                        it.state == WorkInfo.State.RUNNING
                }
            )
        } finally {
            scheduler.cancel(info)
        }
    }

    @Test
    fun periodicFailureIsRescheduledInsteadOfBecomingTerminal() {
        ServiceLocator.init(context)
        val payload = QueuedUpdatePayload.from(appInfo())
        ServiceLocator.queuedUpdateStatus.markQueued(payload)
        val request = PeriodicWorkRequestBuilder<QueuedUpdateWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .setInputData(payload.toWorkData())
            .build()
        val manager = WorkManager.getInstance(context)

        try {
            manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
            val deadline = System.currentTimeMillis() + 10_000L
            while (
                ServiceLocator.queuedUpdateStatus.get(payload)?.phase !=
                    QueuedUpdatePhase.Failed &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(100)
            }
            assertEquals(
                QueuedUpdatePhase.Failed,
                ServiceLocator.queuedUpdateStatus.get(payload)?.phase,
            )
            assertEquals(
                WorkInfo.State.ENQUEUED,
                requireNotNull(
                    manager.getWorkInfoById(request.id)
                        .get(10, TimeUnit.SECONDS),
                ).state,
            )
        } finally {
            manager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun replacementGenerationIgnoresLateStatusWrites() {
        ServiceLocator.init(context)
        context.getSharedPreferences("queued_update_status", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val oldPayload = QueuedUpdatePayload.from(appInfo(), generationId = "old-generation")
        val newPayload = QueuedUpdatePayload.from(appInfo(), generationId = "new-generation")
        val statusStore = ServiceLocator.queuedUpdateStatus

        try {
            statusStore.markQueued(oldPayload)
            assertTrue(statusStore.isCurrent(oldPayload))

            statusStore.markQueued(newPayload)
            assertFalse(statusStore.isCurrent(oldPayload))
            assertTrue(statusStore.isCurrent(newPayload))

            var staleFinalizerRan = false
            statusStore.ifCurrent(oldPayload) { staleFinalizerRan = true }
            assertFalse(staleFinalizerRan)

            statusStore.markFailed(
                oldPayload,
                attempt = 1,
                failure = QueuedUpdateResult.Failed(
                    "old attempt failed",
                    QueuedUpdateFailureKind.Network,
                ),
            )
            statusStore.markInstalled(oldPayload)

            assertEquals(QueuedUpdatePhase.Queued, statusStore.get(newPayload)?.phase)
            assertEquals("new-generation", statusStore.get(newPayload)?.generationId)
        } finally {
            context.getSharedPreferences("queued_update_status", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private fun appInfo() = AppInfo(
        owner = "example",
        repo = "app",
        sourceKey = "example",
        sourceLabel = "example",
        displayName = "Example",
        description = null,
        stars = 0,
        htmlUrl = "https://github.com/example/app",
        tagName = "v2",
        versionName = "2",
        versionCode = null,
        applicationId = "com.example.app",
        asset = GhAsset(
            name = "app.apk",
            browserDownloadUrl = "https://example.invalid/app.apk",
            size = 12_345L,
        ),
        publishedAt = null,
        prerelease = false,
    )
}
