package com.sysadmin.lasstore.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(SettingsSchemaMigration) },
)

private object SettingsSchemaMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = false

    override suspend fun migrate(currentData: Preferences): Preferences = currentData

    override suspend fun cleanUp() = Unit
}

@Serializable
private data class PendingSettingsSave(
    val transactionId: String,
    val previous: AppSettings,
    val target: AppSettings,
)

interface SettingsSecretStore {
    fun getPat(): String
    fun setPat(pat: String)
    fun getPat(sourceKey: String): String
    fun getSourcePat(sourceKey: String): String?
    fun setPat(sourceKey: String, pat: String)
    fun replaceSourcePats(sourcePats: Map<String, String>, activeSourceKeys: Set<String>)
    fun beginSourcePatTransaction(
        id: String,
        targetSettingsFingerprint: String,
        targetSourcePats: Map<String, String>,
    )
    fun applySourcePatTransaction(id: String)
    fun completeSourcePatTransaction(id: String)
    fun rollbackSourcePatTransaction(id: String)
    fun reconcilePendingSourcePatTransaction(settingsFingerprint: String)
    fun sourcePats(): Map<String, String>
}

class SettingsStore(
    private val context: Context,
    private val secrets: SettingsSecretStore,
    private val dataStore: DataStore<Preferences> = context.settingsDataStore,
    private val transactionJournal: SharedPreferences = context.getSharedPreferences(
        "settings_save_journal",
        Context.MODE_PRIVATE,
    ),
) {
    private val keyUser = stringPreferencesKey("github_user")
    private val keyTopic = stringPreferencesKey("topic")
    private val keyFilterByTopic = booleanPreferencesKey("filter_by_topic")
    private val keyPrereleases = booleanPreferencesKey("show_prereleases")
    private val keySources = stringPreferencesKey("github_sources_v1")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val sourceListSerializer = ListSerializer(GitHubSource.serializer())
    private val transactionMutex = Mutex()

    val flow: Flow<AppSettings> = dataStore.data
        .onStart { recoverPendingTransaction() }
        .map(::settingsFromPreferences)

    /** Recover an interrupted registry/PAT transaction before exposing settings to callers. */
    suspend fun recoverPendingTransaction() = transactionMutex.withLock {
        recoverPendingTransactionLocked()
    }

    /** Persist the source registry and source PATs as one recoverable transaction. */
    suspend fun saveSourceRegistry(
        settings: AppSettings,
        sourcePats: Map<String, String>,
    ) = transactionMutex.withLock {
        recoverPendingTransactionLocked()
        val previous = readSettingsLocked()
        val target = canonicalSettings(settings)
        val targetSourcePats = sourcePats
            .filter { (key, value) ->
                key in target.sources.map { it.key } && value.isNotBlank()
            }
            .mapValues { (_, value) -> value.trim() }
        val transaction = PendingSettingsSave(
            transactionId = UUID.randomUUID().toString(),
            previous = previous,
            target = target,
        )
        writePendingTransaction(transaction)
        try {
            secrets.beginSourcePatTransaction(
                id = transaction.transactionId,
                targetSettingsFingerprint = settingsFingerprint(target),
                targetSourcePats = targetSourcePats,
            )
            secrets.applySourcePatTransaction(transaction.transactionId)
            check(secrets.sourcePats() == targetSourcePats) {
                "Source PAT write did not verify"
            }
            writeSettings(target)
            check(settingsFingerprint(readSettingsLocked()) == settingsFingerprint(target)) {
                "Source registry write did not verify"
            }
            secrets.completeSourcePatTransaction(transaction.transactionId)
            check(secrets.sourcePats() == targetSourcePats) {
                "Source PAT completion did not verify"
            }
            clearPendingTransaction()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val recovery = runCatching { recoverPendingTransactionLocked() }
            if (recovery.isFailure) {
                throw IllegalStateException(
                    "Settings save is incomplete; retry after storage is available.",
                    recovery.exceptionOrNull(),
                ).also { it.addSuppressed(failure) }
            }
            throw failure
        }
    }

    suspend fun update(settings: AppSettings) {
        transactionMutex.withLock {
            recoverPendingTransactionLocked()
            writeSettings(canonicalSettings(settings))
        }
    }

    private suspend fun recoverPendingTransactionLocked() {
        val current = readSettingsLocked()
        val pending = readPendingTransaction()
        if (pending == null) {
            secrets.reconcilePendingSourcePatTransaction(settingsFingerprint(current))
            return
        }

        val currentFingerprint = settingsFingerprint(current)
        when (currentFingerprint) {
            settingsFingerprint(pending.target) -> {
                secrets.completeSourcePatTransaction(pending.transactionId)
                clearPendingTransaction()
            }
            settingsFingerprint(pending.previous) -> {
                secrets.rollbackSourcePatTransaction(pending.transactionId)
                clearPendingTransaction()
            }
            else -> {
                throw IllegalStateException(
                    "An interrupted settings save needs recovery before another save can run.",
                )
            }
        }
    }

    private suspend fun readSettingsLocked(): AppSettings =
        dataStore.data.first().let(::settingsFromPreferences)

    private suspend fun writeSettings(settings: AppSettings) {
        val canonical = canonicalSettings(settings)
        val primary = canonical.sources.first()
        dataStore.edit { prefs ->
            prefs[keyUser] = primary.user
            prefs[keyTopic] = primary.topic
            prefs[keyFilterByTopic] = primary.filterByTopic
            prefs[keyPrereleases] = primary.showPrereleases
            prefs[keySources] = json.encodeToString(sourceListSerializer, canonical.sources)
        }
    }

    private fun settingsFromPreferences(prefs: Preferences): AppSettings {
        val legacy = AppSettings(
            githubUser = prefs[keyUser] ?: DEFAULT_GITHUB_USER,
            topic = prefs[keyTopic] ?: DEFAULT_GITHUB_TOPIC,
            filterByTopic = prefs[keyFilterByTopic] ?: false,
            showPrereleases = prefs[keyPrereleases] ?: false,
        )
        val sources = decodeSources(prefs[keySources]) ?: listOf(legacySource(legacy))
        return legacy.copy(sources = normalizeSources(sources))
    }

    private fun canonicalSettings(settings: AppSettings): AppSettings {
        val sources = normalizeSources(settings.sources)
        val primary = sources.first()
        return settings.copy(
            githubUser = primary.user,
            topic = primary.topic,
            filterByTopic = primary.filterByTopic,
            showPrereleases = primary.showPrereleases,
            sources = sources,
        )
    }

    private fun settingsFingerprint(settings: AppSettings): String =
        json.encodeToString(AppSettings.serializer(), canonicalSettings(settings))

    private fun readPendingTransaction(): PendingSettingsSave? =
        transactionJournal.getString(KEY_PENDING_TRANSACTION, null)?.let { raw ->
            json.decodeFromString<PendingSettingsSave>(raw)
        }

    private fun writePendingTransaction(transaction: PendingSettingsSave) {
        check(
            transactionJournal.edit()
                .putString(KEY_PENDING_TRANSACTION, json.encodeToString(transaction))
                .commit()
        ) { "Could not persist settings save journal" }
    }

    private fun clearPendingTransaction() {
        check(
            transactionJournal.edit()
                .remove(KEY_PENDING_TRANSACTION)
                .commit()
        ) { "Could not clear settings save journal" }
    }

    fun getPat(): String = secrets.getPat()
    fun setPat(pat: String) = secrets.setPat(pat)
    fun getPat(sourceKey: String): String = secrets.getPat(sourceKey)
    fun getSourcePat(sourceKey: String): String? = secrets.getSourcePat(sourceKey)
    fun setPat(sourceKey: String, pat: String) = secrets.setPat(sourceKey, pat)
    fun replaceSourcePats(sourcePats: Map<String, String>, activeSourceKeys: Set<String>) =
        secrets.replaceSourcePats(sourcePats, activeSourceKeys)

    private fun decodeSources(raw: String?): List<GitHubSource>? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(sourceListSerializer, raw) }.getOrNull()
    }

    private companion object {
        const val KEY_PENDING_TRANSACTION = "pending_source_registry_save"
    }
}
