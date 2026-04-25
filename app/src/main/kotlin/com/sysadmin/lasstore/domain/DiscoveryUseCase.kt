package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.AppSettings
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.GhRelease
import com.sysadmin.lasstore.data.GitHubClient
import com.sysadmin.lasstore.data.Logger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class DiscoveryUseCase(
    private val github: GitHubClient,
    private val logger: Logger,
) {
    suspend fun discover(settings: AppSettings): List<AppInfo> = coroutineScope {
        val user = settings.githubUser.trim()
        if (user.isEmpty()) return@coroutineScope emptyList()

        val repos = runCatching { github.listUserRepos(user) }
            .onFailure { logger.error("Discovery", "listUserRepos failed", it) }
            .getOrElse { return@coroutineScope emptyList() }

        val candidates = repos
            .filter { !it.archived && !it.fork }
            .filter {
                if (settings.filterByTopic) it.topics.contains(settings.topic.trim()) else true
            }

        candidates.map { repo ->
            async {
                runCatching {
                    val release = github.latestRelease(repo.owner.login, repo.name, settings.showPrereleases)
                        ?: return@async null
                    val asset = pickPrimaryApk(release) ?: return@async null
                    AppInfo(
                        owner = repo.owner.login,
                        repo = repo.name,
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
