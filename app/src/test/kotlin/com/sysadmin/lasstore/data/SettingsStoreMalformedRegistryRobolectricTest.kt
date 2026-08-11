package com.sysadmin.lasstore.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsStoreMalformedRegistryRobolectricTest {
    @Test
    fun malformedPayloadIsBackedUpAndCannotBeReplacedByOrdinarySave() = runBlocking {
        val rawPayload = "{not-a-source-registry"
        val dataStore = newDataStore()
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("github_user")] = "legacy-owner"
            prefs[stringPreferencesKey("github_sources_v1")] = rawPayload
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(
            context = context,
            secrets = NoopSettingsSecrets(),
            dataStore = dataStore,
            transactionJournal = context.getSharedPreferences(
                "settings-malformed-journal-${System.nanoTime()}",
                Context.MODE_PRIVATE,
            ),
            recoveryStore = context.getSharedPreferences(
                "settings-malformed-recovery-${System.nanoTime()}",
                Context.MODE_PRIVATE,
            ),
        )

        val inspection = store.inspectSourceRegistry()

        assertEquals(SourceRegistryPayloadState.Malformed, inspection.payloadState)
        assertEquals(rawPayload, inspection.malformedPayload)
        assertTrue(inspection.backupAvailable)
        assertEquals(rawPayload, store.malformedSourceRegistryBackup())
        assertEquals("legacy-owner", store.flow.first().sources.single().user)

        val failure = runCatching {
            store.saveSourceRegistry(
                settings = AppSettings(sources = listOf(GitHubSource(user = "replacement"))),
                sourcePats = emptyMap(),
            )
        }.exceptionOrNull()

        assertTrue(failure is MalformedSourceRegistryException)
        assertEquals(
            rawPayload,
            dataStore.data.first()[stringPreferencesKey("github_sources_v1")],
        )

        store.replaceMalformedSourceRegistry(
            settings = AppSettings(sources = listOf(GitHubSource(user = "replacement"))),
            sourcePats = emptyMap(),
        )

        assertEquals("replacement", store.flow.first().sources.single().user)
        assertEquals(rawPayload, store.malformedSourceRegistryBackup())
    }

    private fun newDataStore(): DataStore<Preferences> {
        val directory = Files.createTempDirectory("las-settings-malformed-test").toFile()
        return PreferenceDataStoreFactory.create { File(directory, "settings.preferences_pb") }
    }

    private class NoopSettingsSecrets : SettingsSecretStore {
        private var sourcePatValues: Map<String, String> = emptyMap()

        override fun getPat(): String = ""
        override fun setPat(pat: String) = Unit
        override fun getPat(sourceKey: String): String = sourcePatValues[sourceKey].orEmpty()
        override fun getSourcePat(sourceKey: String): String? = sourcePatValues[sourceKey]
        override fun setPat(sourceKey: String, pat: String) = Unit
        override fun replaceSourcePats(
            sourcePats: Map<String, String>,
            activeSourceKeys: Set<String>,
        ) {
            sourcePatValues = sourcePats.filterKeys { it in activeSourceKeys }
        }

        override fun beginSourcePatTransaction(
            id: String,
            targetSettingsFingerprint: String,
            targetSourcePats: Map<String, String>,
        ) = Unit

        override fun applySourcePatTransaction(id: String) = Unit
        override fun completeSourcePatTransaction(id: String) = Unit
        override fun rollbackSourcePatTransaction(id: String) = Unit
        override fun reconcilePendingSourcePatTransaction(settingsFingerprint: String) = Unit
        override fun sourcePats(): Map<String, String> = sourcePatValues
    }
}
