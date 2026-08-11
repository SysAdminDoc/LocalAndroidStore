package com.sysadmin.lasstore.ui.catalog

import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.LibraryRestoreEntry
import com.sysadmin.lasstore.domain.AppInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRestoreMatchingTest {
    @Test
    fun matchesExactSourceReleaseAndPackageFallback() {
        val info = appInfo()
        val exact = LibraryRestoreEntry(
            applicationId = "com.example.app",
            versionCode = 7L,
            sourceKey = "alice",
            owner = "alice",
            repo = "example-app",
            tagName = "v1.2.3",
            assetName = "example.apk",
            sourceUrl = info.asset.browserDownloadUrl,
        )
        val packageOnly = exact.copy(sourceKey = "", owner = "", repo = "", tagName = "", assetName = "")
        val differentRelease = exact.copy(tagName = "v2.0.0")

        assertTrue(restoreEntryMatchesCard(exact, info, cachedApplicationId = null))
        assertTrue(restoreEntryMatchesCard(packageOnly, info.copy(applicationId = null), "com.example.app"))
        assertFalse(restoreEntryMatchesCard(differentRelease, info, cachedApplicationId = null))
    }

    private fun appInfo() = AppInfo(
        owner = "alice",
        repo = "example-app",
        sourceKey = "alice",
        sourceLabel = "alice",
        displayName = "Example",
        description = null,
        stars = 0,
        htmlUrl = "https://github.com/alice/example-app",
        tagName = "v1.2.3",
        versionName = "1.2.3",
        versionCode = 7L,
        applicationId = "com.example.app",
        asset = GhAsset(
            name = "example.apk",
            browserDownloadUrl = "https://github.com/alice/example-app/releases/download/v1.2.3/example.apk",
        ),
        publishedAt = null,
        prerelease = false,
    )
}
