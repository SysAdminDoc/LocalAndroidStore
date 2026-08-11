package com.sysadmin.lasstore.ui.catalog

import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.GhRelease
import com.sysadmin.lasstore.domain.AppInfo
import com.sysadmin.lasstore.domain.CardStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogResumeTest {
    @Test
    fun activityResumeRebuildKeepsHistoricalSelectionContext() {
        val info = AppInfo(
            owner = "owner",
            repo = "repo",
            sourceKey = "owner",
            sourceLabel = "owner",
            displayName = "Example",
            description = null,
            stars = 0,
            htmlUrl = "https://github.com/owner/repo",
            tagName = "v2",
            versionName = "2",
            versionCode = null,
            applicationId = "com.example.app",
            asset = GhAsset(
                name = "example.apk",
                browserDownloadUrl = "https://example.com/example.apk",
            ),
            publishedAt = null,
            prerelease = false,
        )
        val history = ReleaseHistoryState(
            releases = listOf(
                HistoricalRelease(
                    release = GhRelease(
                        tagName = "v1",
                        htmlUrl = "https://github.com/owner/repo/releases/tag/v1",
                    ),
                    info = null,
                ),
            ),
        )
        val previous = CardState(
            info = info,
            status = CardStatus.ReleaseAvailable,
            releaseHistory = history,
            historicalSelection = true,
        )
        val rebuilt = CardState(info = info, status = CardStatus.NotInstalled)

        val reconciled = preserveActivityResumeContext(previous, rebuilt)

        assertEquals(CardStatus.NotInstalled, reconciled.status)
        assertEquals(history, reconciled.releaseHistory)
        assertTrue(reconciled.historicalSelection)
    }
}
