package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.GhAsset
import kotlinx.serialization.Serializable

@Serializable
data class AppInfo(
    val owner: String,
    val repo: String,
    val sourceKey: String,
    val sourceLabel: String,
    val displayName: String,
    val description: String?,
    val stars: Int,
    val htmlUrl: String,
    val tagName: String,
    val versionName: String?,
    val versionCode: Long?,
    val applicationId: String?,
    val asset: GhAsset,
    val publishedAt: String?,
    val prerelease: Boolean,
    val releaseBody: String? = null,
    val assetChoices: List<GhAsset> = emptyList(),
    val antiFeatures: Set<String> = emptySet(),
    /** True when this card came from a bounded, stale snapshot after a transient lookup failure. */
    val isStale: Boolean = false,
) {
    val handle: String get() = "$owner/$repo"

    /**
     * Derive a human-readable channel label from the release tag and the prerelease flag.
     * Returns null for ordinary stable releases.
     */
    val channelLabel: String?
        get() {
            val t = tagName.lowercase()
            return when {
                t.contains("nightly") || t.contains("canary") -> "nightly"
                t.contains("alpha") -> "alpha"
                t.contains("beta") -> "beta"
                t.contains("-rc") || t.contains(".rc") || Regex("""[-.]rc\d""").containsMatchIn(t) -> "rc"
                prerelease -> "pre"
                else -> null
            }
        }
}

enum class CardStatus {
    NotInstalled,
    Unmanaged,
    Installed,
    ReleaseAvailable,
    UpdateAvailable,
    ReinstallAvailable,
    DowngradeAvailable,
    Working,
    Error,
    SignatureMismatch,
    PermissionReview,
}
