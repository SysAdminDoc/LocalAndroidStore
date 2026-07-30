package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.AppIdEntry
import com.sysadmin.lasstore.data.ReleaseAssetIdentity

enum class ReleaseVersionRelation {
    InstalledAsset,
    UninspectedRelease,
    Upgrade,
    SameVersionRelease,
    Downgrade,
    PackageMismatch,
}

/**
 * Classifies a release from inspected manifest metadata, never from its Git tag.
 */
fun classifyReleaseVersion(
    info: AppInfo,
    entry: AppIdEntry,
    installedVersionCode: Long,
): ReleaseVersionRelation {
    val currentAsset = ReleaseAssetIdentity.from(info)
    if (entry.installedAsset == currentAsset) {
        return ReleaseVersionRelation.InstalledAsset
    }
    val inspected = entry.inspectedRelease
        ?.takeIf { it.asset == currentAsset }
        ?: return ReleaseVersionRelation.UninspectedRelease
    if (inspected.applicationId != entry.applicationId) {
        return ReleaseVersionRelation.PackageMismatch
    }
    return when {
        inspected.versionCode > installedVersionCode -> ReleaseVersionRelation.Upgrade
        inspected.versionCode == installedVersionCode -> ReleaseVersionRelation.SameVersionRelease
        else -> ReleaseVersionRelation.Downgrade
    }
}
