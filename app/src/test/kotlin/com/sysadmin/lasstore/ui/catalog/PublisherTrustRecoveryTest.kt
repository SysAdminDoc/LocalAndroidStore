package com.sysadmin.lasstore.ui.catalog

import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.ApkSignatureScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublisherTrustRecoveryTest {
    @Test
    fun typedConfirmationRequiresTheExactPackageIdentifier() {
        assertTrue(canAdvancePublisherPinRecovery(PACKAGE_NAME, "  $PACKAGE_NAME  "))
        assertFalse(canAdvancePublisherPinRecovery(PACKAGE_NAME, "com.example.other"))
        assertFalse(canAdvancePublisherPinRecovery(PACKAGE_NAME, PACKAGE_NAME.uppercase()))
        assertFalse(canAdvancePublisherPinRecovery(PACKAGE_NAME, ""))
    }

    @Test
    fun replacementRequiresBothTypedConfirmationAndSecondAcknowledgement() {
        val details = details()

        assertFalse(canReplacePublisherPin(details, PACKAGE_NAME, independentlyVerified = false))
        assertFalse(canReplacePublisherPin(details, "com.example.other", independentlyVerified = true))
        assertTrue(canReplacePublisherPin(details, PACKAGE_NAME, independentlyVerified = true))
    }

    @Test
    fun unverifiedOrAlreadyMatchingSignerCanNeverUseRecovery() {
        val unverified = details().copy(
            downloadedMetadata = details().downloadedMetadata.copy(
                verifiedSignatureSchemes = emptySet(),
            ),
        )
        val matching = details().copy(
            storedPinSha256 = NEW_SIGNER,
        )

        assertFalse(canReplacePublisherPin(unverified, PACKAGE_NAME, independentlyVerified = true))
        assertFalse(canReplacePublisherPin(matching, PACKAGE_NAME, independentlyVerified = true))
    }

    private fun details() = PublisherTrustDetails(
        source = "Personal · owner/repo",
        installedSignerSha256 = OLD_SIGNER,
        storedPinSha256 = OLD_SIGNER,
        downloadedMetadata = ApkMetadata(
            applicationId = PACKAGE_NAME,
            versionName = "2.0",
            versionCode = 2,
            label = "Example",
            signingSha256 = NEW_SIGNER,
            verifiedSignatureSchemes = setOf(ApkSignatureScheme.V2),
        ),
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val OLD_SIGNER = "12".repeat(32)
        val NEW_SIGNER = "34".repeat(32)
    }
}
