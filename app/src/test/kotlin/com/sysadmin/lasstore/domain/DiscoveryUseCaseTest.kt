package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.CatalogSnapshot
import com.sysadmin.lasstore.data.CatalogSnapshotRepository
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.GhOwner
import com.sysadmin.lasstore.data.GhRelease
import com.sysadmin.lasstore.data.GhRepo
import com.sysadmin.lasstore.data.GitHubGateway
import com.sysadmin.lasstore.data.GitHubSource
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryUseCaseTest {
    @Test
    fun releaseLookupConcurrencyNeverExceedsFour() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val gateway = object : GitHubGateway {
            override suspend fun listUserRepos(
                user: String,
                patOverride: String?,
                sourceKey: String,
            ): List<GhRepo> = (1..12).map { repo(user, "app-$it") }

            override suspend fun latestRelease(
                owner: String,
                repo: String,
                includePrereleases: Boolean,
                patOverride: String?,
                sourceKey: String,
            ): GhRelease {
                val concurrent = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, concurrent) }
                delay(20)
                active.decrementAndGet()
                return release(repo)
            }
        }

        val result = DiscoveryUseCase(
            github = gateway,
            logger = null,
            snapshots = MemorySnapshots(),
        ).discover(listOf(GitHubSource(user = "alice")))

        assertEquals(12, result.apps.size)
        assertTrue(maximum.get() <= 4)
    }

    @Test
    fun failedSourceUsesDatedSnapshotWithoutDroppingSuccessfulSource() = runBlocking {
        val now = 2_000_000L
        val snapshots = MemorySnapshots().apply {
            write(
                CatalogSnapshot(
                    sourceKey = "bob",
                    sourceLabel = "bob",
                    refreshedAtEpochMillis = 1_000_000L,
                    apps = listOf(app("bob", "cached")),
                )
            )
        }
        val gateway = object : GitHubGateway {
            override suspend fun listUserRepos(
                user: String,
                patOverride: String?,
                sourceKey: String,
            ): List<GhRepo> {
                if (user == "bob") throw UnknownHostException("offline")
                return listOf(repo(user, "live"))
            }

            override suspend fun latestRelease(
                owner: String,
                repo: String,
                includePrereleases: Boolean,
                patOverride: String?,
                sourceKey: String,
            ): GhRelease = release(repo)
        }

        val result = DiscoveryUseCase(
            github = gateway,
            logger = null,
            snapshots = snapshots,
            nowEpochMillis = { now },
        ).discover(
            listOf(
                GitHubSource(user = "alice"),
                GitHubSource(user = "bob"),
            )
        )

        assertEquals(setOf("live", "cached"), result.apps.map { it.repo }.toSet())
        assertEquals(CatalogFailureKind.Network, result.issues.single().kind)
        assertEquals(1_000_000L, result.snapshotAgeMillis)
    }

    @Test
    fun successfulEmptySourceIsNotReportedAsFailure() = runBlocking {
        val gateway = object : GitHubGateway {
            override suspend fun listUserRepos(
                user: String,
                patOverride: String?,
                sourceKey: String,
            ): List<GhRepo> = emptyList()

            override suspend fun latestRelease(
                owner: String,
                repo: String,
                includePrereleases: Boolean,
                patOverride: String?,
                sourceKey: String,
            ): GhRelease? = null
        }
        val result = DiscoveryUseCase(
            github = gateway,
            logger = null,
            snapshots = MemorySnapshots(),
        ).discover(listOf(GitHubSource(user = "alice")))

        assertTrue(result.isValidEmpty)
    }

    @Test
    fun firstPartialRefreshBecomesTheOfflineFallback() = runBlocking {
        val snapshots = MemorySnapshots()
        val gateway = object : GitHubGateway {
            override suspend fun listUserRepos(
                user: String,
                patOverride: String?,
                sourceKey: String,
            ): List<GhRepo> = listOf(
                repo(user, "live"),
                repo(user, "limited"),
            )

            override suspend fun latestRelease(
                owner: String,
                repo: String,
                includePrereleases: Boolean,
                patOverride: String?,
                sourceKey: String,
            ): GhRelease {
                if (repo == "limited") throw UnknownHostException("offline")
                return release(repo)
            }
        }

        val result = DiscoveryUseCase(
            github = gateway,
            logger = null,
            snapshots = snapshots,
            nowEpochMillis = { 5_000L },
        ).discover(listOf(GitHubSource(user = "alice")))

        assertEquals(listOf("live"), result.apps.map { it.repo })
        assertEquals(listOf("live"), snapshots.read("alice")?.apps?.map { it.repo })
        assertEquals(5_000L, snapshots.read("alice")?.refreshedAtEpochMillis)
        assertEquals(null, result.snapshotAgeMillis)
    }

    @Test
    fun transportAndCredentialFailuresRemainDistinct() {
        val source = GitHubSource(user = "alice")
        val tls = CatalogFailureClassifier.classify(
            source,
            SSLHandshakeException("bad certificate"),
        )
        val authentication = CatalogFailureClassifier.classify(
            source,
            com.sysadmin.lasstore.data.GitHubRequestException(
                kind = com.sysadmin.lasstore.data.GitHubFailureKind.Authentication,
                statusCode = 401,
                message = "unauthorized",
            ),
        )
        val rateLimit = CatalogFailureClassifier.classify(
            source,
            com.sysadmin.lasstore.data.GitHubRequestException(
                kind = com.sysadmin.lasstore.data.GitHubFailureKind.RateLimited,
                statusCode = 403,
                retryAtEpochMillis = 9_000L,
                message = "limited",
            ),
        )

        assertEquals(CatalogFailureKind.Tls, tls.kind)
        assertEquals(CatalogFailureKind.Authentication, authentication.kind)
        assertEquals(CatalogFailureKind.RateLimited, rateLimit.kind)
        assertEquals(9_000L, rateLimit.retryAtEpochMillis)
    }

    @Test
    fun assetClassifierPrefersUniversalOverLargerAbiVariant() {
        val selected = ApkAssetClassifier.select(
            assets = listOf(
                asset("app-arm64-v8a.apk", size = 200),
                asset("app-universal.apk", size = 100),
            ),
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals("app-universal.apk", selected?.name)
    }

    @Test
    fun assetClassifierFollowsDeviceAbiOrderAndRejectsIncompatibleOnlyRelease() {
        val variants = listOf(
            asset("app-armeabi-v7a.apk", size = 300),
            asset("app-arm64_v8a.apk", size = 200),
            asset("app-x86_64.apk", size = 500),
        )

        assertEquals(
            "app-arm64_v8a.apk",
            ApkAssetClassifier.select(
                variants,
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            )?.name,
        )
        assertEquals(
            null,
            ApkAssetClassifier.select(
                variants,
                supportedAbis = listOf("x86"),
            ),
        )
    }

    @Test
    fun assetClassifierDoesNotTreatSplitSetOrSidecarAsStandaloneApk() {
        assertEquals(
            null,
            ApkAssetClassifier.select(
                assets = listOf(
                    asset("base.apk", size = 100),
                    asset("split_config.arm64_v8a.apk", size = 20),
                    asset("split_config.en.apk", size = 10),
                ),
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
        assertEquals(
            null,
            ApkAssetClassifier.select(
                assets = listOf(
                    asset("app.apk.idsig", size = 10),
                    asset("app.aab", size = 100),
                    asset("app.apkm", size = 100),
                ),
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun assetClassifierKeepsSingleUnlabeledApkConvention() {
        assertEquals(
            "app-release.apk",
            ApkAssetClassifier.select(
                assets = listOf(asset("app-release.apk", size = 100)),
                supportedAbis = listOf("arm64-v8a"),
            )?.name,
        )
    }

    private class MemorySnapshots : CatalogSnapshotRepository {
        private val values = mutableMapOf<String, CatalogSnapshot>()
        override fun read(sourceKey: String): CatalogSnapshot? = values[sourceKey]
        override fun write(snapshot: CatalogSnapshot) {
            values[snapshot.sourceKey] = snapshot
        }
    }

    private companion object {
        fun repo(owner: String, name: String) = GhRepo(
            name = name,
            fullName = "$owner/$name",
            htmlUrl = "https://github.com/$owner/$name",
            owner = GhOwner(owner),
        )

        fun release(repo: String) = GhRelease(
            tagName = "v1",
            htmlUrl = "https://github.com/example/$repo/releases/tag/v1",
            assets = listOf(
                GhAsset(
                    name = "$repo-universal.apk",
                    browserDownloadUrl = "https://example.invalid/$repo.apk",
                )
            ),
        )

        fun asset(name: String, size: Long) = GhAsset(
            name = name,
            browserDownloadUrl = "https://example.invalid/$name",
            size = size,
        )

        fun app(owner: String, repo: String) = AppInfo(
            owner = owner,
            repo = repo,
            sourceKey = owner,
            sourceLabel = owner,
            displayName = repo,
            description = null,
            stars = 0,
            htmlUrl = "https://github.com/$owner/$repo",
            tagName = "v1",
            versionName = "1",
            versionCode = null,
            applicationId = null,
            asset = GhAsset(
                name = "$repo.apk",
                browserDownloadUrl = "https://example.invalid/$repo.apk",
            ),
            publishedAt = null,
            prerelease = false,
        )
    }
}
