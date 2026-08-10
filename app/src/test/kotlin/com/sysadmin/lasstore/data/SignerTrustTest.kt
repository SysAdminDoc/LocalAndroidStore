package com.sysadmin.lasstore.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignerTrustTest {
    @Test
    fun unpinnedPackageIsNotRejected() {
        assertTrue(signerMatchesPin(currentSignerSha256 = null, pinnedSignerSha256 = null))
        assertTrue(signerMatchesPin(currentSignerSha256 = "current", pinnedSignerSha256 = ""))
    }

    @Test
    fun pinnedPackageRequiresTheObservedCurrentSigner() {
        assertTrue(signerMatchesPin("abc", "abc"))
        assertFalse(signerMatchesPin("other", "abc"))
        assertFalse(signerMatchesPin(null, "abc"))
    }

    @Test
    fun recoveredInstallRequiresNonBlankExactArtifactSigner() {
        assertTrue(signerMatchesVerifiedArtifact("abc", "abc"))
        assertFalse(signerMatchesVerifiedArtifact("other", "abc"))
        assertFalse(signerMatchesVerifiedArtifact(null, "abc"))
        assertFalse(signerMatchesVerifiedArtifact("abc", ""))
    }
}
