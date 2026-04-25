package com.sysadmin.lasstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun sourceKeyNormalizesUserNamesForStableSecretKeys() {
        assertEquals("sysadmindoc", sourceKey(" SysAdminDoc "))
        assertEquals("my-org", sourceKey("My Org"))
        assertEquals("owner.name", sourceKey("Owner.Name"))
    }

    @Test
    fun normalizeSourcesTrimsDefaultsAndDeduplicates() {
        val sources = normalizeSources(
            listOf(
                GitHubSource(user = " SysAdminDoc ", topic = "", enabled = true),
                GitHubSource(user = "sysadmindoc", topic = "other", enabled = false),
                GitHubSource(user = " OtherOrg ", topic = "android", enabled = false),
                GitHubSource(user = "", topic = "ignored", enabled = true),
            )
        )

        assertEquals(2, sources.size)
        assertEquals("SysAdminDoc", sources[0].user)
        assertEquals(DEFAULT_GITHUB_TOPIC, sources[0].topic)
        assertTrue(sources[0].enabled)
        assertEquals("OtherOrg", sources[1].user)
        assertFalse(sources[1].enabled)
    }

    @Test
    fun normalizeSourcesKeepsDefaultWhenEverythingIsBlank() {
        assertEquals(listOf(GitHubSource()), normalizeSources(listOf(GitHubSource(user = ""))))
    }
}
