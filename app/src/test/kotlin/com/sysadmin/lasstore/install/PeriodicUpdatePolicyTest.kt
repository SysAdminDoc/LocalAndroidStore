package com.sysadmin.lasstore.install

import com.sysadmin.lasstore.data.AppIdEntry
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.InstallProvenance
import com.sysadmin.lasstore.data.InstalledInfo
import com.sysadmin.lasstore.data.ReleaseAssetIdentity
import com.sysadmin.lasstore.domain.AppInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicUpdatePolicyTest {
    @Test
    fun changedDigestedAssetForManagedInstallIsEligible() {
        val installedRelease = appInfo("v1", assetId = 1, digest = DIGEST_A)
        val currentRelease = appInfo("v2", assetId = 2, digest = DIGEST_B)

        assertTrue(
            shouldQueuePeriodicUpdate(
                info = currentRelease,
                cached = managedEntry(installedRelease),
                installed = installedInfo(),
                ignored = false,
                queued = null,
            ),
        )
    }

    @Test
    fun policyRejectsUnsafeOrAlreadyQueuedCandidates() {
        val installedRelease = appInfo("v1", assetId = 1, digest = DIGEST_A)
        val currentRelease = appInfo("v2", assetId = 2, digest = DIGEST_B)
        val payload = QueuedUpdatePayload.from(currentRelease)
        val queued = QueuedUpdateStatus(
            workName = payload.workName,
            sourceKey = payload.sourceKey,
            owner = payload.owner,
            repo = payload.repo,
            displayName = payload.displayName,
            phase = QueuedUpdatePhase.Queued,
            attempt = 0,
            maxAttempts = 3,
            message = "queued",
            updatedAtEpochMillis = 1L,
            queuedPayload = payload,
        )

        assertFalse(
            shouldQueuePeriodicUpdate(
                currentRelease,
                managedEntry(installedRelease),
                installedInfo(),
                ignored = false,
                queued = queued,
            ),
        )
        assertFalse(
            shouldQueuePeriodicUpdate(
                currentRelease.copy(isStale = true),
                managedEntry(installedRelease),
                installedInfo(),
                ignored = false,
                queued = null,
            ),
        )
        assertFalse(
            shouldQueuePeriodicUpdate(
                currentRelease.copy(asset = currentRelease.asset.copy(digest = null)),
                managedEntry(installedRelease),
                installedInfo(),
                ignored = false,
                queued = null,
            ),
        )
        assertFalse(
            shouldQueuePeriodicUpdate(
                currentRelease,
                managedEntry(installedRelease).copy(provenance = InstallProvenance.EXTERNAL_UNMANAGED),
                installedInfo(),
                ignored = false,
                queued = null,
            ),
        )
    }

    @Test
    fun policyRejectsSignerThatNoLongerMatchesTheStoredPin() {
        val installedRelease = appInfo("v1", assetId = 1, digest = DIGEST_A)
        val currentRelease = appInfo("v2", assetId = 2, digest = DIGEST_B)

        assertFalse(
            shouldQueuePeriodicUpdate(
                info = currentRelease,
                cached = managedEntry(installedRelease),
                installed = installedInfo(currentSignerSha256 = "different"),
                ignored = false,
                queued = null,
                pinnedSignerSha256 = "pinned",
            ),
        )
    }

    private fun managedEntry(installedRelease: AppInfo) = AppIdEntry(
        sourceKey = installedRelease.sourceKey,
        owner = installedRelease.owner,
        repo = installedRelease.repo,
        applicationId = PACKAGE_NAME,
        installedTagName = installedRelease.tagName,
        installedVersionCode = 1L,
        installedAsset = ReleaseAssetIdentity.from(installedRelease),
    )

    private fun installedInfo(currentSignerSha256: String? = null) = InstalledInfo(
        applicationId = PACKAGE_NAME,
        versionName = "1",
        versionCode = 1L,
        currentSignerSha256 = currentSignerSha256,
    )

    private fun appInfo(tag: String, assetId: Long, digest: String?) = AppInfo(
        owner = "owner",
        repo = "app",
        sourceKey = "github",
        sourceLabel = "GitHub",
        displayName = "App",
        description = null,
        stars = 0,
        htmlUrl = "https://github.com/owner/app",
        tagName = tag,
        versionName = tag,
        versionCode = null,
        applicationId = PACKAGE_NAME,
        asset = GhAsset(
            id = assetId,
            name = "app.apk",
            browserDownloadUrl = "https://example.invalid/$tag.apk",
            size = 100L,
            digest = digest,
        ),
        publishedAt = null,
        prerelease = false,
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val DIGEST_A = "a".repeat(64)
        val DIGEST_B = "b".repeat(64)
    }
}
