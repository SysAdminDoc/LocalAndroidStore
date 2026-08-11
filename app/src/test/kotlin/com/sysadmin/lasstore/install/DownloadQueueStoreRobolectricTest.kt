package com.sysadmin.lasstore.install

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.domain.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadQueueStoreRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun stagedPayloadSurvivesAStoreReloadAndDeduplicatesByWorkName() {
        clearQueue()
        val first = QueuedUpdatePayload.from(appInfo("v1"), generationId = "first")
        val replacement = QueuedUpdatePayload.from(appInfo("v2"), generationId = "second")
        val store = DownloadQueueStore(context)

        assertTrue(store.stage(first))
        assertFalse(store.stage(first))
        assertEquals(listOf(first), DownloadQueueStore(context).payloads())

        assertTrue(store.stage(replacement))
        assertEquals(listOf(replacement), DownloadQueueStore(context).payloads())
        assertTrue(store.remove(replacement.workName))
        assertTrue(DownloadQueueStore(context).payloads().isEmpty())
    }

    private fun clearQueue() {
        context.getSharedPreferences("las_download_queue_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun appInfo(tag: String) = AppInfo(
        owner = "owner",
        repo = "app",
        sourceKey = "github",
        sourceLabel = "GitHub",
        displayName = "App",
        description = null,
        stars = 0,
        htmlUrl = "https://github.com/owner/app",
        tagName = tag,
        versionName = tag,
        versionCode = null,
        applicationId = "com.example.app",
        asset = GhAsset(
            id = if (tag == "v1") 1L else 2L,
            name = "app.apk",
            browserDownloadUrl = "https://example.invalid/$tag.apk",
            size = 100L,
            digest = "a".repeat(64),
        ),
        publishedAt = null,
        prerelease = false,
    )
}
