package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.CatalogSnapshot
import com.sysadmin.lasstore.data.CatalogSnapshotRepository
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.GhRelease
import com.sysadmin.lasstore.data.GitHubFailureKind
import com.sysadmin.lasstore.data.GitHubGateway
import com.sysadmin.lasstore.data.GitHubRequestException
import com.sysadmin.lasstore.data.GitHubSource
import com.sysadmin.lasstore.data.Logger
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException

enum class CatalogFailureKind {
    Tls,
    Authentication,
    Authorization,
    RateLimited,
    Network,
    Server,
    InvalidResponse,
    Unknown,
}

data class CatalogSourceIssue(
    val sourceKey: String,
    val sourceLabel: String,
    val kind: CatalogFailureKind,
    val message: String,
    val retryAtEpochMillis: Long? = null,
    val snapshotAgeMillis: Long? = null,
)

data class CatalogDiscoveryResult(
    val apps: List<AppInfo>,
    val issues: List<CatalogSourceIssue>,
    val snapshotAgeMillis: Long? = null,
) {
    val isValidEmpty: Boolean
        get() = apps.isEmpty() && issues.isEmpty()
}

internal object CatalogFailureClassifier {
    fun classify(
        source: GitHubSource,
        throwable: Throwable,
    ): CatalogSourceIssue {
        val kind = when (throwable) {
            is SSLHandshakeException -> CatalogFailureKind.Tls
            is SocketTimeoutException -> CatalogFailureKind.Network
            is UnknownHostException -> CatalogFailureKind.Network
            is GitHubRequestException -> when (throwable.kind) {
                GitHubFailureKind.Authentication -> CatalogFailureKind.Authentication
                GitHubFailureKind.Authorization -> CatalogFailureKind.Authorization
                GitHubFailureKind.RateLimited -> CatalogFailureKind.RateLimited
                GitHubFailureKind.Server -> CatalogFailureKind.Server
                GitHubFailureKind.Http -> CatalogFailureKind.InvalidResponse
            }
            is SerializationException -> CatalogFailureKind.InvalidResponse
            is IOException -> CatalogFailureKind.Network
            else -> CatalogFailureKind.Unknown
        }
        val message = when (kind) {
            CatalogFailureKind.Tls -> "Secure connection to GitHub failed."
            CatalogFailureKind.Authentication -> "GitHub rejected this source's token."
            CatalogFailureKind.Authorization -> "The token cannot access this GitHub source."
            CatalogFailureKind.RateLimited -> "GitHub's request limit was reached."
            CatalogFailureKind.Network -> "GitHub is unreachable from this device."
            CatalogFailureKind.Server -> "GitHub returned a temporary server error."
            CatalogFailureKind.InvalidResponse -> "GitHub returned metadata this app could not read."
            CatalogFailureKind.Unknown -> "Catalog refresh failed unexpectedly."
        }
        return CatalogSourceIssue(
            sourceKey = source.key,
            sourceLabel = source.displayName,
            kind = kind,
            message = message,
            retryAtEpochMillis = (throwable as? GitHubRequestException)?.retryAtEpochMillis,
        )
    }
}

class DiscoveryUseCase(
    private val github: GitHubGateway,
    private val logger: Logger?,
    private val snapshots: CatalogSnapshotRepository,
    private val patForSource: (String) -> String = { "" },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun discover(sources: List<GitHubSource>): CatalogDiscoveryResult = coroutineScope {
        val requestBudget = Semaphore(MAX_CONCURRENT_RELEASE_LOOKUPS)
        val sourceResults = sources
            .filter { it.enabled }
            .map { source ->
                async { discoverSource(source, requestBudget) }
            }
            .awaitAll()

        val apps = sourceResults
            .flatMap { it.apps }
            .distinctBy {
                "${it.sourceKey}/${it.owner}/${it.repo}/${it.tagName}/${it.asset.name}"
                    .lowercase(Locale.US)
            }
            .sortedWith(
                compareByDescending<AppInfo> { it.stars }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
        CatalogDiscoveryResult(
            apps = apps,
            issues = sourceResults.mapNotNull { it.issue },
            snapshotAgeMillis = sourceResults
                .mapNotNull { it.snapshotAgeMillis }
                .maxOrNull(),
        )
    }

    private suspend fun discoverSource(
        source: GitHubSource,
        requestBudget: Semaphore,
    ): SourceDiscovery = coroutineScope {
        val user = source.user.trim()
        if (user.isEmpty()) return@coroutineScope SourceDiscovery(emptyList())
        val pat = patForSource(source.key)

        val repos = try {
            github.listUserRepos(
                user = user,
                patOverride = pat,
                sourceKey = source.key,
            )
        } catch (throwable: Throwable) {
            logger?.error("Discovery", "Could not list repositories for ${source.displayName}", throwable)
            return@coroutineScope recoverFromSnapshot(
                source,
                CatalogFailureClassifier.classify(source, throwable),
            )
        }

        val candidates = repos
            .filter { !it.archived && !it.fork }
            .filter {
                !source.filterByTopic ||
                    it.topics.any { topic -> topic.equals(source.topic.trim(), ignoreCase = true) }
            }

        val lookups = candidates.map { repo ->
            async {
                try {
                    val release = requestBudget.withPermit {
                        github.latestRelease(
                            owner = repo.owner.login,
                            repo = repo.name,
                            includePrereleases = source.showPrereleases,
                            patOverride = pat,
                            sourceKey = source.key,
                        )
                    } ?: return@async ReleaseLookup.Missing
                    val asset = pickPrimaryApk(release) ?: return@async ReleaseLookup.Missing
                    ReleaseLookup.Found(
                        AppInfo(
                            owner = repo.owner.login,
                            repo = repo.name,
                            sourceKey = source.key,
                            sourceLabel = source.displayName,
                            displayName = repo.name,
                            description = repo.description,
                            stars = repo.stars,
                            htmlUrl = repo.htmlUrl,
                            tagName = release.tagName,
                            versionName = release.tagName.removePrefix("v").removePrefix("V"),
                            versionCode = null,
                            applicationId = null,
                            asset = asset,
                            publishedAt = release.publishedAt,
                            prerelease = release.prerelease,
                            releaseBody = release.body?.takeIf { it.isNotBlank() },
                        )
                    )
                } catch (throwable: Throwable) {
                    logger?.warn(
                        "Discovery",
                        "${repo.owner.login}/${repo.name}: ${throwable.message}",
                    )
                    ReleaseLookup.Failed(CatalogFailureClassifier.classify(source, throwable))
                }
            }
        }.awaitAll()

        val liveApps = lookups.mapNotNull { (it as? ReleaseLookup.Found)?.app }
        val issue = lookups
            .mapNotNull { (it as? ReleaseLookup.Failed)?.issue }
            .minByOrNull { issuePriority(it.kind) }

        if (issue == null) {
            runCatching {
                snapshots.write(
                    CatalogSnapshot(
                        sourceKey = source.key,
                        sourceLabel = source.displayName,
                        refreshedAtEpochMillis = nowEpochMillis(),
                        apps = liveApps,
                    )
                )
            }.onFailure {
                logger?.warn("Discovery", "Could not persist snapshot for ${source.displayName}")
            }
            SourceDiscovery(apps = liveApps)
        } else {
            mergeWithSnapshot(source, liveApps, issue)
        }
    }

    private fun recoverFromSnapshot(
        source: GitHubSource,
        issue: CatalogSourceIssue,
    ): SourceDiscovery = mergeWithSnapshot(source, emptyList(), issue)

    private fun mergeWithSnapshot(
        source: GitHubSource,
        liveApps: List<AppInfo>,
        issue: CatalogSourceIssue,
    ): SourceDiscovery {
        val snapshot = snapshots.read(source.key)
        if (snapshot == null && liveApps.isNotEmpty()) {
            runCatching {
                snapshots.write(
                    CatalogSnapshot(
                        sourceKey = source.key,
                        sourceLabel = source.displayName,
                        refreshedAtEpochMillis = nowEpochMillis(),
                        apps = liveApps,
                    )
                )
            }.onFailure {
                logger?.warn(
                    "Discovery",
                    "Could not persist partial snapshot for ${source.displayName}",
                )
            }
            return SourceDiscovery(
                apps = liveApps,
                issue = issue,
            )
        }
        val liveKeys = liveApps
            .map { "${it.owner}/${it.repo}".lowercase(Locale.US) }
            .toSet()
        val cachedRemainder = snapshot?.apps.orEmpty().filter {
            "${it.owner}/${it.repo}".lowercase(Locale.US) !in liveKeys
        }
        val age = snapshot
            ?.let { (nowEpochMillis() - it.refreshedAtEpochMillis).coerceAtLeast(0L) }
        return SourceDiscovery(
            apps = liveApps + cachedRemainder,
            issue = issue.copy(snapshotAgeMillis = age),
            snapshotAgeMillis = age,
        )
    }

    private fun issuePriority(kind: CatalogFailureKind): Int = when (kind) {
        CatalogFailureKind.Tls -> 0
        CatalogFailureKind.Authentication -> 1
        CatalogFailureKind.Authorization -> 2
        CatalogFailureKind.RateLimited -> 3
        CatalogFailureKind.Network -> 4
        CatalogFailureKind.Server -> 5
        CatalogFailureKind.InvalidResponse -> 6
        CatalogFailureKind.Unknown -> 7
    }

    /**
     * Choose the primary APK asset:
     *   1. Skip .apk.idsig sidecars and .aab.
     *   2. Prefer one whose name contains "universal".
     *   3. Otherwise pick the largest .apk.
     */
    private fun pickPrimaryApk(release: GhRelease): GhAsset? {
        val apks = release.assets.filter {
            val name = it.name.lowercase(Locale.US)
            name.endsWith(".apk") && !name.endsWith(".apk.idsig")
        }
        if (apks.isEmpty()) return null
        apks.firstOrNull {
            it.name.lowercase(Locale.US).contains("universal")
        }?.let { return it }
        return apks.maxByOrNull { it.size }
    }

    private data class SourceDiscovery(
        val apps: List<AppInfo>,
        val issue: CatalogSourceIssue? = null,
        val snapshotAgeMillis: Long? = null,
    )

    private sealed interface ReleaseLookup {
        data class Found(val app: AppInfo) : ReleaseLookup
        data class Failed(val issue: CatalogSourceIssue) : ReleaseLookup
        data object Missing : ReleaseLookup
    }

    private companion object {
        const val MAX_CONCURRENT_RELEASE_LOOKUPS = 4
    }
}
