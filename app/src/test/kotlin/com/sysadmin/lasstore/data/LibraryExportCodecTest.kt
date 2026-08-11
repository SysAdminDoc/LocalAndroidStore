package com.sysadmin.lasstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryExportCodecTest {
    @Test
    fun roundTripPreservesSourcesLibraryAndInstallLocks() {
        val sources = LibraryExportSourceDocument(
            github = listOf(GitHubSource(user = "alice", topic = "android-app")),
        )
        val versions = LibraryVersionsDocument(
            generatedAtEpochMillis = 123L,
            collections = listOf(LibraryCollectionSnapshot("watch", "Watch later")),
            entries = listOf(
                LibraryEntrySnapshot(
                    key = "package:com.example.app",
                    favorite = true,
                    collectionIds = setOf("watch"),
                ),
            ),
            installs = listOf(
                LibraryRestoreEntry(
                    applicationId = "com.example.app",
                    versionCode = 7L,
                    versionName = "1.2.3",
                    apkSha256 = "a".repeat(64),
                    certSha256 = "b".repeat(64),
                    sourceKey = "alice",
                    owner = "alice",
                    repo = "example-app",
                    tagName = "v1.2.3",
                    assetName = "example.apk",
                    sourceUrl = "https://github.com/alice/example-app/releases/download/v1.2.3/example.apk",
                ),
            ),
        )

        val parsed = LibraryExportCodec.toParsedExport(
            LibraryExportCodec.decodeSources(LibraryExportCodec.encodeSources(sources)),
            LibraryExportCodec.decodeVersions(LibraryExportCodec.encodeVersions(versions)),
        )

        assertEquals(sources.github, parsed.sources.github)
        assertEquals(versions.collections, parsed.library.collections)
        assertEquals(versions.entries, parsed.library.entries)
        assertEquals(versions.installs, parsed.installs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInstallEntriesWithoutAUsableSourceRoute() {
        LibraryExportCodec.decodeVersions(
            """
                {
                  "formatVersion": 1,
                  "installs": [{"applicationId":"com.example.app","versionCode":1}]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun restoreEntryKeyIsStableForSameSourceRelease() {
        val first = LibraryRestoreEntry(
            applicationId = "com.example.app",
            versionCode = 1L,
            sourceKey = "Alice",
            owner = "Alice",
            repo = "Example",
            tagName = "v1",
            assetName = "app.apk",
            sourceUrl = "https://example.com/app.apk",
        )
        val second = first.copy(sourceKey = "alice", owner = "alice", repo = "example")

        assertEquals(first.key, second.key)
        assertTrue(first.applicationId.isNotBlank())
    }
}
