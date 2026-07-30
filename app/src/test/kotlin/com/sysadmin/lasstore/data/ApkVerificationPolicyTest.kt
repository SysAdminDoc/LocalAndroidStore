package com.sysadmin.lasstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerificationPolicyTest {
    @Test
    fun eachPlatformSignatureSchemeCanSatisfyTheVerifiedContract() {
        ApkSignatureScheme.entries.forEach { scheme ->
            val evidence = evidence(
                schemes = setOf(scheme),
                lineage = if (scheme == ApkSignatureScheme.V3 || scheme == ApkSignatureScheme.V31) {
                    listOf(OLD_SIGNER, CURRENT_SIGNER)
                } else {
                    emptyList()
                },
            )

            assertNull("$scheme should be accepted", validateApkEvidence(evidence))
        }
    }

    @Test
    fun verifierFailureAlwaysWinsOverApparentlyUsableCertificateData() {
        val result = validateApkEvidence(
            evidence(
                verified = false,
                errors = listOf("V2_SIG_APK_DIGEST_DID_NOT_VERIFY"),
            ),
        )

        assertEquals(ApkRejectionReason.SIGNATURE_NOT_VERIFIED, result)
    }

    @Test
    fun emptyAndMultipleCurrentSignerSetsAreRejected() {
        assertEquals(
            ApkRejectionReason.EMPTY_SIGNER_SET,
            validateApkEvidence(evidence(signers = emptyList())),
        )
        assertEquals(
            ApkRejectionReason.MULTIPLE_SIGNERS,
            validateApkEvidence(evidence(signers = listOf(CURRENT_SIGNER, OTHER_SIGNER))),
        )
    }

    @Test
    fun malformedSignerAndUnsupportedVerifiedEvidenceAreRejected() {
        assertEquals(
            ApkRejectionReason.MALFORMED_SIGNER,
            validateApkEvidence(evidence(signers = listOf("not-a-sha256"))),
        )
        assertEquals(
            ApkRejectionReason.NO_VERIFIED_SCHEME,
            validateApkEvidence(evidence(schemes = emptySet())),
        )
    }

    @Test
    fun onlyVerifiedV3LineageEndingAtCurrentSignerIsAccepted() {
        assertNull(
            validateApkEvidence(
                evidence(
                    schemes = setOf(ApkSignatureScheme.V3),
                    lineage = listOf(OLD_SIGNER, CURRENT_SIGNER),
                ),
            ),
        )
        assertEquals(
            ApkRejectionReason.INVALID_LINEAGE,
            validateApkEvidence(
                evidence(
                    schemes = setOf(ApkSignatureScheme.V2),
                    lineage = listOf(OLD_SIGNER, CURRENT_SIGNER),
                ),
            ),
        )
        assertEquals(
            ApkRejectionReason.INVALID_LINEAGE,
            validateApkEvidence(
                evidence(
                    schemes = setOf(ApkSignatureScheme.V31),
                    lineage = listOf(OLD_SIGNER, OTHER_SIGNER),
                ),
            ),
        )
        assertEquals(
            ApkRejectionReason.INVALID_LINEAGE,
            validateApkEvidence(
                evidence(
                    schemes = setOf(ApkSignatureScheme.V3),
                    lineage = listOf(CURRENT_SIGNER, CURRENT_SIGNER),
                ),
            ),
        )
    }

    @Test
    fun packageMetadataMustParseAndAgreeWithCryptographicSigner() {
        val evidence = evidence()

        assertEquals(
            ApkRejectionReason.PACKAGE_PARSE_FAILED,
            validateParsedPackage(null, evidence),
        )
        assertEquals(
            ApkRejectionReason.PACKAGE_ID_INVALID,
            validateParsedPackage(parsed(applicationId = "single_segment"), evidence),
        )
        assertEquals(
            ApkRejectionReason.PACKAGE_SIGNER_MISMATCH,
            validateParsedPackage(parsed(signer = OTHER_SIGNER), evidence),
        )
        assertEquals(
            ApkRejectionReason.PACKAGE_SIGNER_MISMATCH,
            validateParsedPackage(
                parsed(signer = CURRENT_SIGNER).copy(
                    currentSignerSha256 = listOf(CURRENT_SIGNER, OTHER_SIGNER),
                ),
                evidence,
            ),
        )
        assertNull(validateParsedPackage(parsed(), evidence))
    }

    @Test
    fun onlyVerifiedMetadataCanEnrollAFirstPin() {
        val unverified = metadata()
        val verified = metadata().copy(verifiedSignatureSchemes = setOf(ApkSignatureScheme.V2))
        val brokenLineage = verified.copy(lineageSha256 = listOf(OLD_SIGNER, OTHER_SIGNER))

        assertFalse(unverified.isEligibleForPinEnrollment)
        assertTrue(verified.isEligibleForPinEnrollment)
        assertFalse(brokenLineage.isEligibleForPinEnrollment)
        assertEquals(CURRENT_SIGNER, normalizeSigningCertificateSha256("  ${CURRENT_SIGNER.uppercase()}  "))
        assertNull(normalizeSigningCertificateSha256("AA"))
    }

    private fun evidence(
        verified: Boolean = true,
        schemes: Set<ApkSignatureScheme> = setOf(ApkSignatureScheme.V2),
        signers: List<String> = listOf(CURRENT_SIGNER),
        lineage: List<String> = emptyList(),
        errors: List<String> = emptyList(),
    ) = ApkVerificationEvidence(
        verified = verified,
        schemes = schemes,
        signerSha256 = signers,
        lineageSha256 = lineage,
        errors = errors,
    )

    private fun parsed(
        applicationId: String = "com.example.app",
        signer: String = CURRENT_SIGNER,
    ) = ParsedApkPackage(
        applicationId = applicationId,
        versionName = "1.0",
        versionCode = 1,
        label = "Example",
        currentSignerSha256 = listOf(signer),
        requestedPermissions = emptyList(),
    )

    private fun metadata() = ApkMetadata(
        applicationId = "com.example.app",
        versionName = "1.0",
        versionCode = 1,
        label = "Example",
        signingSha256 = CURRENT_SIGNER,
    )

    private companion object {
        val CURRENT_SIGNER = "ab".repeat(32)
        val OLD_SIGNER = "cd".repeat(32)
        val OTHER_SIGNER = "ef".repeat(32)
    }
}
