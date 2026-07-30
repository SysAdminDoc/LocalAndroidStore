package com.sysadmin.lasstore.domain

import com.sysadmin.lasstore.data.AppIdEntry
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.InspectedReleaseIdentity
import com.sysadmin.lasstore.data.ReleaseAssetIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseVersionPolicyTest {
    @Test
    fun tagChangeWithoutInspectionIsNotCalledAnUpdate() {
        assertEquals(
            ReleaseVersionRelation.UninspectedRelease,
            classifyReleaseVersion(release(tag = "v2", code = null), installedEntry(), 10),
        )
    }

    @Test
    fun higherVersionCodeIsUpgradeAcrossStableAndPrereleaseChannels() {
        assertEquals(
            ReleaseVersionRelation.Upgrade,
            classifyReleaseVersion(
                release(tag = "v11-beta1", code = 11, prerelease = true),
                installedEntry().withInspected(
                    release(tag = "v11-beta1", code = 11, prerelease = true),
                    code = 11,
                ),
                10,
            ),
        )
    }

    @Test
    fun equalCodeRetagIsSameVersionReleaseInsteadOfUpdate() {
        val retag = release(tag = "v10-hotfix", code = 10)
        assertEquals(
            ReleaseVersionRelation.SameVersionRelease,
            classifyReleaseVersion(
                retag,
                installedEntry().withInspected(retag, code = 10),
                10,
            ),
        )
    }

    @Test
    fun stableTransitionBelowInstalledPrereleaseIsDowngrade() {
        val stable = release(tag = "v10", code = 10)
        assertEquals(
            ReleaseVersionRelation.Downgrade,
            classifyReleaseVersion(
                stable,
                installedEntry(installedPrerelease = true).withInspected(stable, code = 10),
                11,
            ),
        )
    }

    @Test
    fun differentPackageIsRejectedBeforeVersionComparison() {
        val release = release(tag = "v11", code = 11)
        val inspected = installedEntry().withInspected(
            info = release,
            code = 11,
            applicationId = "com.example.other",
        )
        assertEquals(
            ReleaseVersionRelation.PackageMismatch,
            classifyReleaseVersion(release, inspected, 10),
        )
    }

    private fun installedEntry(installedPrerelease: Boolean = false): AppIdEntry {
        val installed = release(tag = "v10-beta1", code = 10, prerelease = installedPrerelease)
        return AppIdEntry(
            sourceKey = "source-a",
            owner = "owner",
            repo = "repo",
            applicationId = APPLICATION_ID,
            installedTagName = installed.tagName,
            installedVersionCode = 10,
            installedVersionName = "10",
            installedSignerSha256 = "AA",
            installedAsset = ReleaseAssetIdentity.from(installed),
        )
    }

    private fun AppIdEntry.withInspected(
        info: AppInfo,
        code: Long,
        applicationId: String = APPLICATION_ID,
    ): AppIdEntry = copy(
        inspectedRelease = InspectedReleaseIdentity(
            asset = ReleaseAssetIdentity.from(info),
            applicationId = applicationId,
            versionCode = code,
            versionName = code.toString(),
            signerSha256 = "AA",
        ),
    )

    private fun release(
        tag: String,
        code: Long?,
        prerelease: Boolean = false,
    ) = AppInfo(
        owner = "owner",
        repo = "repo",
        sourceKey = "source-a",
        sourceLabel = "Source A",
        displayName = "Repo",
        description = null,
        stars = 0,
        htmlUrl = "https://github.com/owner/repo",
        tagName = tag,
        versionName = code?.toString(),
        versionCode = code,
        applicationId = APPLICATION_ID,
        asset = GhAsset(
            id = tag.hashCode().toLong(),
            name = "repo-$tag.apk",
            browserDownloadUrl = "https://example.invalid/$tag.apk",
            size = 100,
        ),
        publishedAt = null,
        prerelease = prerelease,
    )

    private companion object {
        const val APPLICATION_ID = "com.example.app"
    }
}
