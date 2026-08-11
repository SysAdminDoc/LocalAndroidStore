package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.CatalogSnapshot
import com.sysadmin.lasstore.data.CatalogSnapshotRepository
import com.sysadmin.lasstore.data.FDroidIndexV2Plugin
import com.sysadmin.lasstore.data.FdroidIndexProvider
import com.sysadmin.lasstore.data.FdroidRepositoryTrust
import com.sysadmin.lasstore.data.FdroidSource
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.GhRelease
import com.sysadmin.lasstore.data.GitHubFailureKind
import com.sysadmin.lasstore.data.GitHubGateway
import com.sysadmin.lasstore.data.GitHubRepoListResult
import com.sysadmin.lasstore.data.GitHubRequestException
import com.sysadmin.lasstore.data.GitHubSource
import com.sysadmin.lasstore.data.InstallArtifactKind
import com.sysadmin.lasstore.data.Logger
import com.sysadmin.lasstore.data.NetworkUnavailableException
import com.sysadmin.lasstore.data.installArtifactKind
import com.sysadmin.lasstore.data.isInstallableArtifactName
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException

enum class CatalogFailureKind {
    Tls,
    Authentication,
    Authorization,
    RateLimited,
    Truncated,
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
    val fetchedCount: Int? = null,
    val omittedCount: Int? = null,
    val omittedCountIsLowerBound: Boolean = false,
    val continuationPage: Int? = null,
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
            CatalogFailureKind.Truncated -> "GitHub returned only part of this source's repositories."
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

    fun classifyFdroid(
        source: FdroidSource,
        throwable: Throwable,
    ): CatalogSourceIssue {
        val kind = when (throwable) {
            is SSLHandshakeException -> CatalogFailureKind.Tls
            is SocketTimeoutException,
            is UnknownHostException,
            is NetworkUnavailableException,
            is IOException -> CatalogFailureKind.Network
            is SerializationException,
            is IllegalArgumentException,
            is SecurityException -> CatalogFailureKind.InvalidResponse
            else -> CatalogFailureKind.Unknown
        }
        val message = when (kind) {
            CatalogFailureKind.Tls -> "Secure connection to this F-Droid repository failed."
            CatalogFailureKind.Network -> "This F-Droid repository is unreachable from the device."
            CatalogFailureKind.InvalidResponse ->
                "This F-Droid repository failed fingerprint or index validation."
            else -> "F-Droid repository refresh failed unexpectedly."
        }
        return CatalogSourceIssue(
            sourceKey = source.key,
            sourceLabel = source.displayName,
            kind = kind,
            message = message,
        )
    }
}

class DiscoveryUseCase(
    private val github: GitHubGateway,
    private val logger: Logger?,
    private val snapshots: CatalogSnapshotRepository,
    private val patForSource: (String) -> String = { "" },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val supportedAbis: List<String> = emptyList(),
    private val fdroidIndexProvider: FdroidIndexProvider? = null,
    private val preferredChannelFor: (GitHubSource, String, String) -> ReleaseChannel? =
        { _, _, _ -> null },
) {
    suspend fun discover(
        sources: List<GitHubSource>,
        fdroidSources: List<FdroidSource> = emptyList(),
    ): CatalogDiscoveryResult = coroutineScope {
        val requestBudget = Semaphore(MAX_CONCURRENT_RELEASE_LOOKUPS)
        val githubJobs = sources
            .filter { it.enabled }
            .map { source ->
                async { discoverSource(source, requestBudget) }
            }
        val fdroidJobs = fdroidSources
            .filter { it.enabled }
            .map { source -> async { discoverFdroidSource(source) } }
        val sourceResults = (githubJobs + fdroidJobs).awaitAll()

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

    private suspend fun discoverFdroidSource(source: FdroidSource): SourceDiscovery {
        val provider = fdroidIndexProvider
            ?: return SourceDiscovery(
                apps = emptyList(),
                issue = CatalogSourceIssue(
                    sourceKey = source.key,
                    sourceLabel = source.displayName,
                    kind = CatalogFailureKind.Unknown,
                    message = "F-Droid source support is not available in this build.",
                ),
            )
        return try {
            val endpoint = FdroidRepositoryTrust.parseEndpoint(source.endpointUrl)
            when (val entryJar = provider.verifyEntryJar(
                endpoint.indexUrl,
                endpoint.expectedFingerprint,
            )) {
                null,
                is VerifyResult.Verified -> Unit
                is VerifyResult.Unverified -> throw SecurityException(entryJar.reason)
                is VerifyResult.Rejected -> throw SecurityException(entryJar.reason)
            }
            var cachedRaw: String? = null
            val plugin = FDroidIndexV2Plugin(
                indexProvider = {
                    cachedRaw ?: provider.fetch(endpoint.indexUrl).also { cachedRaw = it }
                },
                baseUrl = endpoint.indexUrl,
                expectedFingerprint = endpoint.expectedFingerprint,
                id = source.key,
            )
            val apps = plugin.listApps().mapNotNull { discovered ->
                val release = plugin.getReleases(discovered.applicationId)
                    .maxWithOrNull(
                        compareBy<Release> { it.versionCode ?: Long.MIN_VALUE }
                            .thenBy { it.versionName.orEmpty() },
                    )
                    ?: return@mapNotNull null
                val asset = release.assets.firstOrNull { asset ->
                    asset.name.lowercase(Locale.US).endsWith(".apk")
                } ?: return@mapNotNull null
                val ghAsset = GhAsset(
                    id = asset.id.hashCode().toLong(),
                    name = asset.name,
                    browserDownloadUrl = plugin.resolveDownloadUrl(release),
                    size = asset.sizeBytes,
                    contentType = "application/vnd.android.package-archive",
                    digest = asset.sha256,
                )
                AppInfo(
                    owner = source.key,
                    repo = discovered.applicationId,
                    sourceKey = source.key,
                    sourceLabel = source.displayName,
                    displayName = discovered.displayName,
                    description = discovered.description,
                    stars = 0,
                    htmlUrl = discovered.homepageUrl ?: endpoint.indexUrl,
                    tagName = release.versionName
                        ?: release.versionCode?.toString()
                        ?: release.id,
                    versionName = release.versionName,
                    versionCode = release.versionCode,
                    applicationId = discovered.applicationId,
                    asset = ghAsset,
                    publishedAt = release.publishedAt,
                    prerelease = release.prerelease,
                    releaseBody = release.body,
                    minSdk = release.minSdk,
                    antiFeatures = discovered.antiFeatures,
                )
            }
            runCatching {
                snapshots.write(
                    CatalogSnapshot(
                        sourceKey = source.key,
                        sourceLabel = source.displayName,
                        refreshedAtEpochMillis = nowEpochMillis(),
                        apps = apps,
                    )
                )
            }.onFailure {
                logger?.warn("Discovery", "Could not persist snapshot for ${source.displayName}")
            }
            SourceDiscovery(apps = apps)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            logger?.error(
                "Discovery",
                "Could not read F-Droid repository ${source.displayName}",
                throwable,
            )
            SourceDiscovery(
                apps = emptyList(),
                issue = CatalogFailureClassifier.classifyFdroid(source, throwable),
            )
        }
    }

    private suspend fun discoverSource(
        source: GitHubSource,
        requestBudget: Semaphore,
    ): SourceDiscovery = coroutineScope {
        val user = source.user.trim()
        if (user.isEmpty()) return@coroutineScope SourceDiscovery(emptyList())
        val pat = patForSource(source.key)

        val repoResult = try {
            github.listUserReposResult(
                user = user,
                patOverride = pat,
                sourceKey = source.key,
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            logger?.error("Discovery", "Could not list repositories for ${source.displayName}", throwable)
            return@coroutineScope recoverFromSnapshot(
                source,
                CatalogFailureClassifier.classify(source, throwable),
            )
        }
        val repos = repoResult.repos
        val repoIssue = repoResult.truncationIssue(source)

        val candidates = repos
            .filter { !it.archived && !it.fork }
            .filter {
                !source.filterByTopic ||
                    it.topics.any { topic -> topic.equals(source.topic.trim(), ignoreCase = true) }
            }

        val lookups = candidates.map { repo ->
            async {
                try {
                    val preferredChannel = preferredChannelFor(
                        source,
                        repo.owner.login,
                        repo.name,
                    )
                    val release = requestBudget.withPermit {
                        if (preferredChannel == null) {
                            github.latestRelease(
                                owner = repo.owner.login,
                                repo = repo.name,
                                includePrereleases = source.showPrereleases,
                                patOverride = pat,
                                sourceKey = source.key,
                            )
                        } else {
                            github.latestReleaseForChannel(
                                owner = repo.owner.login,
                                repo = repo.name,
                                channel = preferredChannel,
                                patOverride = pat,
                                sourceKey = source.key,
                            )
                        }
                    } ?: return@async ReleaseLookup.Missing
                    val selection = ApkAssetClassifier.classify(
                        release.assets,
                        supportedAbis,
                    )
                    val asset = when (selection) {
                        ApkAssetSelection.Unavailable -> return@async ReleaseLookup.Missing
                        is ApkAssetSelection.Selected -> selection.asset
                        is ApkAssetSelection.SelectionRequired -> selection.candidates.first()
                    }
                    val assetChoices = (selection as? ApkAssetSelection.SelectionRequired)
                        ?.candidates
                        .orEmpty()
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
                            assetChoices = assetChoices,
                        )
                    )
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    logger?.warn(
                        "Discovery",
                        "${repo.owner.login}/${repo.name}: ${throwable.message}",
                    )
                    ReleaseLookup.Failed(
                        repoKey = repoKey(repo.owner.login, repo.name),
                        issue = CatalogFailureClassifier.classify(source, throwable),
                    )
                }
            }
        }.awaitAll()

        val liveApps = lookups.mapNotNull { (it as? ReleaseLookup.Found)?.app }
        val transientFailedKeys = lookups
            .mapNotNull { lookup ->
                (lookup as? ReleaseLookup.Failed)
                    ?.takeIf { it.issue.kind.isTransientLookupFailure() }
                    ?.repoKey
            }
            .toSet()
        val releaseIssue = lookups
            .mapNotNull { (it as? ReleaseLookup.Failed)?.issue }
            .minByOrNull { issuePriority(it.kind) }

        if (repoIssue != null) {
            // A truncated repository list is not a safe offline snapshot boundary: cached repos
            // beyond the fetched page must not be resurrected as if they were current.
            SourceDiscovery(apps = liveApps, issue = repoIssue)
        } else if (releaseIssue == null) {
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
            mergeWithSnapshot(
                source = source,
                liveApps = liveApps,
                issue = releaseIssue,
                retainKeys = transientFailedKeys,
            )
        }
    }

    private fun GitHubRepoListResult.truncationIssue(source: GitHubSource): CatalogSourceIssue? {
        if (!isTruncated) return null
        val omitted = if (omittedCountIsLowerBound) "at least $omittedCount" else omittedCount.toString()
        return CatalogSourceIssue(
            sourceKey = source.key,
            sourceLabel = source.displayName,
            kind = CatalogFailureKind.Truncated,
            message = "${source.displayName} returned $fetchedCount repositories and stopped before " +
                "page $continuationPage; $omitted repositories were not inspected. " +
                "Use a topic filter to narrow this source, then refresh.",
            fetchedCount = fetchedCount,
            omittedCount = omittedCount,
            omittedCountIsLowerBound = omittedCountIsLowerBound,
            continuationPage = continuationPage,
        )
    }

    private fun recoverFromSnapshot(
        source: GitHubSource,
        issue: CatalogSourceIssue,
    ): SourceDiscovery = mergeWithSnapshot(
        source = source,
        liveApps = emptyList(),
        issue = issue,
        retainKeys = null,
    )

    private fun mergeWithSnapshot(
        source: GitHubSource,
        liveApps: List<AppInfo>,
        issue: CatalogSourceIssue,
        retainKeys: Set<String>?,
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
        val age = snapshot
            ?.let { (nowEpochMillis() - it.refreshedAtEpochMillis).coerceAtLeast(0L) }
        val cachedRemainder = snapshot
            ?.takeIf { age != null && age <= MAX_PARTIAL_SNAPSHOT_AGE_MILLIS }
            ?.apps
            .orEmpty()
            .filter { app ->
                retainKeys == null || repoKey(app.owner, app.repo) in retainKeys
            }
            .map { it.copy(isStale = true) }
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
        CatalogFailureKind.Truncated -> 4
        CatalogFailureKind.Network -> 5
        CatalogFailureKind.Server -> 6
        CatalogFailureKind.InvalidResponse -> 7
        CatalogFailureKind.Unknown -> 8
    }

    private fun CatalogFailureKind.isTransientLookupFailure(): Boolean = when (this) {
        CatalogFailureKind.Tls,
        CatalogFailureKind.RateLimited,
        CatalogFailureKind.Network,
        CatalogFailureKind.Server -> true
        CatalogFailureKind.Authentication,
        CatalogFailureKind.Authorization,
        CatalogFailureKind.Truncated,
        CatalogFailureKind.InvalidResponse,
        CatalogFailureKind.Unknown -> false
    }

    private data class SourceDiscovery(
        val apps: List<AppInfo>,
        val issue: CatalogSourceIssue? = null,
        val snapshotAgeMillis: Long? = null,
    )

    private sealed interface ReleaseLookup {
        data class Found(val app: AppInfo) : ReleaseLookup
        data class Failed(val repoKey: String, val issue: CatalogSourceIssue) : ReleaseLookup
        data object Missing : ReleaseLookup
    }

    private companion object {
        const val MAX_CONCURRENT_RELEASE_LOOKUPS = 4
        const val MAX_PARTIAL_SNAPSHOT_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L

        fun repoKey(owner: String, repo: String): String =
            "$owner/$repo".lowercase(Locale.US)
    }
}

/** Selects one standalone APK or one archive; archive contents are verified after download. */
internal object ApkAssetClassifier {
    fun classify(
        assets: List<GhAsset>,
        supportedAbis: List<String>,
    ): ApkAssetSelection {
        val archiveAssets = assets.filter { asset ->
            isInstallableArtifactName(asset.name) &&
                installArtifactKind(asset.name) != InstallArtifactKind.APK
        }
        if (archiveAssets.isNotEmpty()) return archiveAssets.toSelection()

        val apkAssets = assets.filter { asset ->
            val name = asset.name.lowercase(Locale.US)
            name.endsWith(".apk") &&
                !name.endsWith(".apk.idsig")
        }
        val splitSetPresent = apkAssets.any { isSplitConfig(it.name) }
        val apks = apkAssets.filterNot { isSplitConfig(it.name) }
        if (apks.isEmpty()) return ApkAssetSelection.Unavailable
        if (splitSetPresent && apks.any { it.name.equals("base.apk", ignoreCase = true) }) {
            return ApkAssetSelection.Unavailable
        }

        apks.filter { isUniversal(it.name) }
            .takeIf { it.isNotEmpty() }
            ?.let { candidates ->
                return candidates.toSelection()
            }

        val unlabeled = apks.filter { abiForName(it.name) == null }
        if (unlabeled.isNotEmpty()) return unlabeled.toSelection()

        supportedAbis.forEach { supported ->
            apks.filter { abiForName(it.name) == normalizeAbi(supported) }
                .takeIf { it.isNotEmpty() }
                ?.let { candidates ->
                    return candidates.toSelection()
                }
        }
        return ApkAssetSelection.Unavailable
    }

    fun select(
        assets: List<GhAsset>,
        supportedAbis: List<String>,
    ): GhAsset? = (classify(assets, supportedAbis) as? ApkAssetSelection.Selected)?.asset

    internal fun variantLabel(name: String): String = when {
        installArtifactKind(name) == InstallArtifactKind.ZIP_APK_SET -> "APK set"
        installArtifactKind(name) == InstallArtifactKind.AAB -> "Android App Bundle"
        isUniversal(name) -> "Universal"
        abiForName(name) != null -> abiForName(name)!!
        else -> "Unlabeled standalone APK"
    }

    internal fun abiForName(name: String): String? {
        val normalized = name.lowercase(Locale.US)
        return when {
            ARM64.containsMatchIn(normalized) -> "arm64-v8a"
            ARM_V7.containsMatchIn(normalized) -> "armeabi-v7a"
            X86_64.containsMatchIn(normalized) -> "x86_64"
            X86.containsMatchIn(normalized) -> "x86"
            else -> null
        }
    }

    private fun isUniversal(name: String): Boolean =
        UNIVERSAL.containsMatchIn(name.lowercase(Locale.US))

    private fun isSplitConfig(name: String): Boolean =
        SPLIT_CONFIG.matches(name.lowercase(Locale.US))

    private fun normalizeAbi(abi: String): String? = when (abi.lowercase(Locale.US)) {
        "arm64-v8a", "arm64_v8a", "aarch64" -> "arm64-v8a"
        "armeabi-v7a", "armeabi_v7a", "armv7", "armv7a" -> "armeabi-v7a"
        "x86_64", "x86-64", "amd64" -> "x86_64"
        "x86", "i686" -> "x86"
        else -> null
    }

    private val ARM64 = Regex("(^|[^a-z0-9])(?:arm64[-_]?v8a|aarch64)([^a-z0-9]|$)")
    private val ARM_V7 = Regex("(^|[^a-z0-9])(?:armeabi[-_]?v7a|armv7a?)([^a-z0-9]|$)")
    private val X86_64 = Regex("(^|[^a-z0-9])(?:x86[-_]?64|amd64)([^a-z0-9]|$)")
    private val X86 = Regex("(^|[^a-z0-9])(?:x86|i686)([^a-z0-9]|$)")
    private val UNIVERSAL = Regex("(^|[^a-z0-9])(?:universal|noarch|all)([^a-z0-9]|$)")
    private val SPLIT_CONFIG = Regex("^split_config\\.[a-z0-9]+(?:[._-][a-z0-9]+)*\\.apk$")
}

internal sealed interface ApkAssetSelection {
    data class Selected(val asset: GhAsset) : ApkAssetSelection
    data class SelectionRequired(val candidates: List<GhAsset>) : ApkAssetSelection
    data object Unavailable : ApkAssetSelection
}

private fun List<GhAsset>.toSelection(): ApkAssetSelection = when (size) {
    0 -> ApkAssetSelection.Unavailable
    1 -> ApkAssetSelection.Selected(single())
    else -> ApkAssetSelection.SelectionRequired(this)
}
