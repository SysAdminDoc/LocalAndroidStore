package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.GhAsset
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogAggregationTest {
    @Test
    fun groupsKnownPackagesAndHonorsPreferredSource() {
        val candidates = listOf(
            app("fdroid", "https://fdroid.example/app.apk"),
            app("github", "https://github.example/app.apk"),
        )

        val grouped = aggregateCatalogApps(
            infos = candidates,
            preferredSourceFor = { "github" },
        )

        assertEquals(1, grouped.size)
        assertEquals("github", grouped.single().primary.sourceKey)
        assertEquals(candidates, grouped.single().candidates)
    }

    @Test
    fun leavesSourceNativeAppsDistinctUntilPackageIdentityIsKnown() {
        val grouped = aggregateCatalogApps(
            listOf(
                app("first", "https://one.example/app.apk", applicationId = null),
                app("second", "https://two.example/app.apk", applicationId = null),
            ),
        )

        assertEquals(2, grouped.size)
        assertEquals(listOf(1, 1), grouped.map { it.candidates.size })
    }

    @Test
    fun fallsBackWhenThePinnedSourceRequiresAnIncompatibleSdk() {
        val grouped = aggregateCatalogApps(
            infos = listOf(
                app("preferred", "https://preferred.example/app.apk", minSdk = 35),
                app("compatible", "https://compatible.example/app.apk", minSdk = 26),
            ),
            preferredSourceFor = { "preferred" },
            candidateAllowed = { it.minSdk == null || it.minSdk <= 33 },
        )

        assertEquals("compatible", grouped.single().primary.sourceKey)
    }

    private fun app(
        sourceKey: String,
        url: String,
        applicationId: String? = "com.example.shared",
        minSdk: Int? = null,
    ) = AppInfo(
        owner = sourceKey,
        repo = "app",
        sourceKey = sourceKey,
        sourceLabel = sourceKey,
        displayName = "Example",
        description = null,
        stars = 0,
        htmlUrl = url,
        tagName = "v1",
        versionName = "1",
        versionCode = 1,
        applicationId = applicationId,
        asset = GhAsset(name = "app.apk", browserDownloadUrl = url),
        publishedAt = null,
        prerelease = false,
        minSdk = minSdk,
    )
}
