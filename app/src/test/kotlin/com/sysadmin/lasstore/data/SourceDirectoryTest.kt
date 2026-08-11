package com.sysadmin.lasstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDirectoryTest {
    @Test
    fun decodesCuratedGitHubAndFdroidDefinitions() {
        val feed = SourceDirectoryCodec.decode(
            """
                {
                  "formatVersion": 1,
                  "sources": [
                    {
                      "id": "github-apps",
                      "name": "My apps",
                      "description": "Personal releases",
                      "github": {"user":"alice","topic":"android-app"}
                    },
                    {
                      "id": "fdroid-community",
                      "name": "Community",
                      "fdroid": {"endpointUrl":"https://repo.example/index-v2.json?fingerprint=${"a".repeat(64)}"}
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(2, feed.sources.size)
        assertEquals("alice", feed.sources[0].github?.user)
        assertEquals("fdroid:https://repo.example/index-v2.json", feed.sources[1].sourceKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEntriesThatDescribeBothSourceTypes() {
        SourceDirectoryCodec.decode(
            """
                {
                  "formatVersion": 1,
                  "sources": [{
                    "id":"bad",
                    "name":"Bad",
                    "github":{"user":"alice"},
                    "fdroid":{"endpointUrl":"https://repo.example/index-v2.json?fingerprint=${"b".repeat(64)}"}
                  }]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun directoryUrlRequiresHttpsAndRejectsCredentialQueryParameters() {
        assertNotNull(validatedSourceDirectoryUrl("https://example.com/sources.json"))
        assertNull(validatedSourceDirectoryUrl("http://example.com/sources.json"))
        assertNull(validatedSourceDirectoryUrl("https://example.com/sources.json?token=secret"))
        assertTrue(validateSourceDirectoryUrl("https://example.com/sources.json") == null)
    }
}
