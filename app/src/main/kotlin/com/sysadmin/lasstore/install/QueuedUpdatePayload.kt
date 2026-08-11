package com.sysadmin.lasstore.install

import android.content.Intent
import android.os.PersistableBundle
import androidx.work.Data
import androidx.work.workDataOf
import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.ApkSignatureScheme
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.domain.AppInfo
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class QueuedUpdatePayload(
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
    val applicationId: String?,
    val assetId: Long,
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long,
    val assetContentType: String,
    val publishedAt: String?,
    val prerelease: Boolean,
    val assetDigest: String? = null,
    val generationId: String = "",
) {
    val workName: String get() = "queued-update-$sourceKey-$owner-$repo"

    fun toAppInfo(): AppInfo = AppInfo(
        owner = owner,
        repo = repo,
        sourceKey = sourceKey,
        sourceLabel = sourceLabel,
        displayName = displayName,
        description = description,
        stars = stars,
        htmlUrl = htmlUrl,
        tagName = tagName,
        versionName = versionName,
        versionCode = null,
        applicationId = applicationId,
        asset = GhAsset(
            id = assetId,
            name = assetName,
            browserDownloadUrl = assetUrl,
            size = assetSize,
            contentType = assetContentType,
            digest = assetDigest,
        ),
        publishedAt = publishedAt,
        prerelease = prerelease,
        releaseBody = null,
    )

    fun toPersistableBundle(): PersistableBundle = PersistableBundle().also { bundle ->
        putInto(bundle)
    }

    fun toWorkData(): Data = workDataOf(
        KEY_OWNER to owner,
        KEY_REPO to repo,
        KEY_SOURCE_KEY to sourceKey,
        KEY_SOURCE_LABEL to sourceLabel,
        KEY_DISPLAY_NAME to displayName,
        KEY_DESCRIPTION to description.orEmpty(),
        KEY_STARS to stars,
        KEY_HTML_URL to htmlUrl,
        KEY_TAG_NAME to tagName,
        KEY_VERSION_NAME to versionName.orEmpty(),
        KEY_APPLICATION_ID to applicationId.orEmpty(),
        KEY_ASSET_ID to assetId,
        KEY_ASSET_NAME to assetName,
        KEY_ASSET_URL to assetUrl,
        KEY_ASSET_SIZE to assetSize,
        KEY_ASSET_CONTENT_TYPE to assetContentType,
        KEY_ASSET_DIGEST to assetDigest.orEmpty(),
        KEY_GENERATION_ID to generationId,
        KEY_PUBLISHED_AT to publishedAt.orEmpty(),
        KEY_PRERELEASE to prerelease,
    )

    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(KEY_OWNER, owner)
        putExtra(KEY_REPO, repo)
        putExtra(KEY_SOURCE_KEY, sourceKey)
        putExtra(KEY_SOURCE_LABEL, sourceLabel)
        putExtra(KEY_DISPLAY_NAME, displayName)
        putExtra(KEY_DESCRIPTION, description.orEmpty())
        putExtra(KEY_STARS, stars)
        putExtra(KEY_HTML_URL, htmlUrl)
        putExtra(KEY_TAG_NAME, tagName)
        putExtra(KEY_VERSION_NAME, versionName.orEmpty())
        putExtra(KEY_APPLICATION_ID, applicationId.orEmpty())
        putExtra(KEY_ASSET_ID, assetId)
        putExtra(KEY_ASSET_NAME, assetName)
        putExtra(KEY_ASSET_URL, assetUrl)
        putExtra(KEY_ASSET_SIZE, assetSize)
        putExtra(KEY_ASSET_CONTENT_TYPE, assetContentType)
        putExtra(KEY_ASSET_DIGEST, assetDigest.orEmpty())
        putExtra(KEY_GENERATION_ID, generationId)
        putExtra(KEY_PUBLISHED_AT, publishedAt.orEmpty())
        putExtra(KEY_PRERELEASE, prerelease)
    }

    private fun putInto(bundle: PersistableBundle) {
        bundle.putString(KEY_OWNER, owner)
        bundle.putString(KEY_REPO, repo)
        bundle.putString(KEY_SOURCE_KEY, sourceKey)
        bundle.putString(KEY_SOURCE_LABEL, sourceLabel)
        bundle.putString(KEY_DISPLAY_NAME, displayName)
        bundle.putString(KEY_DESCRIPTION, description.orEmpty())
        bundle.putInt(KEY_STARS, stars)
        bundle.putString(KEY_HTML_URL, htmlUrl)
        bundle.putString(KEY_TAG_NAME, tagName)
        bundle.putString(KEY_VERSION_NAME, versionName.orEmpty())
        bundle.putString(KEY_APPLICATION_ID, applicationId.orEmpty())
        bundle.putLong(KEY_ASSET_ID, assetId)
        bundle.putString(KEY_ASSET_NAME, assetName)
        bundle.putString(KEY_ASSET_URL, assetUrl)
        bundle.putLong(KEY_ASSET_SIZE, assetSize)
        bundle.putString(KEY_ASSET_CONTENT_TYPE, assetContentType)
        bundle.putString(KEY_ASSET_DIGEST, assetDigest.orEmpty())
        bundle.putString(KEY_GENERATION_ID, generationId)
        bundle.putString(KEY_PUBLISHED_AT, publishedAt.orEmpty())
        bundle.putBoolean(KEY_PRERELEASE, prerelease)
    }

    companion object {
        fun from(
            info: AppInfo,
            generationId: String = newGenerationId(),
        ): QueuedUpdatePayload = QueuedUpdatePayload(
            owner = info.owner,
            repo = info.repo,
            sourceKey = info.sourceKey,
            sourceLabel = info.sourceLabel,
            displayName = info.displayName,
            description = info.description,
            stars = info.stars,
            htmlUrl = info.htmlUrl,
            tagName = info.tagName,
            versionName = info.versionName,
            applicationId = info.applicationId,
            assetId = info.asset.id,
            assetName = info.asset.name,
            assetUrl = info.asset.browserDownloadUrl,
            assetSize = info.asset.size,
            assetContentType = info.asset.contentType,
            publishedAt = info.publishedAt,
            prerelease = info.prerelease,
            assetDigest = info.asset.digest,
            generationId = generationId,
        )

        fun from(bundle: PersistableBundle): QueuedUpdatePayload? {
            val owner = bundle.getString(KEY_OWNER) ?: return null
            val repo = bundle.getString(KEY_REPO) ?: return null
            val sourceKey = bundle.getString(KEY_SOURCE_KEY) ?: return null
            val sourceLabel = bundle.getString(KEY_SOURCE_LABEL) ?: return null
            val displayName = bundle.getString(KEY_DISPLAY_NAME) ?: return null
            val htmlUrl = bundle.getString(KEY_HTML_URL) ?: return null
            val tagName = bundle.getString(KEY_TAG_NAME) ?: return null
            val assetName = bundle.getString(KEY_ASSET_NAME) ?: return null
            val assetUrl = bundle.getString(KEY_ASSET_URL) ?: return null
            return QueuedUpdatePayload(
                owner = owner,
                repo = repo,
                sourceKey = sourceKey,
                sourceLabel = sourceLabel,
                displayName = displayName,
                description = bundle.getString(KEY_DESCRIPTION).blankToNull(),
                stars = bundle.getInt(KEY_STARS),
                htmlUrl = htmlUrl,
                tagName = tagName,
                versionName = bundle.getString(KEY_VERSION_NAME).blankToNull(),
                applicationId = bundle.getString(KEY_APPLICATION_ID).blankToNull(),
                assetId = bundle.getLong(KEY_ASSET_ID),
                assetName = assetName,
                assetUrl = assetUrl,
                assetSize = bundle.getLong(KEY_ASSET_SIZE),
                assetContentType = bundle.getString(KEY_ASSET_CONTENT_TYPE).orEmpty(),
                publishedAt = bundle.getString(KEY_PUBLISHED_AT).blankToNull(),
                prerelease = bundle.getBoolean(KEY_PRERELEASE),
                assetDigest = bundle.getString(KEY_ASSET_DIGEST).blankToNull(),
                generationId = bundle.getString(KEY_GENERATION_ID).orEmpty(),
            )
        }

        fun from(data: Data): QueuedUpdatePayload? {
            val owner = data.getString(KEY_OWNER) ?: return null
            val repo = data.getString(KEY_REPO) ?: return null
            val sourceKey = data.getString(KEY_SOURCE_KEY) ?: return null
            val sourceLabel = data.getString(KEY_SOURCE_LABEL) ?: return null
            val displayName = data.getString(KEY_DISPLAY_NAME) ?: return null
            val htmlUrl = data.getString(KEY_HTML_URL) ?: return null
            val tagName = data.getString(KEY_TAG_NAME) ?: return null
            val assetName = data.getString(KEY_ASSET_NAME) ?: return null
            val assetUrl = data.getString(KEY_ASSET_URL) ?: return null
            return QueuedUpdatePayload(
                owner = owner,
                repo = repo,
                sourceKey = sourceKey,
                sourceLabel = sourceLabel,
                displayName = displayName,
                description = data.getString(KEY_DESCRIPTION).blankToNull(),
                stars = data.getInt(KEY_STARS, 0),
                htmlUrl = htmlUrl,
                tagName = tagName,
                versionName = data.getString(KEY_VERSION_NAME).blankToNull(),
                applicationId = data.getString(KEY_APPLICATION_ID).blankToNull(),
                assetId = data.getLong(KEY_ASSET_ID, 0L),
                assetName = assetName,
                assetUrl = assetUrl,
                assetSize = data.getLong(KEY_ASSET_SIZE, 0L),
                assetContentType = data.getString(KEY_ASSET_CONTENT_TYPE).orEmpty(),
                publishedAt = data.getString(KEY_PUBLISHED_AT).blankToNull(),
                prerelease = data.getBoolean(KEY_PRERELEASE, false),
                assetDigest = data.getString(KEY_ASSET_DIGEST).blankToNull(),
                generationId = data.getString(KEY_GENERATION_ID).orEmpty(),
            )
        }

        fun from(intent: Intent): QueuedUpdatePayload? {
            val owner = intent.getStringExtra(KEY_OWNER) ?: return null
            val repo = intent.getStringExtra(KEY_REPO) ?: return null
            val sourceKey = intent.getStringExtra(KEY_SOURCE_KEY) ?: return null
            val sourceLabel = intent.getStringExtra(KEY_SOURCE_LABEL) ?: return null
            val displayName = intent.getStringExtra(KEY_DISPLAY_NAME) ?: return null
            val htmlUrl = intent.getStringExtra(KEY_HTML_URL) ?: return null
            val tagName = intent.getStringExtra(KEY_TAG_NAME) ?: return null
            val assetName = intent.getStringExtra(KEY_ASSET_NAME) ?: return null
            val assetUrl = intent.getStringExtra(KEY_ASSET_URL) ?: return null
            return QueuedUpdatePayload(
                owner = owner,
                repo = repo,
                sourceKey = sourceKey,
                sourceLabel = sourceLabel,
                displayName = displayName,
                description = intent.getStringExtra(KEY_DESCRIPTION).blankToNull(),
                stars = intent.getIntExtra(KEY_STARS, 0),
                htmlUrl = htmlUrl,
                tagName = tagName,
                versionName = intent.getStringExtra(KEY_VERSION_NAME).blankToNull(),
                applicationId = intent.getStringExtra(KEY_APPLICATION_ID).blankToNull(),
                assetId = intent.getLongExtra(KEY_ASSET_ID, 0L),
                assetName = assetName,
                assetUrl = assetUrl,
                assetSize = intent.getLongExtra(KEY_ASSET_SIZE, 0L),
                assetContentType = intent.getStringExtra(KEY_ASSET_CONTENT_TYPE).orEmpty(),
                publishedAt = intent.getStringExtra(KEY_PUBLISHED_AT).blankToNull(),
                prerelease = intent.getBooleanExtra(KEY_PRERELEASE, false),
                assetDigest = intent.getStringExtra(KEY_ASSET_DIGEST).blankToNull(),
                generationId = intent.getStringExtra(KEY_GENERATION_ID).orEmpty(),
            )
        }

        fun newGenerationId(): String = UUID.randomUUID().toString()
    }
}

data class QueuedInstallMetadata(
    val applicationId: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String?,
    val signingSha256: String,
    val previousPinnedSha256: String?,
    val lineageRotationAccepted: Boolean,
    val verifiedSignatureSchemes: Set<ApkSignatureScheme>,
) {
    fun toApkMetadata(): ApkMetadata = ApkMetadata(
        applicationId = applicationId,
        versionName = versionName,
        versionCode = versionCode,
        label = label,
        signingSha256 = signingSha256,
        verifiedSignatureSchemes = verifiedSignatureSchemes,
    )

    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(KEY_META_APPLICATION_ID, applicationId)
        putExtra(KEY_META_VERSION_NAME, versionName.orEmpty())
        putExtra(KEY_META_VERSION_CODE, versionCode)
        putExtra(KEY_META_LABEL, label.orEmpty())
        putExtra(KEY_META_SIGNING_SHA256, signingSha256)
        putExtra(KEY_META_PREVIOUS_PIN, previousPinnedSha256.orEmpty())
        putExtra(KEY_META_LINEAGE_ACCEPTED, lineageRotationAccepted)
        putExtra(
            KEY_META_VERIFIED_SCHEMES,
            verifiedSignatureSchemes.map(ApkSignatureScheme::name).toTypedArray(),
        )
    }

    companion object {
        fun from(meta: ApkMetadata, previousPinnedSha256: String?, lineageRotationAccepted: Boolean) =
            QueuedInstallMetadata(
                applicationId = meta.applicationId,
                versionName = meta.versionName,
                versionCode = meta.versionCode,
                label = meta.label,
                signingSha256 = meta.signingSha256,
                previousPinnedSha256 = previousPinnedSha256,
                lineageRotationAccepted = lineageRotationAccepted,
                verifiedSignatureSchemes = meta.verifiedSignatureSchemes,
            )

        fun from(intent: Intent): QueuedInstallMetadata? {
            val applicationId = intent.getStringExtra(KEY_META_APPLICATION_ID) ?: return null
            val signingSha256 = intent.getStringExtra(KEY_META_SIGNING_SHA256) ?: return null
            return QueuedInstallMetadata(
                applicationId = applicationId,
                versionName = intent.getStringExtra(KEY_META_VERSION_NAME).blankToNull(),
                versionCode = intent.getLongExtra(KEY_META_VERSION_CODE, 0L),
                label = intent.getStringExtra(KEY_META_LABEL).blankToNull(),
                signingSha256 = signingSha256,
                previousPinnedSha256 = intent.getStringExtra(KEY_META_PREVIOUS_PIN).blankToNull(),
                lineageRotationAccepted = intent.getBooleanExtra(KEY_META_LINEAGE_ACCEPTED, false),
                verifiedSignatureSchemes = intent.getStringArrayExtra(KEY_META_VERIFIED_SCHEMES)
                    .orEmpty()
                    .mapNotNull { name ->
                        runCatching { ApkSignatureScheme.valueOf(name) }.getOrNull()
                    }
                    .toSet(),
            )
        }
    }
}

private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

private const val KEY_OWNER = "owner"
private const val KEY_REPO = "repo"
private const val KEY_SOURCE_KEY = "source_key"
private const val KEY_SOURCE_LABEL = "source_label"
private const val KEY_DISPLAY_NAME = "display_name"
private const val KEY_DESCRIPTION = "description"
private const val KEY_STARS = "stars"
private const val KEY_HTML_URL = "html_url"
private const val KEY_TAG_NAME = "tag_name"
private const val KEY_VERSION_NAME = "version_name"
private const val KEY_APPLICATION_ID = "application_id"
private const val KEY_ASSET_ID = "asset_id"
private const val KEY_ASSET_NAME = "asset_name"
private const val KEY_ASSET_URL = "asset_url"
private const val KEY_ASSET_SIZE = "asset_size"
private const val KEY_ASSET_CONTENT_TYPE = "asset_content_type"
private const val KEY_ASSET_DIGEST = "asset_digest"
private const val KEY_GENERATION_ID = "generation_id"
private const val KEY_PUBLISHED_AT = "published_at"
private const val KEY_PRERELEASE = "prerelease"

private const val KEY_META_APPLICATION_ID = "meta_application_id"
private const val KEY_META_VERSION_NAME = "meta_version_name"
private const val KEY_META_VERSION_CODE = "meta_version_code"
private const val KEY_META_LABEL = "meta_label"
private const val KEY_META_SIGNING_SHA256 = "meta_signing_sha256"
private const val KEY_META_PREVIOUS_PIN = "meta_previous_pin"
private const val KEY_META_LINEAGE_ACCEPTED = "meta_lineage_accepted"
private const val KEY_META_VERIFIED_SCHEMES = "meta_verified_schemes"
