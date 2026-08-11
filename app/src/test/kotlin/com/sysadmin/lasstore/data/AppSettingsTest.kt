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
        val normalized = normalizeSources(listOf(GitHubSource(user = "")))

        assertEquals(1, normalized.size)
        assertEquals(DEFAULT_GITHUB_USER, normalized.single().user)
        assertTrue(normalized.single().threatModel.isNotBlank())
    }

    @Test
    fun validateSourcesRejectsBlankAndDuplicateIdentitiesBeforeNormalization() {
        assertEquals(
            "Enter a GitHub user or organization for source 2.",
            validateSources(listOf(GitHubSource(user = "alice"), GitHubSource(user = " "))),
        )
        assertTrue(
            validateSources(
                listOf(GitHubSource(user = "Alice"), GitHubSource(user = " alice ")),
        )?.contains("listed more than once") == true,
        )
    }

    @Test
    fun fdroidEndpointsAreCanonicalizedAndRequireFingerprint() {
        val fingerprint = "AB".repeat(32)
        val normalized = normalizeFdroidSources(
            listOf(
                FdroidSource(
                    endpointUrl = "https://repo.example/index-v2.json?fingerprint=$fingerprint",
                ),
            ),
        )

        assertEquals(
            "https://repo.example/index-v2.json?fingerprint=${fingerprint.lowercase()}",
            normalized.single().endpointUrl,
        )
        assertTrue(
            validateFdroidSources(
                listOf(FdroidSource("https://repo.example/index-v2.json")),
        )?.contains("fingerprint") == true,
        )
    }

    @Test
    fun sourceAccentOverridesGlobalAccentAndGlobalIsTheFallback() {
        val settings = AppSettings(
            accentColor = AccentColor.Lavender,
            sources = listOf(
                GitHubSource(user = "alice", accent = AccentColor.Teal),
                GitHubSource(user = "bob"),
            ),
        )

        assertEquals(AccentColor.Teal, accentForSource(settings, sourceKey("alice")))
        assertEquals(AccentColor.Lavender, accentForSource(settings, sourceKey("bob")))
        assertEquals(AccentColor.Lavender, accentForSource(settings, "missing"))
    }

    @Test
    fun sourceThreatModelsHaveSafeDefaultsAndBoundedValidation() {
        val normalized = normalizeSources(
            listOf(GitHubSource(user = "alice")),
        ).single()
        assertTrue(normalized.threatModel.contains("alice"))

        val custom = normalizeSources(
            listOf(GitHubSource(user = "alice", threatModel = "  verified by our team  ")),
        ).single()
        assertEquals("verified by our team", custom.threatModel)
        assertTrue(
            validateSources(
                listOf(
                    GitHubSource(
                        user = "alice",
                        threatModel = "x".repeat(MAX_SOURCE_THREAT_MODEL_LENGTH + 1),
                    ),
                ),
            )?.contains(MAX_SOURCE_THREAT_MODEL_LENGTH.toString()) == true,
        )
    }

    @Test
    fun fdroidThreatModelsDescribeThePinnedEndpointWhenBlank() {
        val endpoint = "https://repo.example/index-v2.json?fingerprint=${"AB".repeat(32)}"
        val normalized = normalizeFdroidSources(listOf(FdroidSource(endpoint))).single()

        assertTrue(normalized.threatModel.contains("repo.example"))
    }
}
