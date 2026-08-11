package com.sysadmin.lasstore.data

import com.sysadmin.lasstore.domain.DiscoveredApp
import com.sysadmin.lasstore.domain.Release
import com.sysadmin.lasstore.domain.ReleaseAsset
import com.sysadmin.lasstore.domain.SourcePlugin
import com.sysadmin.lasstore.domain.VerifyResult
import com.sysadmin.lasstore.domain.githubTopicTag
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Adapts the existing bounded GitHub gateway to the source-plugin contract. */
class GitHubReleasesPlugin(
    private val gateway: GitHubGateway,
    private val source: GitHubSource,
    private val patOverride: String? = null,
    private val releaseHistoryPageSize: Int = 20,
) : SourcePlugin {
    override val id: String = "github:${source.key}"
    override val displayName: String = source.displayName

    override suspend fun listApps(): List<DiscoveredApp> =
        gateway.listUserReposResult(
            user = source.user,
            patOverride = patOverride,
            sourceKey = source.key,
        ).repos
            .filter { !it.archived && !it.fork }
            .filter {
                !source.filterByTopic || it.topics.any { topic ->
                    topic.equals(source.topic.trim(), ignoreCase = true)
                }
            }
            .map { repo ->
                DiscoveredApp(
                    applicationId = repo.fullName,
                    displayName = repo.name,
                    description = repo.description,
                    homepageUrl = repo.htmlUrl,
                    tags = repo.topics.mapNotNull(::githubTopicTag).toSet(),
                )
            }

    override suspend fun getReleases(applicationId: String): List<Release> {
        val parts = applicationId.trim().split('/', limit = 2)
        require(parts.size == 2 && parts.all { it.isNotBlank() }) {
            "GitHub application id must be owner/repository"
        }
        return gateway.listReleaseHistory(
            owner = parts[0],
            repo = parts[1],
            includePrereleases = source.showPrereleases,
            page = 1,
            perPage = releaseHistoryPageSize,
            patOverride = patOverride,
            sourceKey = source.key,
        ).releases.map { release ->
            Release(
                id = release.htmlUrl.ifBlank { "${parts[0]}/${parts[1]}#${release.tagName}" },
                applicationId = applicationId,
                versionName = release.tagName.removePrefix("v").removePrefix("V"),
                publishedAt = release.publishedAt,
                prerelease = release.prerelease,
                body = release.body?.takeIf { it.isNotBlank() },
                assets = release.assets.map { asset ->
                    ReleaseAsset(
                        id = asset.id.toString(),
                        name = asset.name,
                        downloadUrl = asset.browserDownloadUrl,
                        sizeBytes = asset.size,
                        sha256 = asset.digest,
                    )
                },
            )
        }
    }

    override suspend fun resolveDownloadUrl(release: Release): String {
        val asset = release.apkAssets().firstOrNull()
            ?: throw IllegalArgumentException("Release has no standalone APK asset")
        require(asset.downloadUrl.toHttpUrlOrNull()?.scheme.equals("https", ignoreCase = true)) {
            "GitHub release asset URL must use HTTPS"
        }
        return asset.downloadUrl
    }

    override suspend fun verify(release: Release): VerifyResult {
        val asset = release.apkAssets().firstOrNull()
            ?: return VerifyResult.Rejected("Release has no standalone APK asset")
        val suppliedDigest = asset.sha256
        if (suppliedDigest == null) {
            return VerifyResult.Unverified(
                "GitHub did not publish a SHA-256 digest for the selected APK",
            )
        }
        if (normalizeSha256Digest(suppliedDigest) == null) {
            return VerifyResult.Rejected("GitHub published an invalid SHA-256 digest")
        }
        return VerifyResult.Verified(normalizeSha256Digest(suppliedDigest))
    }

    private fun Release.apkAssets(): List<ReleaseAsset> = assets
        .filter { asset ->
            val name = asset.name.lowercase(Locale.US)
            name.endsWith(".apk") && !name.endsWith(".apk.idsig")
        }
        .sortedWith(
            compareBy<ReleaseAsset> { it.name.lowercase(Locale.US) }
                .thenBy { it.id },
        )
}
