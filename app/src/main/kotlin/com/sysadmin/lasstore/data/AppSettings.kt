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

@Serializable
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

fun validateSources(sources: List<GitHubSource>): String? {
    val blankIndex = sources.indexOfFirst { it.user.trim().isBlank() }
    if (blankIndex >= 0) {
        return "Enter a GitHub user or organization for source ${blankIndex + 1}."
    }
    val duplicate = sources
        .withIndex()
        .groupBy { it.value.key }
        .values
        .firstOrNull { it.size > 1 }
    if (duplicate != null) {
        val label = duplicate.first().value.user.trim()
        return "Source '$label' is listed more than once. Keep one entry or use a different owner."
    }
    return null
}

fun legacySource(settings: AppSettings): GitHubSource = GitHubSource(
    user = settings.githubUser,
    topic = settings.topic,
    filterByTopic = settings.filterByTopic,
    showPrereleases = settings.showPrereleases,
)
