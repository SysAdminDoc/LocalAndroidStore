package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.GhAsset
import kotlinx.serialization.Serializable

enum class ReleaseChannel(val key: String, val label: String) {
    STABLE("stable", "stable"),
    BETA("beta", "beta"),
    ALPHA("alpha", "alpha"),
    NIGHTLY("nightly", "nightly"),
    RC("rc", "rc"),
    DEV("dev", "dev"),
    PREVIEW("preview", "pre"),
    ;

    companion object {
        fun fromKey(key: String?): ReleaseChannel? =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

fun deriveReleaseChannel(tagName: String, prerelease: Boolean): ReleaseChannel {
    val tag = tagName.lowercase()
    return when {
        tag.contains("nightly") || tag.contains("canary") -> ReleaseChannel.NIGHTLY
        tag.contains("alpha") -> ReleaseChannel.ALPHA
        tag.contains("beta") -> ReleaseChannel.BETA
        RC_TAG_PATTERN.containsMatchIn(tag) -> ReleaseChannel.RC
        tag.contains("dev") -> ReleaseChannel.DEV
        prerelease -> ReleaseChannel.PREVIEW
        else -> ReleaseChannel.STABLE
    }
}

private val RC_TAG_PATTERN = Regex("""(^|[-_.])rc([0-9]|[-_.]|$)""")

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
    val minSdk: Int? = null,
    val assetChoices: List<GhAsset> = emptyList(),
    val antiFeatures: Set<String> = emptySet(),
    /** True when this card came from a bounded, stale snapshot after a transient lookup failure. */
    val isStale: Boolean = false,
) {
    val handle: String get() = "$owner/$repo"

    val releaseChannel: ReleaseChannel
        get() = deriveReleaseChannel(tagName, prerelease)

    /** Returns a human-readable label for non-stable releases. */
    val channelLabel: String?
        get() = releaseChannel.label.takeUnless { releaseChannel == ReleaseChannel.STABLE }
}

enum class CardStatus {
    NotInstalled,
    Unmanaged,
    Installed,
    Archived,
    ReleaseAvailable,
    UpdateAvailable,
    ReinstallAvailable,
    DowngradeAvailable,
    Working,
    Error,
    SignatureMismatch,
    PermissionReview,
}
