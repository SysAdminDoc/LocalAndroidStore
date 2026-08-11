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
import kotlinx.coroutines.flow.onEach
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

enum class SourceRegistryPayloadState {
    Missing,
    Valid,
    Malformed,
}

data class SourceRegistryInspection(
    val settings: AppSettings,
    val payloadState: SourceRegistryPayloadState,
    val malformedPayload: String? = null,
    val backupAvailable: Boolean = false,
) {
    val requiresRecovery: Boolean get() = payloadState == SourceRegistryPayloadState.Malformed
}

class MalformedSourceRegistryException(
    val rawPayload: String,
    val backupAvailable: Boolean,
) : IllegalStateException(
    "The saved GitHub source registry is malformed. A recovery copy was preserved; " +
        "review the fallback entries and explicitly replace the saved registry.",
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
    private val recoveryStore: SharedPreferences = context.getSharedPreferences(
        "settings_recovery",
        Context.MODE_PRIVATE,
    ),
) {
    private val keyUser = stringPreferencesKey("github_user")
    private val keyTopic = stringPreferencesKey("topic")
    private val keyFilterByTopic = booleanPreferencesKey("filter_by_topic")
    private val keyPrereleases = booleanPreferencesKey("show_prereleases")
    private val keySources = stringPreferencesKey("github_sources_v1")
    private val keyFdroidSources = stringPreferencesKey("fdroid_sources_v1")
    private val keyHideUnverifiedSources = booleanPreferencesKey("hide_unverified_sources")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val sourceListSerializer = ListSerializer(GitHubSource.serializer())
    private val fdroidSourceListSerializer = ListSerializer(FdroidSource.serializer())
    private val transactionMutex = Mutex()

    val flow: Flow<AppSettings> = dataStore.data
        .onStart { recoverPendingTransaction() }
        .map(::inspectPreferences)
        .onEach { persistMalformedPayload(it.malformedPayload) }
        .map { it.settings }

    /** Recover an interrupted registry/PAT transaction before exposing settings to callers. */
    suspend fun recoverPendingTransaction() = transactionMutex.withLock {
        recoverPendingTransactionLocked()
    }

    /** Inspect the persisted registry without replacing malformed data with a default. */
    suspend fun inspectSourceRegistry(): SourceRegistryInspection = transactionMutex.withLock {
        inspectSourceRegistryLocked()
    }

    fun malformedSourceRegistryBackup(): String? =
        recoveryStore.getString(KEY_MALFORMED_SOURCES_BACKUP, null)

    /** Persist the source registry and source PATs as one recoverable transaction. */
    suspend fun saveSourceRegistry(
        settings: AppSettings,
        sourcePats: Map<String, String>,
    ) = saveSourceRegistryInternal(settings, sourcePats, allowMalformedReplacement = false)

    /** Explicitly replace a malformed persisted registry after the user reviews the fallback. */
    suspend fun replaceMalformedSourceRegistry(
        settings: AppSettings,
        sourcePats: Map<String, String>,
    ) = saveSourceRegistryInternal(settings, sourcePats, allowMalformedReplacement = true)

    private suspend fun saveSourceRegistryInternal(
        settings: AppSettings,
        sourcePats: Map<String, String>,
        allowMalformedReplacement: Boolean,
    ) = transactionMutex.withLock {
        recoverPendingTransactionLocked()
        val inspection = inspectSourceRegistryLocked()
        if (inspection.requiresRecovery && !allowMalformedReplacement) {
            throw MalformedSourceRegistryException(
                rawPayload = requireNotNull(inspection.malformedPayload),
                backupAvailable = inspection.backupAvailable,
            )
        }
        val previous = inspection.settings
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
            val inspection = inspectSourceRegistryLocked()
            if (inspection.requiresRecovery) {
                throw MalformedSourceRegistryException(
                    rawPayload = requireNotNull(inspection.malformedPayload),
                    backupAvailable = inspection.backupAvailable,
                )
            }
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

    private suspend fun inspectSourceRegistryLocked(): SourceRegistryInspection =
        dataStore.data.first()
            .let(::inspectPreferences)
            .let { inspection ->
                inspection.copy(
                    backupAvailable = persistMalformedPayload(inspection.malformedPayload),
                )
            }

    private suspend fun readSettingsLocked(): AppSettings =
        inspectSourceRegistryLocked().settings

    private suspend fun writeSettings(settings: AppSettings) {
        val canonical = canonicalSettings(settings)
        val primary = canonical.sources.first()
        dataStore.edit { prefs ->
            prefs[keyUser] = primary.user
            prefs[keyTopic] = primary.topic
            prefs[keyFilterByTopic] = primary.filterByTopic
            prefs[keyPrereleases] = primary.showPrereleases
            prefs[keySources] = json.encodeToString(sourceListSerializer, canonical.sources)
            prefs[keyFdroidSources] = json.encodeToString(
                fdroidSourceListSerializer,
                canonical.fdroidSources,
            )
            prefs[keyHideUnverifiedSources] = canonical.hideUnverifiedSources
        }
    }

    private fun inspectPreferences(prefs: Preferences): SourceRegistryInspection {
        val legacy = AppSettings(
            githubUser = prefs[keyUser] ?: DEFAULT_GITHUB_USER,
            topic = prefs[keyTopic] ?: DEFAULT_GITHUB_TOPIC,
            filterByTopic = prefs[keyFilterByTopic] ?: false,
            showPrereleases = prefs[keyPrereleases] ?: false,
        )
        val decoded = decodeSources(prefs[keySources])
        val sources = (decoded.sources ?: listOf(legacySource(legacy)))
        return SourceRegistryInspection(
            settings = legacy.copy(
                sources = normalizeSources(sources),
                fdroidSources = decodeFdroidSources(prefs[keyFdroidSources]),
                hideUnverifiedSources = prefs[keyHideUnverifiedSources] ?: false,
            ),
            payloadState = decoded.state,
            malformedPayload = decoded.rawPayload,
        )
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
            fdroidSources = normalizeFdroidSources(settings.fdroidSources),
            hideUnverifiedSources = settings.hideUnverifiedSources,
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

    private fun decodeSources(raw: String?): DecodedSources {
        if (raw == null) return DecodedSources(SourceRegistryPayloadState.Missing)
        val sources = runCatching {
            json.decodeFromString(sourceListSerializer, raw)
        }.getOrNull() ?: return DecodedSources(
            state = SourceRegistryPayloadState.Malformed,
            rawPayload = raw,
        )
        val keys = sources.map { it.key }
        if (
            sources.isEmpty() ||
            sources.any { it.user.isBlank() } ||
            keys.toSet().size != keys.size
        ) {
            return DecodedSources(
                state = SourceRegistryPayloadState.Malformed,
                rawPayload = raw,
            )
        }
        return DecodedSources(
            state = SourceRegistryPayloadState.Valid,
            sources = sources,
        )
    }

    private fun decodeFdroidSources(raw: String?): List<FdroidSource> {
        if (raw == null) return emptyList()
        return runCatching {
            json.decodeFromString(fdroidSourceListSerializer, raw)
        }.getOrDefault(emptyList())
            .let(::normalizeFdroidSources)
    }

    private fun persistMalformedPayload(rawPayload: String?): Boolean {
        if (rawPayload == null) return false
        return runCatching {
            if (recoveryStore.getString(KEY_MALFORMED_SOURCES_BACKUP, null) != rawPayload) {
                check(
                    recoveryStore.edit()
                        .putString(KEY_MALFORMED_SOURCES_BACKUP, rawPayload)
                        .putLong(KEY_MALFORMED_SOURCES_BACKUP_AT, System.currentTimeMillis())
                        .commit(),
                ) { "Could not preserve malformed source registry" }
            }
            recoveryStore.getString(KEY_MALFORMED_SOURCES_BACKUP, null) == rawPayload
        }.getOrDefault(false)
    }

    private companion object {
        const val KEY_PENDING_TRANSACTION = "pending_source_registry_save"
        const val KEY_MALFORMED_SOURCES_BACKUP = "malformed_github_sources_v1"
        const val KEY_MALFORMED_SOURCES_BACKUP_AT = "malformed_github_sources_v1_at"
    }
}

private data class DecodedSources(
    val state: SourceRegistryPayloadState,
    val sources: List<GitHubSource>? = null,
    val rawPayload: String? = null,
)
