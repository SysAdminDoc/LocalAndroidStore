package com.sysadmin.lasstore.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseChannelTest {
    @Test
    fun derivesStableAndNamedChannelsFromTags() {
        assertEquals(ReleaseChannel.STABLE, deriveReleaseChannel("v1.2.3", prerelease = false))
        assertEquals(ReleaseChannel.BETA, deriveReleaseChannel("v1.2.3-beta1", prerelease = true))
        assertEquals(ReleaseChannel.ALPHA, deriveReleaseChannel("v1.2.3-alpha", prerelease = true))
        assertEquals(ReleaseChannel.NIGHTLY, deriveReleaseChannel("nightly-2026-08-11", prerelease = true))
        assertEquals(ReleaseChannel.RC, deriveReleaseChannel("v1.2.3-rc1", prerelease = true))
        assertEquals(ReleaseChannel.DEV, deriveReleaseChannel("v1.2.3-dev", prerelease = true))
    }

    @Test
    fun unknownPrereleaseUsesPreviewChannelAndKeysRoundTrip() {
        assertEquals(ReleaseChannel.PREVIEW, deriveReleaseChannel("v1.2.3", prerelease = true))
        assertEquals(ReleaseChannel.BETA, ReleaseChannel.fromKey(" BETA "))
    }
}
