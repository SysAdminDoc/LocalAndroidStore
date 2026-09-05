package com.sysadmin.lasstore.install

import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.ApkSignatureScheme
import com.sysadmin.lasstore.data.InstalledInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallArtifactVerifierTest {
    @Test
    fun rejectsPackageIdentityChangesBeforeTrustChecks() {
        val result = verifyInstallArtifact(
            expectedApplicationId = "com.example.expected",
            installedInfo = null,
            metadata = metadata(applicationId = "com.example.other"),
            pinnedSignerSha256 = null,
            declaredSignerSha256 = null,
        )

        assertEquals(
            ArtifactVerificationRejection.PackageIdentity,
            (result as ArtifactVerificationResult.Rejected).reason,
        )
    }

    @Test
    fun rejectsAnArtifactThatContradictsTheDeclaredRestoreCertificate() {
        val result = verifyInstallArtifact(
            expectedApplicationId = PACKAGE_NAME,
            installedInfo = null,
            metadata = metadata(),
            pinnedSignerSha256 = null,
            declaredSignerSha256 = OTHER_SIGNER,
        )

        val rejected = result as ArtifactVerificationResult.Rejected
        assertEquals(ArtifactVerificationRejection.DeclaredSigner, rejected.reason)
        assertEquals(OTHER_SIGNER, rejected.declaredSignerSha256)
    }

    @Test
    fun rejectsADeclaredCertificateThatDiffersByASingleCharacter() {
        val mutated = NEW_SIGNER.dropLast(1) + if (NEW_SIGNER.last() == 'a') 'b' else 'a'
        val result = verifyInstallArtifact(
            expectedApplicationId = PACKAGE_NAME,
            installedInfo = null,
            metadata = metadata(),
            pinnedSignerSha256 = null,
            declaredSignerSha256 = mutated,
        )

        assertEquals(
            ArtifactVerificationRejection.DeclaredSigner,
            (result as ArtifactVerificationResult.Rejected).reason,
        )
    }

    @Test
    fun acceptsAnArtifactThatMatchesTheDeclaredRestoreCertificate() {
        val result = verifyInstallArtifact(
            expectedApplicationId = PACKAGE_NAME,
            installedInfo = null,
            metadata = metadata(),
            pinnedSignerSha256 = null,
            declaredSignerSha256 = NEW_SIGNER.uppercase(),
        )

        assertTrue(result is ArtifactVerificationResult.Accepted)
    }

    @Test
    fun acceptsADeclaredCertificateThatAppearsInTheVerifiedRotationLineage() {
        val result = verifyInstallArtifact(
            expectedApplicationId = PACKAGE_NAME,
            installedInfo = null,
            metadata = metadata(lineage = listOf(OLD_SIGNER, NEW_SIGNER)),
            pinnedSignerSha256 = null,
            declaredSignerSha256 = OLD_SIGNER,
        )

        assertTrue(result is ArtifactVerificationResult.Accepted)
    }

    @Test
    fun ignoresAMalformedDeclaredCertificate() {
        val result = verifyInstallArtifact(
            expectedApplicationId = PACKAGE_NAME,
            installedInfo = null,
            metadata = metadata(),
            pinnedSignerSha256 = null,
            declaredSignerSha256 = "not-a-fingerprint",
        )

        assertTrue(result is ArtifactVerificationResult.Accepted)
    }

    @Test
    fun acceptsAnInstalledSignerThatAppearsInVerifiedLineage() {
        val result = verifyInstallArtifact(
            expectedApplicationId = PACKAGE_NAME,
            installedInfo = InstalledInfo(PACKAGE_NAME, "1", 1, OLD_SIGNER),
            metadata = metadata(lineage = listOf(OLD_SIGNER, NEW_SIGNER)),
            pinnedSignerSha256 = OLD_SIGNER,
            declaredSignerSha256 = null,
        )

        val accepted = result as ArtifactVerificationResult.Accepted
        assertEquals(OLD_SIGNER, accepted.pinnedSignerSha256)
        assertTrue(accepted.lineageRotationAccepted)
    }

    @Test
    fun rejectsAnUnrelatedPublisherPin() {
        val result = verifyInstallArtifact(
            expectedApplicationId = PACKAGE_NAME,
            installedInfo = InstalledInfo(PACKAGE_NAME, "1", 1, NEW_SIGNER),
            metadata = metadata(),
            pinnedSignerSha256 = OTHER_SIGNER,
            declaredSignerSha256 = null,
        )

        assertEquals(
            ArtifactVerificationRejection.PublisherPin,
            (result as ArtifactVerificationResult.Rejected).reason,
        )
    }

    private fun metadata(
        applicationId: String = PACKAGE_NAME,
        lineage: List<String> = emptyList(),
    ) = ApkMetadata(
        applicationId = applicationId,
        versionName = "2",
        versionCode = 2,
        label = "Example",
        signingSha256 = NEW_SIGNER,
        lineageSha256 = lineage,
        verifiedSignatureSchemes = setOf(ApkSignatureScheme.V2),
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val OLD_SIGNER = "12".repeat(32)
        val NEW_SIGNER = "34".repeat(32)
        val OTHER_SIGNER = "56".repeat(32)
    }
}
