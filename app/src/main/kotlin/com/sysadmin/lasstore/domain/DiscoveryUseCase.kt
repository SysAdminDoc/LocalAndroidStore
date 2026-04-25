package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.GhRelease
import com.sysadmin.lasstore.data.GitHubSource
import com.sysadmin.lasstore.data.GitHubClient
import com.sysadmin.lasstore.data.Logger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class DiscoveryUseCase(
    private val github: GitHubClient,
    private val logger: Logger,
    private val patForSource: (String) -> String = { "" },
) {
    suspend fun discover(sources: List<GitHubSource>): List<AppInfo> = coroutineScope {
        sources.filter { it.enabled }.map { source ->
            async { discoverSource(source) }
        }.awaitAll().flatten()
            .distinctBy { "${it.sourceKey}/${it.owner}/${it.repo}/${it.tagName}/${it.asset.name}" }
            .sortedWith(compareByDescending<AppInfo> { it.stars }.thenBy { it.displayName.lowercase() })
    }

    private suspend fun discoverSource(source: GitHubSource): List<AppInfo> = coroutineScope {
        val user = source.user.trim()
        if (user.isEmpty()) return@coroutineScope emptyList()
        val pat = patForSource(source.key)

        val repos = runCatching { github.listUserRepos(user, patOverride = pat) }
            .onFailure { logger.error("Discovery", "listUserRepos failed for $user", it) }
            .getOrElse { return@coroutineScope emptyList() }

        val candidates = repos
            .filter { !it.archived && !it.fork }
            .filter {
                if (source.filterByTopic) it.topics.contains(source.topic.trim()) else true
            }

        candidates.map { repo ->
            async {
                runCatching {
                    val release = github.latestRelease(
                        owner = repo.owner.login,
                        repo = repo.name,
                        includePrereleases = source.showPrereleases,
                        patOverride = pat,
                    )
                        ?: return@async null
                    val asset = pickPrimaryApk(release) ?: return@async null
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
                    )
                }.onFailure { logger.warn("Discovery", "${repo.owner.login}/${repo.name}: ${it.message}") }
                    .getOrNull()
            }
        }.awaitAll().filterNotNull()
            .sortedWith(compareByDescending<AppInfo> { it.stars }.thenBy { it.displayName.lowercase() })
    }

    /**
     * Choose the primary APK asset:
     *   1. Skip .apk.idsig sidecars and .aab.
     *   2. Prefer one whose name contains "universal".
     *   3. Otherwise pick the largest .apk.
     */
    private fun pickPrimaryApk(release: GhRelease): GhAsset? {
        val apks = release.assets.filter {
            val n = it.name.lowercase()
            n.endsWith(".apk") && !n.endsWith(".apk.idsig")
        }
        if (apks.isEmpty()) return null
        apks.firstOrNull { it.name.lowercase().contains("universal") }?.let { return it }
        return apks.maxByOrNull { it.size }
    }
}
