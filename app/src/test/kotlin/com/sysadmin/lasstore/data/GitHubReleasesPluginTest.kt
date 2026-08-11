package com.sysadmin.lasstore.data

import com.sysadmin.lasstore.domain.Release
import com.sysadmin.lasstore.domain.ReleaseAsset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleasesPluginTest {
    @Test
    fun mapsRepositoriesAndFiltersConfiguredTopics() = runBlocking {
        val gateway = FakeGateway(
            repos = listOf(
                repo("owner/first", topics = listOf("android")),
                repo("owner/second", topics = listOf("tools")),
                repo("owner/archived", archived = true, topics = listOf("android")),
            ),
        )
        val plugin = GitHubReleasesPlugin(
            gateway = gateway,
            source = GitHubSource(user = "owner", topic = "android", filterByTopic = true),
        )

        assertEquals(listOf("owner/first"), plugin.listApps().map { it.applicationId })
    }

    @Test
    fun verifiesSelectedApkDigestAndRejectsNonHttpsDownload() = runBlocking {
        val plugin = GitHubReleasesPlugin(FakeGateway(), GitHubSource(user = "owner"))
        val release = Release(
            id = "release",
            applicationId = "owner/repo",
            versionName = "1.0",
            assets = listOf(
                ReleaseAsset(
                    id = "1",
                    name = "app.apk",
                    downloadUrl = "https://github.com/owner/repo/releases/download/v1/app.apk",
                    sha256 = "ab".repeat(32),
                ),
            ),
        )

        assertTrue(plugin.verify(release) is com.sysadmin.lasstore.domain.VerifyResult.Verified)
        assertEquals(release.assets.single().downloadUrl, plugin.resolveDownloadUrl(release))

        val insecure = release.copy(
            assets = listOf(release.assets.single().copy(downloadUrl = "http://example.invalid/app.apk")),
        )
        val failure = runCatching { plugin.resolveDownloadUrl(insecure) }
        assertTrue(failure.isFailure)
    }

    private class FakeGateway(
        private val repos: List<GhRepo> = emptyList(),
    ) : GitHubGateway {
        override suspend fun listUserRepos(
            user: String,
            patOverride: String?,
            sourceKey: String,
        ): List<GhRepo> = repos

        override suspend fun latestRelease(
            owner: String,
            repo: String,
            includePrereleases: Boolean,
            patOverride: String?,
            sourceKey: String,
        ): GhRelease? = null
    }

    private fun repo(
        fullName: String,
        archived: Boolean = false,
        topics: List<String> = emptyList(),
    ): GhRepo {
        val (owner, name) = fullName.split('/')
        return GhRepo(
            name = name,
            fullName = fullName,
            htmlUrl = "https://github.com/$fullName",
            archived = archived,
            topics = topics,
            owner = GhOwner(owner),
        )
    }
}
