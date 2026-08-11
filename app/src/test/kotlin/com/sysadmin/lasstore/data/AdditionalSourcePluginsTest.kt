package com.sysadmin.lasstore.data

import com.sysadmin.lasstore.domain.Release
import com.sysadmin.lasstore.domain.VerifyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdditionalSourcePluginsTest {
    @Test
    fun parsesLocalizedMetadataVersionsAndAntiFeatures() {
        val index = FdroidIndexV2Parser.parse(
            raw = """
                {
                  "repo": {
                    "address": "https://example.invalid/repo",
                    "version": 21,
                    "name": {"en-US": "Example Repo"},
                    "fingerprint": "${"ab".repeat(32)}"
                  },
                  "packages": {
                    "com.example.app": {
                      "metadata": {
                        "name": {"en-US": "Example"},
                        "summary": {"en-US": "A test app"},
                        "categories": ["Internet", "Connectivity"],
                        "antiFeatures": {"Tracking": {}, "Ads": {}}
                      },
                      "versions": {
                        "42": {
                          "manifest": {"versionName": "4.2", "versionCode": 42, "usesSdk": {"minSdkVersion": 26}},
                          "whatsNew": {"en-US": "- Fix the sample flow"},
                          "file": {
                            "name": "repo/example.apk",
                            "size": 123,
                            "sha256": "${"cd".repeat(32)}"
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent(),
        )

        val app = index.packages.single()
        assertEquals("Example", app.displayName)
        assertEquals(setOf("Tracking", "Ads"), app.antiFeatures)
        assertEquals(setOf("Internet", "Connectivity"), app.categories)
        assertEquals("4.2", app.versions.single().versionName)
        assertEquals("- Fix the sample flow", app.versions.single().whatsNew)
        assertEquals(26, app.versions.single().minSdk)
        assertEquals("https://example.invalid/repo/repo/example.apk", app.versions.single().downloadUrl)
    }

    @Test
    fun endpointRequiresFingerprintAndRemovesItFromIndexRequest() {
        val fingerprint = "ab".repeat(32)
        val endpoint = FdroidRepositoryTrust.parseEndpoint(
            "https://example.invalid/index-v2.json?fingerprint=$fingerprint",
        )

        assertEquals("https://example.invalid/index-v2.json", endpoint.indexUrl)
        assertTrue(FdroidRepositoryTrust.matches(fingerprint, fingerprint.uppercase()))
        assertTrue(!FdroidRepositoryTrust.matches(fingerprint, "cd".repeat(32)))
        assertEquals(
            "https://example.invalid/index-v2.json?fingerprint=${fingerprint.lowercase()}",
            FdroidRepositoryTrust.canonicalEndpoint(
                "https://example.invalid/index-v2.json?fingerprint=${fingerprint.uppercase()}",
            ),
        )
    }

    @Test
    fun fdroidPluginRequiresRepositoryFingerprintBeforeExposingReleases() = runBlocking {
        val fingerprint = "ab".repeat(32)
        val raw = """
            {"repo":{"address":"https://example.invalid/repo","fingerprint":"$fingerprint"},"packages":{}}
        """.trimIndent()
        val plugin = FDroidIndexV2Plugin(
            indexProvider = { raw },
            baseUrl = "https://example.invalid/repo",
            expectedFingerprint = fingerprint,
        )

        assertEquals(emptyList<Any>(), plugin.listApps())
        val failure = runCatching {
            FDroidIndexV2Plugin(
                indexProvider = { raw },
                baseUrl = "https://example.invalid/repo",
                expectedFingerprint = "cd".repeat(32),
            ).listApps()
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun releaseVerificationDistinguishesMissingDigest() = runBlocking {
        val plugin = FDroidIndexV2Plugin(
            indexProvider = { "{\"repo\":{\"address\":\"https://example.invalid/repo\"},\"packages\":{}}" },
            baseUrl = "https://example.invalid/repo",
            expectedFingerprint = "ab".repeat(32),
        )
        val result = plugin.verify(
            Release(
                id = "release",
                applicationId = "com.example.app",
                versionName = "1",
                assets = listOf(
                    com.sysadmin.lasstore.domain.ReleaseAsset(
                        id = "app.apk",
                        name = "app.apk",
                        downloadUrl = "https://example.invalid/app.apk",
                    ),
                ),
            ),
        )

        assertTrue(result is VerifyResult.Unverified)
    }
}
