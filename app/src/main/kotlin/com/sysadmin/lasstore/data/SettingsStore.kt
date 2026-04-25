package com.sysadmin.lasstore.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(SettingsSchemaMigration) },
)

private object SettingsSchemaMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = false

    override suspend fun migrate(currentData: Preferences): Preferences = currentData

    override suspend fun cleanUp() = Unit
}

class SettingsStore(private val context: Context, private val secrets: SecretStore) {
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

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val legacy = AppSettings(
            githubUser = prefs[keyUser] ?: DEFAULT_GITHUB_USER,
            topic = prefs[keyTopic] ?: DEFAULT_GITHUB_TOPIC,
            filterByTopic = prefs[keyFilterByTopic] ?: false,
            showPrereleases = prefs[keyPrereleases] ?: false,
        )
        val sources = decodeSources(prefs[keySources]) ?: listOf(legacySource(legacy))
        legacy.copy(sources = normalizeSources(sources))
    }

    suspend fun update(settings: AppSettings) {
        val sources = normalizeSources(settings.sources)
        val primary = sources.first()
        context.settingsDataStore.edit { prefs ->
            prefs[keyUser] = primary.user
            prefs[keyTopic] = primary.topic
            prefs[keyFilterByTopic] = primary.filterByTopic
            prefs[keyPrereleases] = primary.showPrereleases
            prefs[keySources] = json.encodeToString(sourceListSerializer, sources)
        }
    }

    fun getPat(): String = secrets.getPat()
    fun setPat(pat: String) = secrets.setPat(pat)
    fun getPat(sourceKey: String): String = secrets.getPat(sourceKey)
    fun setPat(sourceKey: String, pat: String) = secrets.setPat(sourceKey, pat)

    private fun decodeSources(raw: String?): List<GitHubSource>? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(sourceListSerializer, raw) }.getOrNull()
    }
}
