package com.sysadmin.lasstore.data

import android.content.Context
import com.sysadmin.lasstore.domain.AppInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * The immutable GitHub release asset identity used for version classification.
 *
 * GitHub asset IDs are preferred when available. The remaining fields keep records stable for
 * older cached API responses and test fixtures where GitHub did not provide an ID.
 */
@Serializable
data class ReleaseAssetIdentity(
    val assetId: Long,
    val tagName: String,
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val prerelease: Boolean,
    val digest: String? = null,
) {
    companion object {
        fun from(info: AppInfo) = ReleaseAssetIdentity(
            assetId = info.asset.id,
            tagName = info.tagName,
            name = info.asset.name,
            downloadUrl = info.asset.browserDownloadUrl,
            size = info.asset.size,
            prerelease = info.prerelease,
            digest = info.asset.digest,
        )
    }
}

@Serializable
data class InspectedReleaseIdentity(
    val asset: ReleaseAssetIdentity,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String?,
    val signerSha256: String,
    val lineageSha256: List<String> = emptyList(),
)

@Serializable
enum class InstallProvenance {
    LOCAL_ANDROID_STORE,
    EXTERNAL_UNMANAGED,
    USER_ADOPTED,
}

/**
 * Source-scoped install identity for one catalog repository.
 *
 * Installed fields describe the last APK successfully installed through LocalAndroidStore.
 * [inspectedRelease] describes the current release only after its APK was actually parsed; a tag
 * is never treated as a version code.
 */
@Serializable
data class AppIdEntry(
    val sourceKey: String,
    val owner: String,
    val repo: String,
    val applicationId: String,
    val installedTagName: String,
    val installedVersionCode: Long? = null,
    val installedVersionName: String? = null,
    val installedSignerSha256: String? = null,
    val installedAsset: ReleaseAssetIdentity? = null,
    val inspectedRelease: InspectedReleaseIdentity? = null,
    val provenance: InstallProvenance = InstallProvenance.LOCAL_ANDROID_STORE,
    val provenanceRecordedAtEpochMillis: Long? = null,
)

/**
 * Persistent, source-scoped package/version/signer/asset identity cache.
 *
 * v1 keyed records by owner/repo only. On first access, one source claims a legacy record and the
 * ambiguous keys are removed so a second source cannot inherit the same package identity.
 */
class AppIdCache(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Synchronized
    fun get(sourceKey: String, owner: String, repo: String): AppIdEntry? {
        val key = recordKey(sourceKey, owner, repo)
        prefs.getString(key, null)?.let { encoded ->
            return runCatching { json.decodeFromString<AppIdEntry>(encoded) }.getOrNull()
        }

        val legacyApplicationId = prefs.getString(legacyAppIdKey(owner, repo), null)
            ?: return null
        val legacyTagName = prefs.getString(legacyTagKey(owner, repo), null)
            ?: return null
        val migrated = AppIdEntry(
            sourceKey = sourceKey,
            owner = owner,
            repo = repo,
            applicationId = legacyApplicationId,
            installedTagName = legacyTagName,
        )
        prefs.edit()
            .putString(key, json.encodeToString(migrated))
            .remove(legacyAppIdKey(owner, repo))
            .remove(legacyTagKey(owner, repo))
            .commit()
        return migrated
    }

    @Synchronized
    fun recordInspected(info: AppInfo, metadata: ApkMetadata): AppIdEntry? {
        val existing = get(info.sourceKey, info.owner, info.repo) ?: return null
        val inspected = InspectedReleaseIdentity(
            asset = ReleaseAssetIdentity.from(info),
            applicationId = metadata.applicationId,
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            signerSha256 = metadata.signingSha256,
            lineageSha256 = metadata.lineageSha256,
        )
        return existing.copy(inspectedRelease = inspected).also(::put)
    }

    @Synchronized
    fun recordInstalled(info: AppInfo, metadata: ApkMetadata): AppIdEntry {
        val asset = ReleaseAssetIdentity.from(info)
        return AppIdEntry(
            sourceKey = info.sourceKey,
            owner = info.owner,
            repo = info.repo,
            applicationId = metadata.applicationId,
            installedTagName = info.tagName,
            installedVersionCode = metadata.versionCode,
            installedVersionName = metadata.versionName,
            installedSignerSha256 = metadata.signingSha256,
            installedAsset = asset,
            inspectedRelease = InspectedReleaseIdentity(
                asset = asset,
                applicationId = metadata.applicationId,
                versionCode = metadata.versionCode,
                versionName = metadata.versionName,
                signerSha256 = metadata.signingSha256,
                lineageSha256 = metadata.lineageSha256,
            ),
            provenance = InstallProvenance.LOCAL_ANDROID_STORE,
            provenanceRecordedAtEpochMillis = System.currentTimeMillis(),
        ).also(::put)
    }

    /**
     * Records a verified package that was already installed before LocalAndroidStore managed it.
     * This is deliberately not a managed install record: background updates remain ineligible
     * until the user explicitly adopts the package and its current signer.
     */
    @Synchronized
    fun recordExternalObservation(
        info: AppInfo,
        metadata: ApkMetadata,
        installed: InstalledInfo,
    ): AppIdEntry {
        require(metadata.applicationId == installed.applicationId) {
            "Observed package does not match the inspected APK"
        }
        val existing = get(info.sourceKey, info.owner, info.repo)
        check(existing == null || existing.provenance == InstallProvenance.EXTERNAL_UNMANAGED) {
            "A LocalAndroidStore-managed install cannot be replaced by an external observation"
        }
        val asset = ReleaseAssetIdentity.from(info)
        return AppIdEntry(
            sourceKey = info.sourceKey,
            owner = info.owner,
            repo = info.repo,
            applicationId = installed.applicationId,
            installedTagName = info.tagName,
            installedVersionCode = installed.versionCode,
            installedVersionName = installed.versionName,
            installedSignerSha256 = installed.currentSignerSha256,
            installedAsset = asset.takeIf {
                metadata.versionCode == installed.versionCode &&
                    metadata.signingSha256 == installed.currentSignerSha256
            },
            inspectedRelease = InspectedReleaseIdentity(
                asset = asset,
                applicationId = metadata.applicationId,
                versionCode = metadata.versionCode,
                versionName = metadata.versionName,
                signerSha256 = metadata.signingSha256,
                lineageSha256 = metadata.lineageSha256,
            ),
            provenance = InstallProvenance.EXTERNAL_UNMANAGED,
            provenanceRecordedAtEpochMillis = System.currentTimeMillis(),
        ).also(::put)
    }

    /** Promote an observed external install to a user-adopted managed record. */
    @Synchronized
    fun adoptExternal(
        sourceKey: String,
        owner: String,
        repo: String,
        installed: InstalledInfo,
    ): AppIdEntry? {
        val existing = get(sourceKey, owner, repo) ?: return null
        if (
            existing.provenance != InstallProvenance.EXTERNAL_UNMANAGED ||
            existing.applicationId != installed.applicationId
        ) {
            return null
        }
        val inspected = existing.inspectedRelease
        val installedAsset = inspected?.takeIf {
            it.applicationId == installed.applicationId &&
                it.versionCode == installed.versionCode &&
                it.signerSha256 == installed.currentSignerSha256
        }?.asset
        return existing.copy(
            installedVersionCode = installed.versionCode,
            installedVersionName = installed.versionName,
            installedSignerSha256 = installed.currentSignerSha256,
            installedAsset = installedAsset,
            provenance = InstallProvenance.USER_ADOPTED,
            provenanceRecordedAtEpochMillis = System.currentTimeMillis(),
        ).also(::put)
    }

    @Synchronized
    fun reconcileInstalled(
        entry: AppIdEntry,
        installed: InstalledInfo,
    ): AppIdEntry {
        val sameRecordedVersion = entry.installedVersionCode == installed.versionCode
        val reconciled = entry.copy(
            applicationId = installed.applicationId,
            installedVersionCode = installed.versionCode,
            installedVersionName = installed.versionName,
            installedSignerSha256 = installed.currentSignerSha256,
            installedAsset = entry.installedAsset.takeIf { sameRecordedVersion },
        )
        if (reconciled != entry) put(reconciled)
        return reconciled
    }

    @Synchronized
    internal fun put(entry: AppIdEntry) {
        check(prefs.edit()
            .putString(
                recordKey(entry.sourceKey, entry.owner, entry.repo),
                json.encodeToString(entry),
            )
            .commit()) { "Could not persist installed application identity" }
    }

    private fun recordKey(sourceKey: String, owner: String, repo: String): String =
        "record:v2:${normalize(sourceKey)}/${normalize(owner)}/${normalize(repo)}"

    private fun legacyAppIdKey(owner: String, repo: String) = "appid:$owner/$repo"
    private fun legacyTagKey(owner: String, repo: String) = "tag:$owner/$repo"
    private fun normalize(value: String): String = value.trim().lowercase(Locale.US)

    private companion object {
        const val PREFS_NAME = "las_appid_cache"
    }
}
