package com.sysadmin.lasstore.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceVerificationTest {
    @Test
    fun missingPackageIdentityIsUnknown() {
        assertEquals(
            SourceVerificationStatus.Unknown,
            sourceVerificationStatus(
                applicationId = null,
                knownSignerSha256 = null,
                pinnedSignerSha256 = null,
            ),
        )
    }

    @Test
    fun knownPackageWithoutLocalPinIsUnverified() {
        assertEquals(
            SourceVerificationStatus.Unverified,
            sourceVerificationStatus(
                applicationId = "com.example.app",
                knownSignerSha256 = "aa",
                pinnedSignerSha256 = null,
            ),
        )
    }

    @Test
    fun matchingPinnedSignerIsVerifiedCaseInsensitively() {
        assertEquals(
            SourceVerificationStatus.Verified,
            sourceVerificationStatus(
                applicationId = "com.example.app",
                knownSignerSha256 = "AA",
                pinnedSignerSha256 = "aa",
            ),
        )
    }

    @Test
    fun mismatchedPinnedSignerIsUnverified() {
        assertEquals(
            SourceVerificationStatus.Unverified,
            sourceVerificationStatus(
                applicationId = "com.example.app",
                knownSignerSha256 = "aa",
                pinnedSignerSha256 = "bb",
            ),
        )
    }
}
