package com.sysadmin.lasstore.data

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class AppThemeMode {
    Dark,
    Light,
}

@Serializable
enum class AccentColor {
    Mauve,
    Sapphire,
    Green,
    Yellow,
    Red,
    Pink,
    Teal,
    Lavender,
}

@Serializable
data class GitHubSource(
    val user: String = DEFAULT_GITHUB_USER,
    val topic: String = DEFAULT_GITHUB_TOPIC,
    val filterByTopic: Boolean = false,
    val showPrereleases: Boolean = false,
    val enabled: Boolean = true,
    val accent: AccentColor? = null,
    val brandingUrl: String = "",
    val threatModel: String = "",
) {
    val key: String get() = sourceKey(user)
    val displayName: String get() = user.trim().ifBlank { DEFAULT_GITHUB_USER }
}

@Serializable
data class FdroidSource(
    val endpointUrl: String = "",
    val enabled: Boolean = true,
    val accent: AccentColor? = null,
    val brandingUrl: String = "",
    val threatModel: String = "",
) {
    val key: String get() = FdroidRepositoryTrust.sourceKey(endpointUrl)
    val displayName: String get() = FdroidRepositoryTrust.displayName(endpointUrl)
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
    val fdroidSources: List<FdroidSource> = emptyList(),
    val hideUnverifiedSources: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.Dark,
    val accentColor: AccentColor = AccentColor.Mauve,
    val dynamicColor: Boolean = false,
    val highContrast: Boolean = false,
    val dailyUpdateCap: Int = 3,
    val sourceDirectoryUrl: String = "",
    val socks5ProxyEnabled: Boolean = false,
    val socks5ProxyHost: String = "127.0.0.1",
    val socks5ProxyPort: Int = 9050,
)

const val DEFAULT_GITHUB_USER = "SysAdminDoc"
const val DEFAULT_GITHUB_TOPIC = "android-app"
const val MAX_SOURCE_THREAT_MODEL_LENGTH = 4_000

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
                    brandingUrl = source.brandingUrl.trim(),
                    threatModel = normalizeThreatModel(
                        source.threatModel,
                        defaultGitHubThreatModel(user),
                    ),
                )
            }
        }
        .distinctBy { it.key }

    return cleaned.ifEmpty {
        listOf(
            GitHubSource(
                threatModel = defaultGitHubThreatModel(DEFAULT_GITHUB_USER),
            ),
        )
    }
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
    sources.forEachIndexed { index, source ->
        validateSourceBrandingUrl(source.brandingUrl)?.let { error ->
            return "Source ${index + 1}: $error"
        }
        validateSourceThreatModel(source.threatModel)?.let { error ->
            return "Source ${index + 1}: $error"
        }
    }
    return null
}

fun normalizeFdroidSources(sources: List<FdroidSource>): List<FdroidSource> = sources
    .mapNotNull { source ->
        runCatching {
            val endpoint = FdroidRepositoryTrust.canonicalEndpoint(source.endpointUrl)
            source.copy(
                endpointUrl = endpoint,
                brandingUrl = source.brandingUrl.trim(),
                threatModel = normalizeThreatModel(
                    source.threatModel,
                    defaultFdroidThreatModel(endpoint),
                ),
            )
        }.getOrNull()
    }
    .distinctBy { it.key }

fun validateFdroidSources(sources: List<FdroidSource>): String? {
    sources.forEachIndexed { index, source ->
        if (source.endpointUrl.trim().isBlank()) {
            return "Enter an F-Droid index URL with a fingerprint for repository ${index + 1}."
        }
        val failure = runCatching {
            FdroidRepositoryTrust.parseEndpoint(source.endpointUrl)
        }.exceptionOrNull()
        if (failure != null) {
            return "F-Droid repository ${index + 1}: ${failure.message ?: "the endpoint is invalid"}"
        }
    }
    val duplicate = sources
        .withIndex()
        .groupBy {
            runCatching { FdroidRepositoryTrust.sourceKey(it.value.endpointUrl) }.getOrNull()
        }
        .values
        .firstOrNull { it.size > 1 }
    if (duplicate != null) {
        return "F-Droid repository is listed more than once. Keep one endpoint entry."
    }
    sources.forEachIndexed { index, source ->
        validateSourceBrandingUrl(source.brandingUrl)?.let { error ->
            return "F-Droid repository ${index + 1}: $error"
        }
        validateSourceThreatModel(source.threatModel)?.let { error ->
            return "F-Droid repository ${index + 1}: $error"
        }
    }
    return null
}

fun legacySource(settings: AppSettings): GitHubSource = GitHubSource(
    user = settings.githubUser,
    topic = settings.topic,
    filterByTopic = settings.filterByTopic,
    showPrereleases = settings.showPrereleases,
)

fun accentForSource(settings: AppSettings, sourceKey: String): AccentColor =
    settings.sources.firstOrNull { it.key == sourceKey }?.accent
        ?: settings.fdroidSources.firstOrNull { it.key == sourceKey }?.accent
        ?: settings.accentColor

fun validateSourceThreatModel(value: String): String? {
    if (value.length > MAX_SOURCE_THREAT_MODEL_LENGTH) {
        return "Threat model must be $MAX_SOURCE_THREAT_MODEL_LENGTH characters or fewer."
    }
    if (value.any { it.isISOControl() && it !in setOf('\n', '\r', '\t') }) {
        return "Threat model contains an unsupported control character."
    }
    return null
}

private fun normalizeThreatModel(value: String, fallback: String): String =
    value.trim().ifBlank { fallback }.take(MAX_SOURCE_THREAT_MODEL_LENGTH)

fun defaultGitHubThreatModel(user: String): String =
    "The GitHub account '$user' controls repository metadata and release assets. " +
        "LocalAndroidStore uses HTTPS, verifies the APK package, version, digest, and publisher-key continuity, " +
        "and blocks a signer change unless the user completes the separate trust-recovery review. " +
        "A stolen repository or signing key could still publish a valid-looking update until local evidence or independent review rejects it."

fun defaultFdroidThreatModel(endpoint: String): String =
    "The F-Droid repository operator controls the index and APK locations for $endpoint. " +
        "LocalAndroidStore requires an HTTPS endpoint with a fingerprint pin, verifies signed entry metadata when available, " +
        "and checks package, version, digest, and publisher continuity before installation. " +
        "A compromised repository or signing key could publish a valid-looking update until the local fingerprint or publisher evidence rejects it."
