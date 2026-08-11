package com.sysadmin.lasstore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePluginTest {
    @Test
    fun registryLookupIsCaseInsensitiveAndRejectsBlankIds() {
        val plugin = FakePlugin("GitHub:personal")
        val registry = SourcePluginRegistry(listOf(plugin))

        assertSame(plugin, registry.find(" github:PERSONAL "))
        assertEquals(1, registry.plugins.size)

        val failure = runCatching { SourcePluginRegistry(listOf(FakePlugin(" "))) }
        assertTrue(failure.isFailure)

        val duplicate = runCatching {
            SourcePluginRegistry(listOf(FakePlugin("github"), FakePlugin("GitHub")))
        }
        assertTrue(duplicate.isFailure)
    }

    private class FakePlugin(override val id: String) : SourcePlugin {
        override val displayName: String = id

        override suspend fun listApps(): List<DiscoveredApp> = emptyList()

        override suspend fun getReleases(applicationId: String): List<Release> = emptyList()

        override suspend fun resolveDownloadUrl(release: Release): String = release.assets.first().downloadUrl

        override suspend fun verify(release: Release): VerifyResult = VerifyResult.Unverified("test")
    }
}
