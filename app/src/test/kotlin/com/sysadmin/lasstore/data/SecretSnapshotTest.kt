package com.sysadmin.lasstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretSnapshotTest {
    @Test
    fun blankSourcePatRemovesOverrideAndKeepsGlobalFallbackAvailable() {
        val snapshot = SecretSnapshot(globalPat = "global", sourcePats = mapOf("sysadmindoc" to "source"))
            .withSourcePat("sysadmindoc", " ")

        assertEquals("global", snapshot.globalPat)
        assertNull(snapshot.sourcePats["sysadmindoc"])
        assertTrue(snapshot.sourcePats.isEmpty())
    }

    @Test
    fun replacingSourcePatsPurgesRemovedSourcesInOneSnapshot() {
        val snapshot = SecretSnapshot(
            globalPat = "global",
            sourcePats = mapOf("old-owner" to "old", "kept" to "old-kept"),
        ).replaceSourcePats(
            sourcePats = mapOf("renamed" to " new-token ", "old-owner" to "stale"),
            activeSourceKeys = setOf("renamed"),
        )

        assertEquals("global", snapshot.globalPat)
        assertEquals(mapOf("renamed" to "new-token"), snapshot.sourcePats)
    }

    @Test
    fun sourcePatTransactionCanApplyAndCompleteIdempotently() {
        val transaction = SecretSnapshot(
            sourcePats = mapOf("old" to "old-token"),
        ).beginSourcePatTransaction(
            id = "tx-1",
            targetSettingsFingerprint = "settings-v2",
            targetSourcePats = mapOf("new" to "new-token"),
        )

        val applied = transaction.applySourcePatTransaction("tx-1")
        assertEquals(mapOf("new" to "new-token"), applied.sourcePats)
        assertEquals(
            mapOf("new" to "new-token"),
            applied.completeSourcePatTransaction("tx-1").sourcePats,
        )
    }

    @Test
    fun sourcePatTransactionRollbackRestoresPreviousSnapshot() {
        val pending = SecretSnapshot(
            sourcePats = mapOf("old" to "old-token"),
        ).beginSourcePatTransaction(
            id = "tx-1",
            targetSettingsFingerprint = "settings-v2",
            targetSourcePats = mapOf("new" to "new-token"),
        )

        val rolledBack = pending
            .applySourcePatTransaction("tx-1")
            .rollbackSourcePatTransaction("tx-1")

        assertEquals(mapOf("old" to "old-token"), rolledBack.sourcePats)
        assertEquals(null, rolledBack.pendingSourcePatTransaction)
    }

    @Test
    fun pinsCanBeAddedAndRemovedWithoutTouchingTokens() {
        val snapshot = SecretSnapshot(globalPat = "pat")
            .withPin("com.example.app", "abc123")
            .withoutPin("com.example.app")

        assertEquals("pat", snapshot.globalPat)
        assertFalse(snapshot.isEmpty)
        assertTrue(snapshot.pins.isEmpty())
    }

    @Test
    fun fallbackMergePreservesValuesWithoutOverridingNewerSecrets() {
        val primary = SecretSnapshot(
            sourcePats = mapOf("sysadmindoc" to "fresh"),
            pins = mapOf("com.example.one" to "fresh-pin"),
        )
        val fallback = SecretSnapshot(
            globalPat = "global",
            sourcePats = mapOf("sysadmindoc" to "old", "other" to "fallback"),
            pins = mapOf("com.example.one" to "old-pin", "com.example.two" to "fallback-pin"),
        )

        val merged = primary.mergedWithFallback(fallback)

        assertEquals("global", merged.globalPat)
        assertEquals("fresh", merged.sourcePats["sysadmindoc"])
        assertEquals("fallback", merged.sourcePats["other"])
        assertEquals("fresh-pin", merged.pins["com.example.one"])
        assertEquals("fallback-pin", merged.pins["com.example.two"])
    }

    @Test
    fun migrationMergePrefersNewerFallbackOverExistingEncryptedValues() {
        val encrypted = SecretSnapshot(
            globalPat = "old-global",
            sourcePats = mapOf("source" to "old"),
            pins = mapOf("com.example.app" to "old-pin"),
            updatedAtEpochMillis = 10L,
        )
        val fallback = SecretSnapshot(
            globalPat = "new-global",
            sourcePats = mapOf("source" to "new"),
            pins = mapOf("com.example.app" to "new-pin"),
            updatedAtEpochMillis = 20L,
        )

        val merged = encrypted.mergeForMigration(fallback)

        assertEquals("new-global", merged.globalPat)
        assertEquals("new", merged.sourcePats["source"])
        assertEquals("new-pin", merged.pins["com.example.app"])
        assertEquals(20L, merged.updatedAtEpochMillis)
    }

    @Test
    fun migrationMergeKeepsEncryptedValuesWhenEncryptedSnapshotIsNewer() {
        val encrypted = SecretSnapshot(
            globalPat = "new-global",
            sourcePats = mapOf("source" to "new"),
            updatedAtEpochMillis = 20L,
        )
        val fallback = SecretSnapshot(
            globalPat = "old-global",
            sourcePats = mapOf("source" to "old"),
            updatedAtEpochMillis = 10L,
        )

        val merged = encrypted.mergeForMigration(fallback)

        assertEquals("new-global", merged.globalPat)
        assertEquals("new", merged.sourcePats["source"])
        assertEquals(20L, merged.updatedAtEpochMillis)
    }
}
