package com.sysadmin.lasstore.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsStoreTransactionRobolectricTest {
    @Test
    fun dataStoreFailureRollsBackSecretChangesAndRegistry() = runBlocking {
        val secrets = FakeSettingsSecrets(initialSourcePats = mapOf("old" to "old-token"))
        val store = newStore(
            secrets = secrets,
            dataStore = FailingDataStore(newDataStore(), failWrites = true),
        )

        val failure = runCatching {
            store.saveSourceRegistry(
                settings = AppSettings(sources = listOf(GitHubSource(user = "alice"))),
                sourcePats = mapOf("alice" to "new-token"),
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(mapOf("old" to "old-token"), secrets.sourcePats)
        assertEquals(DEFAULT_GITHUB_USER, store.flow.first().sources.single().user)
    }

    @Test
    fun secretApplyFailureRollsBackThePendingTransaction() = runBlocking {
        val secrets = FakeSettingsSecrets(failure = Failure.Apply)
        val store = newStore(secrets)

        val failure = runCatching {
            store.saveSourceRegistry(
                settings = AppSettings(sources = listOf(GitHubSource(user = "alice"))),
                sourcePats = mapOf("alice" to "new-token"),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(emptyMap<String, String>(), secrets.sourcePats)
        assertEquals(DEFAULT_GITHUB_USER, store.flow.first().sources.single().user)
    }

    @Test
    fun completionFailureLeavesRetryableJournalThatCanBeRecovered() = runBlocking {
        val secrets = FakeSettingsSecrets(failure = Failure.Complete)
        val store = newStore(secrets)
        val target = AppSettings(sources = listOf(GitHubSource(user = "alice")))

        val failure = runCatching {
            store.saveSourceRegistry(target, mapOf("alice" to "new-token"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        secrets.failure = Failure.None
        store.recoverPendingTransaction()

        assertEquals(mapOf("alice" to "new-token"), secrets.sourcePats)
        assertEquals(normalizeSources(target.sources), store.flow.first().sources)
    }

    @Test
    fun hideUnverifiedSourcesPreferencePersistsWithTheSourceRegistry() = runBlocking {
        val store = newStore(FakeSettingsSecrets())
        val target = AppSettings(
            sources = listOf(
                GitHubSource(
                    user = "alice",
                    accent = AccentColor.Teal,
                    brandingUrl = "https://example.com/alice.json",
                    threatModel = "Alice controls the repository; LAS verifies the release digest and signer.",
                ),
            ),
            hideUnverifiedSources = true,
            themeMode = AppThemeMode.Light,
            accentColor = AccentColor.Lavender,
            dynamicColor = true,
            highContrast = true,
            dailyUpdateCap = 7,
        )

        store.update(target)

        val saved = store.flow.first()
        assertTrue(saved.hideUnverifiedSources)
        assertEquals(AppThemeMode.Light, saved.themeMode)
        assertEquals(AccentColor.Lavender, saved.accentColor)
        assertTrue(saved.dynamicColor)
        assertTrue(saved.highContrast)
        assertEquals(7, saved.dailyUpdateCap)
        assertEquals(AccentColor.Teal, saved.sources.single().accent)
        assertEquals("https://example.com/alice.json", saved.sources.single().brandingUrl)
        assertTrue(saved.sources.single().threatModel.startsWith("Alice controls"))
    }

    private fun newStore(
        secrets: FakeSettingsSecrets,
        dataStore: DataStore<Preferences> = newDataStore(),
    ): SettingsStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val journal = context.getSharedPreferences(
            "settings-test-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        return SettingsStore(
            context = context,
            secrets = secrets,
            dataStore = dataStore,
            transactionJournal = journal,
        )
    }

    private fun newDataStore(): DataStore<Preferences> {
        val directory = Files.createTempDirectory("las-settings-test").toFile()
        val file = File(directory, "settings.preferences_pb")
        return PreferenceDataStoreFactory.create { file }
    }

    private enum class Failure {
        None,
        Begin,
        Apply,
        Complete,
        Rollback,
    }

    private class FailingDataStore(
        private val delegate: DataStore<Preferences>,
        private val failWrites: Boolean,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = delegate.data

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            if (failWrites) throw IOException("Injected DataStore failure")
            return delegate.updateData(transform)
        }
    }

    private class FakeSettingsSecrets(
        initialSourcePats: Map<String, String> = emptyMap(),
        var failure: Failure = Failure.None,
    ) : SettingsSecretStore {
        var sourcePats: Map<String, String> = initialSourcePats
            private set
        private var pending: PendingSourcePatTransaction? = null

        override fun getPat(): String = ""
        override fun setPat(pat: String) = Unit
        override fun getPat(sourceKey: String): String = sourcePats[sourceKey].orEmpty()
        override fun getSourcePat(sourceKey: String): String? = sourcePats[sourceKey]
        override fun setPat(sourceKey: String, pat: String) = Unit
        override fun replaceSourcePats(
            sourcePats: Map<String, String>,
            activeSourceKeys: Set<String>,
        ) {
            this.sourcePats = sourcePats.filterKeys { it in activeSourceKeys }
        }

        override fun beginSourcePatTransaction(
            id: String,
            targetSettingsFingerprint: String,
            targetSourcePats: Map<String, String>,
        ) {
            failIf(Failure.Begin)
            pending = PendingSourcePatTransaction(
                id = id,
                targetSettingsFingerprint = targetSettingsFingerprint,
                previousSourcePats = sourcePats,
                targetSourcePats = targetSourcePats,
            )
        }

        override fun applySourcePatTransaction(id: String) {
            failIf(Failure.Apply)
            sourcePats = checkNotNull(pending).also { check(it.id == id) }.targetSourcePats
        }

        override fun completeSourcePatTransaction(id: String) {
            failIf(Failure.Complete)
            pending?.also { check(it.id == id) }
            pending = null
        }

        override fun rollbackSourcePatTransaction(id: String) {
            failIf(Failure.Rollback)
            pending?.also { check(it.id == id) }?.let {
                sourcePats = it.previousSourcePats
                pending = null
            }
        }

        override fun reconcilePendingSourcePatTransaction(settingsFingerprint: String) {
            val transaction = pending ?: return
            if (transaction.targetSettingsFingerprint == settingsFingerprint) {
                completeSourcePatTransaction(transaction.id)
            } else {
                rollbackSourcePatTransaction(transaction.id)
            }
        }

        override fun sourcePats(): Map<String, String> = sourcePats

        private fun failIf(expected: Failure) {
            if (failure == expected) throw IllegalStateException("Injected $expected failure")
        }
    }
}
