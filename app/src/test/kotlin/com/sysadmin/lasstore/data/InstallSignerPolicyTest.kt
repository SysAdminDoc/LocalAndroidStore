package com.sysadmin.lasstore.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSignerPolicyTest {
    @Test
    fun currentSignerMustMatchVerifiedSignerOrItsLineage() {
        assertTrue(
            signerMatchesArtifactOrLineage(
                currentSignerSha256 = "CURRENT",
                expectedSignerSha256 = "CURRENT",
                lineageSha256 = emptyList(),
            ),
        )
        assertTrue(
            signerMatchesArtifactOrLineage(
                currentSignerSha256 = "PREVIOUS",
                expectedSignerSha256 = "CURRENT",
                lineageSha256 = listOf("PREVIOUS", "CURRENT"),
            ),
        )
        assertFalse(
            signerMatchesArtifactOrLineage(
                currentSignerSha256 = "UNRELATED",
                expectedSignerSha256 = "CURRENT",
                lineageSha256 = listOf("PREVIOUS", "CURRENT"),
            ),
        )
        assertFalse(
            signerMatchesArtifactOrLineage(
                currentSignerSha256 = null,
                expectedSignerSha256 = "CURRENT",
                lineageSha256 = emptyList(),
            ),
        )
    }
}
