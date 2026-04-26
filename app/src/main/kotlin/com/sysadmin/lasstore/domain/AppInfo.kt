package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.GhAsset

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

enum class CardStatus { NotInstalled, Installed, UpdateAvailable, Working, Error, SignatureMismatch, PermissionReview }
