package com.sysadmin.lasstore.data

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class GitHubSource(
    val user: String = DEFAULT_GITHUB_USER,
    val topic: String = DEFAULT_GITHUB_TOPIC,
    val filterByTopic: Boolean = false,
    val showPrereleases: Boolean = false,
    val enabled: Boolean = true,
) {
    val key: String get() = sourceKey(user)
    val displayName: String get() = user.trim().ifBlank { DEFAULT_GITHUB_USER }
}

data class AppSettings(
    val githubUser: String = DEFAULT_GITHUB_USER,
    val topic: String = DEFAULT_GITHUB_TOPIC,
    val filterByTopic: Boolean = false,
    val showPrereleases: Boolean = false,
    val sources: List<GitHubSource> = listOf(
        GitHubSource(
            user = githubUser,
            topic = topic,
            filterByTopic = filterByTopic,
            showPrereleases = showPrereleases,
        )
    ),
)

const val DEFAULT_GITHUB_USER = "SysAdminDoc"
const val DEFAULT_GITHUB_TOPIC = "android-app"

fun sourceKey(user: String): String =
    user.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_.-]+"), "-").ifBlank { DEFAULT_GITHUB_USER.lowercase() }

fun normalizeSources(sources: List<GitHubSource>): List<GitHubSource> {
    val cleaned = sources
        .mapNotNull { source ->
            val user = source.user.trim()
            if (user.isBlank()) {
                null
            } else {
                source.copy(
                    user = user,
                    topic = source.topic.trim().ifBlank { DEFAULT_GITHUB_TOPIC },
                )
            }
        }
        .distinctBy { it.key }

    return cleaned.ifEmpty { listOf(GitHubSource()) }
}

fun legacySource(settings: AppSettings): GitHubSource = GitHubSource(
    user = settings.githubUser,
    topic = settings.topic,
    filterByTopic = settings.filterByTopic,
    showPrereleases = settings.showPrereleases,
)
