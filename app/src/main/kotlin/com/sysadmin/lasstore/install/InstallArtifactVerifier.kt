package com.sysadmin.lasstore.install

import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.InstalledInfo
import com.sysadmin.lasstore.data.normalizeSigningCertificateSha256
import com.sysadmin.lasstore.data.signerMatchesArtifactOrLineage

internal enum class ArtifactVerificationRejection {
    PackageIdentity,
    DeclaredSigner,
    InstalledSigner,
    PublisherPin,
}

internal sealed interface ArtifactVerificationResult {
    data class Accepted(
        val pinnedSignerSha256: String?,
        val lineageRotationAccepted: Boolean,
    ) : ArtifactVerificationResult

    data class Rejected(
        val reason: ArtifactVerificationRejection,
        val message: String,
        val pinnedSignerSha256: String? = null,
        val installedSignerSha256: String? = null,
        val declaredSignerSha256: String? = null,
    ) : ArtifactVerificationResult
}

/**
 * Shared package identity, declared-signer, installed-signer, and publisher-pin policy for every
 * install route. Callers own their user-facing state and audit reason; this function owns the
 * trust decision.
 *
 * [declaredSignerSha256] is an out-of-band publisher claim the user already committed to, such as
 * the `certSha256` in an imported library lockfile. It can only narrow trust: a device with no pin
 * would otherwise accept whatever key the source currently serves, so checking it converts a
 * restore from trust-on-first-install into verified-on-first-install. A declared value that appears
 * in the artifact's verified rotation lineage is accepted, so a stale export does not block a
 * publisher who legitimately rotated keys.
 */
internal fun verifyInstallArtifact(
    expectedApplicationId: String?,
    installedInfo: InstalledInfo?,
    metadata: ApkMetadata,
    pinnedSignerSha256: String?,
    declaredSignerSha256: String?,
): ArtifactVerificationResult {
    if (expectedApplicationId != null && metadata.applicationId != expectedApplicationId) {
        return ArtifactVerificationResult.Rejected(
            reason = ArtifactVerificationRejection.PackageIdentity,
            message = "Downloaded APK package changed from $expectedApplicationId to " +
                metadata.applicationId,
            pinnedSignerSha256 = pinnedSignerSha256,
            installedSignerSha256 = installedInfo?.currentSignerSha256,
        )
    }
    val declaredSigner = declaredSignerSha256?.let(::normalizeSigningCertificateSha256)
    if (
        declaredSigner != null &&
        !signerMatchesArtifactOrLineage(
            currentSignerSha256 = declaredSigner,
            expectedSignerSha256 = metadata.signingSha256,
            lineageSha256 = metadata.lineageSha256,
        )
    ) {
        return ArtifactVerificationResult.Rejected(
            reason = ArtifactVerificationRejection.DeclaredSigner,
            message = "Publisher key for ${metadata.applicationId} does not match the " +
                "certificate recorded for it; install blocked.",
            pinnedSignerSha256 = pinnedSignerSha256,
            installedSignerSha256 = installedInfo?.currentSignerSha256,
            declaredSignerSha256 = declaredSigner,
        )
    }
    if (
        installedInfo != null &&
        !signerMatchesArtifactOrLineage(
            currentSignerSha256 = installedInfo.currentSignerSha256,
            expectedSignerSha256 = metadata.signingSha256,
            lineageSha256 = metadata.lineageSha256,
        )
    ) {
        return ArtifactVerificationResult.Rejected(
            reason = ArtifactVerificationRejection.InstalledSigner,
            message = "Installed publisher key does not match the verified release.",
            pinnedSignerSha256 = pinnedSignerSha256,
            installedSignerSha256 = installedInfo.currentSignerSha256,
        )
    }
    val lineageRotationAccepted = pinnedSignerSha256 != null &&
        pinnedSignerSha256 != metadata.signingSha256 &&
        pinnedSignerSha256 in metadata.lineageSha256
    if (
        !pinnedSignerSha256.isNullOrBlank() &&
        pinnedSignerSha256 != metadata.signingSha256 &&
        !lineageRotationAccepted
    ) {
        return ArtifactVerificationResult.Rejected(
            reason = ArtifactVerificationRejection.PublisherPin,
            message = "Publisher key changed for ${metadata.applicationId}; install blocked.",
            pinnedSignerSha256 = pinnedSignerSha256,
            installedSignerSha256 = installedInfo?.currentSignerSha256,
        )
    }
    return ArtifactVerificationResult.Accepted(
        pinnedSignerSha256 = pinnedSignerSha256,
        lineageRotationAccepted = lineageRotationAccepted,
    )
}
